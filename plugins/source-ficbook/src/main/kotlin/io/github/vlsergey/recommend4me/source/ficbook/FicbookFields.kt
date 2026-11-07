package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.DIRECTION
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.PAGES
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.PAIRING
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.PAIRINGS_LINE
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.PARTS
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.SERIES
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.STATUS
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.TAG
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.WORDS
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.jsoup.nodes.Element

/**
 * What a card of a list and a work's header both show, written to the work's item: the labels — the direction, the
 * rating, the status — the labelled lines — the author, the fandoms, the series, the pairings, the size — the tags and
 * the cover.
 */
internal class FicbookFields(private val items: ItemStore) {

    /** The direction, the rating and the status a card or a header shows as labels. */
    fun badges(id: String, badges: Element?) {
        badges ?: return
        badges.selectFirst(".direction")?.let { d ->
            // The site's classes are the standard keys; "unknown" — not told — is no direction
            val key = d.classNames().firstOrNull { it.startsWith("direction-") }?.removePrefix("direction-")
            if (key != null)
                items.setFacet(
                    id,
                    DIRECTION,
                    listOfNotNull(Books.DIRECTION.value(key)?.let { FacetValue(it.key, it.modelName) }),
                )
        }
        // The rating the site states: the age rating's own scale, one of it ("NC-17")
        badges.select("[class*=ds-label-rating-]").firstOrNull()?.let { r ->
            val key = r.classNames().first { it.startsWith("ds-label-rating-") }.removePrefix("ds-label-rating-")
            AgeRating.write(items, id, listOfNotNull(AgeRating.VALUES.firstOrNull { it.key == key.lowercase() }))
        }
        badges.select("[class*=ds-label-status-]").firstOrNull()?.let { s ->
            val key = s.classNames().first { it.startsWith("ds-label-status-") }.removePrefix("ds-label-status-")
            val text = s.text().trim()
            // The standard status by the label's class or its text; another as the site writes it, for the application
            // to tell of
            val status = STATUSES[key] ?: STATUSES[text.lowercase()]
            items.setFacet(
                id,
                STATUS,
                listOf(status?.let { FacetValue(it.key, it.modelName) } ?: FacetValue(key, text.ifEmpty { key })),
            )
        }
    }

    /**
     * What the labelled lines of a card or a header say: the author, the fandoms, the series, the pairings, the size.
     */
    fun described(id: String, info: Map<String, Element?>) {
        info["Автор"]?.let { dd ->
            val authors =
                dd.select("a[href^=/authors/]").mapNotNull { a ->
                    AUTHOR_ID.find(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, a.text().trim()) }
                }
            if (authors.isNotEmpty()) items.setFacet(id, AUTHOR, authors)
        }
        info["Фэндом"]?.let { dd ->
            val fandoms =
                dd.select("a[href^=/fanfiction/]").map { a ->
                    FacetValue(a.attr("href").removePrefix("/fanfiction/").substringBefore('?'), a.text().trim())
                }
            if (fandoms.isNotEmpty()) {
                items.setFacet(id, FANDOM, fandoms)
                // A work of the fandom "originals" is an original one; of any other, fan fiction
                val original = fandoms.all { it.key == ORIGINALS }
                items.setFacet(
                    id,
                    UniverseFacets.KIND,
                    listOf(FacetValue(if (original) UniverseFacets.ORIGINAL else UniverseFacets.FANFICTION)),
                )
            }
        }
        info["Серия"]?.selectFirst("a[href^=/series/]")?.let { a ->
            items.setFacet(
                id,
                SERIES,
                listOf(FacetValue(a.attr("href").removePrefix("/series/").substringBefore('?'), a.text().trim())),
            )
        }
        info["Пэйринг и персонажи"]?.let { dd ->
            // The line as the site shows it: what the application works the pairings and characters out from
            items.setTexts(id, mapOf(PAIRINGS_LINE to PageText.of(dd)))
            // Every entry of the line as written, one field: a pairing "A/B" and a character alone
            val entries =
                dd.select("a")
                    .map { it.text().trim() }
                    .flatMap { it.split(", ") }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            items.setFacet(id, PAIRING, entries.map(::entry).distinctBy { it.key })
        }
        info["Размер"]?.text()?.let { size ->
            items.setNumbers(
                id,
                listOfNotNull(
                        PageText.numberBefore(size, "стр")?.let { PAGES to it.toDouble() },
                        PageText.numberBefore(size, "слов")?.let { WORDS to it.toDouble() },
                        PageText.numberBefore(size, "част")?.let { PARTS to it.toDouble() },
                    )
                    .toMap(),
            )
        }
    }

    fun tags(id: String, links: List<Element>) =
        items.setFacet(
            id,
            TAG,
            links.map { a -> a.text().trim().let { FacetValue(it.lowercase(), it) } }.filter { it.key.isNotEmpty() },
        )

    companion object {
        internal val AUTHOR_ID = Regex("^/authors/([^/?#]+)")

        /** The fandom of the original works. */
        private const val ORIGINALS = "no_fandom/originals"

        /** The statuses of the site, by the class of their label and by its text, to the standard ones. */
        private val STATUSES =
            mapOf(
                "in-progress" to Books.Status.IN_PROGRESS,
                "в процессе" to Books.Status.IN_PROGRESS,
                "finished" to Books.Status.FINISHED,
                "завершён" to Books.Status.FINISHED,
                "завершен" to Books.Status.FINISHED,
                "frozen" to Books.Status.FROZEN,
                "заморожен" to Books.Status.FROZEN,
            )

        /**
         * The cover of a work's header, of the size a card of a list shows — one picture, whichever page it is read of:
         * as the site sends it, the attribute of its component; as the browser drew it, the picture ([pictured]). None
         * when the header has none: a work without a cover, or a markup changed.
         */
        fun cover(hat: Element): String? =
            hat.selectFirst("fanfic-cover[src-desktop]")?.attr("src-desktop")?.takeIf { it.isNotBlank() }
                ?: hat.selectFirst(".fanfic-hat-cover")?.let(::pictured)

        /**
         * A cover drawn as a picture — of a card, of a header — at the size of a wide screen: the source the picture
         * gives for it, else the picture's own; a cover of the older markup (img.fic-cover) as it is. The size of a
         * card and of a header is one: one picture of a work.
         */
        fun pictured(at: Element): String? {
            val picture =
                at.selectFirst("picture.fanfic-hat-cover-picture")
                    ?: return at.selectFirst("img.fic-cover[src]")?.attr("src")?.takeIf { it.isNotBlank() }
            val wide =
                picture.selectFirst("source[media*=min-width][srcset]")?.attr("srcset")?.trim()?.substringBefore(' ')
            return wide?.takeIf { it.isNotBlank() }
                ?: picture.selectFirst("img[src]")?.attr("src")?.takeIf { it.isNotBlank() }
        }

        /**
         * An entry of the line as written — a pairing "A/B", or a character alone: a pairing is one whatever the order
         * of its names — the key has them sorted.
         */
        private fun entry(written: String): FacetValue =
            FacetValue(
                written.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString("/"),
                written,
            )
    }
}
