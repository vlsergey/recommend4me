package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.BASE
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.COMMENTS
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.DEDICATION
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.LIKES
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.NOTES
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.ONLY_PART
import io.github.vlsergey.recommend4me.source.ficbook.FicbookFields.Companion.AUTHOR_ID
import io.github.vlsergey.recommend4me.source.ficbook.FicbookFields.Companion.cover
import io.github.vlsergey.recommend4me.source.ficbook.FicbookFields.Companion.pictured
import java.net.URI
import java.time.Instant
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Reads the pages of ficbook.net and writes what they say: the cards of a list (a fandom, a tag, a search, a
 * collection), a work's header and table of contents, the text of a part, the comments; and what the site's tags mean
 * ([FicbookTagMeanings]) and what its fandoms are of ([FicbookFandoms]).
 */
class FicbookPages(private val context: SourceContext) {

    private val items = context.items
    private val fields = FicbookFields(items)
    private val meanings = FicbookTagMeanings(items)
    private val series = FicbookSeries(items)
    private val fandoms = FicbookFandoms(items)

    fun read(page: CapturedPage): List<String> {
        val path = runCatching { URI(page.url).path }.getOrDefault("")
        if (path == TAG_SEARCH) {
            meanings.tagSearch(page)
            return emptyList()
        }
        val doc = Jsoup.parse(page.html, page.url)
        val touched = LinkedHashSet<String>()
        touched += cards(doc, page.capturedAt)
        if (TAG_PAGE.matches(path)) meanings.tagPage(doc)
        FANDOM_PAGE.matchEntire(path)?.groupValues?.get(1)?.let { fandoms.page(doc, it) }
        // A series' own page, and an author's list of series: after the cards, whose works they list
        FicbookSeries.SERIES.matchEntire(path)?.groupValues?.get(1)?.let { series.page(doc, it) }
        if (AUTHOR_SERIES.matches(path)) series.list(doc)
        READFIC.matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            val rest = m.groupValues[2]
            when {
                rest.isEmpty() -> work(doc, id, page.capturedAt)?.let { touched += it }
                rest == "comments" -> Unit
                rest.all { it.isDigit() } -> part(doc, id, rest, page.capturedAt)?.let { touched += it }
            }
            if (comments(doc, id)) touched += id
        }
        return touched.toList()
    }

    // --- Cards of a list ---

    private fun cards(doc: Document, now: Instant): List<String> =
        doc.select("article.fanfic-inline").mapNotNull { card ->
            val link = card.selectFirst(".fanfic-inline-title a[href^=/readfic/]") ?: return@mapNotNull null
            val id = workId(link.attr("href")) ?: return@mapNotNull null
            val title =
                link.text().trim().ifEmpty {
                    return@mapNotNull null
                }
            val info =
                card.select("dl.fanfic-inline-info").associate { dl ->
                    dl.selectFirst("dt")?.text()?.trim()?.removeSuffix(":").orEmpty() to dl.selectFirst("dd")
                }
            val updated =
                info.entries.firstOrNull { it.key.startsWith("Дата") }?.value?.text()?.let { PageText.russianDate(it) }
            items.upsert(ItemHead(id, "$BASE/readfic/$id", title, updatedAt = updated), now)
            fields.badges(id, card.selectFirst(".fanfic-badges"))
            card.selectFirst(".js-like-count")?.text()?.let(PageText::number)?.let {
                items.setNumbers(id, mapOf(LIKES to it.toDouble()))
            }
            fields.described(id, info.mapValues { it.value })
            card.select(".tags a.tag").takeIf { it.isNotEmpty() }?.let { fields.tags(id, it) }
            card.selectFirst(".fanfic-description-text")?.let {
                items.setTexts(id, mapOf(ANNOTATION to PageText.of(it)))
            }
            pictured(card)?.let { items.setPictures(id, listOf(it)) }
            id
        }

    // --- A work's page ---

    /**
     * A work's own page: its header ("hat") and the blocks of it, the table of contents. A page without the header — an
     * error page, a changed markup — says nothing of the work, and nothing is written: what it does not show is not
     * known to be gone. Of the header, a block it has not is a thing the work has not.
     */
    private fun work(doc: Document, id: String, now: Instant): String? {
        val title = doc.selectFirst("h1.heading, h1[itemprop=name]")?.text()?.trim()?.ifEmpty { null }
        val hat = doc.selectFirst(".fanfic-hat")
        if (title == null || hat == null) return null
        val blocks =
            hat.select(".description > .mb-10").associate { block ->
                block.selectFirst("strong")?.text()?.trim()?.removeSuffix(":").orEmpty() to
                    block.selectFirst("strong + div")
            }
        val parts = contents(doc)
        items.upsert(
            ItemHead(id, "$BASE/readfic/$id", title, updatedAt = parts.mapNotNull { it.publishedAt }.maxOrNull()),
            now,
        )
        fields.badges(id, doc.selectFirst("section.fanfic-badges"))
        cover(hat)?.let { items.setPictures(id, listOf(it)) }
        doc.selectFirst("section.fanfic-badges button span")?.text()?.let(PageText::number)?.let {
            items.setNumbers(id, mapOf(LIKES to it.toDouble()))
        }
        val authors =
            hat.select("[itemprop=author]").mapNotNull { a ->
                AUTHOR_ID.find(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, a.text().trim()) }
            }
        if (authors.isNotEmpty()) items.setFacet(id, AUTHOR, authors)
        fields.described(id, blocks.filterKeys { it != "Автор" })
        // The block of tags with no tag found in it: its markup changed, not the work's tags gone
        blocks["Метки"]?.let { block ->
            block.select("a.tag").takeIf { it.isNotEmpty() || block.text().isBlank() }?.let { fields.tags(id, it) }
        }
        items.setTexts(
            id,
            mapOf(
                ANNOTATION to blocks["Описание"]?.let(PageText::of),
                NOTES to blocks["Примечания"]?.let(PageText::of),
                DEDICATION to blocks["Посвящение"]?.let(PageText::of),
            ),
        )
        doc.select("a[href=/readfic/$id/comments#comments-list]")
            .firstNotNullOfOrNull { PageText.number(it.text()) }
            ?.let { items.setNumbers(id, mapOf(COMMENTS to it.toDouble())) }
        marks(doc, id)
        if (parts.isNotEmpty()) items.saveParts(id, parts)
        // A work of one part has its text on its own page, without a table of contents
        else
            doc.selectFirst("#content")?.let { content ->
                val text = PageText.of(content, NOT_THE_TEXT)
                if (text.isNotBlank()) items.saveParts(id, listOf(PartData(ONLY_PART, 0, title, text)))
            }
        return id
    }

    /** The parts the work's table of contents names, each with the day it came out, without their texts. */
    private fun contents(doc: Document): List<PartData> =
        doc.select("ul.list-of-fanfic-parts li.part").mapIndexedNotNull { i, li ->
            val a = li.selectFirst("a.part-link[href^=/readfic/]") ?: return@mapIndexedNotNull null
            val partId =
                READFIC.matchEntire(a.attr("href").substringBefore('#').substringBefore('?'))
                    ?.groupValues
                    ?.get(2)
                    ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@mapIndexedNotNull null
            PartData(
                partId,
                i,
                li.selectFirst(".part-title")?.text()?.trim(),
                null,
                li.selectFirst(".part-info [title]")?.attr("title")?.let { PageText.russianDate(it) },
            )
        }

    /**
     * The user's own marks of the work in the buttons of its header — liked, read, followed: a button pressed is
     * coloured "success". Read only of a page of a user logged in, the one that has the menu of their liked works: to
     * anyone else the page tells nothing of them.
     */
    private fun marks(doc: Document, id: String) {
        if (doc.selectFirst("a[href=/home/liked_fanfics]") == null) return
        fun pressed(icon: String): Boolean? =
            doc.select(".hat-actions-container button:has(svg.$icon)").firstOrNull()?.let { b ->
                b.classNames().any { it.startsWith("ds-btn-success") }
            }
        pressed("ic_thumbs-up")?.let { context.signals.set(id, Books.LIKED.key, Books.YES.key.takeIf { _ -> it }) }
        pressed("ic_star-empty")?.let {
            context.signals.set(id, Ficbook.SIGNAL_FOLLOWED, Books.YES.key.takeIf { _ -> it })
        }
        // The box "read" of the work, ticked or not
        when {
            doc.selectFirst("button:has(svg.ic_checkbox-checked2)") != null ->
                context.signals.set(id, Books.READ.key, Books.YES.key)
            doc.selectFirst("button:has(svg.ic_checkbox-unchecked2)") != null ->
                context.signals.set(id, Books.READ.key, null)
        }
    }

    // --- A part ---

    /** The text of a part, of a work known — or made known by the part's own page ([known]). */
    private fun part(doc: Document, id: String, partId: String, now: Instant): String? {
        val text = doc.selectFirst("#content")?.let { PageText.of(it, NOT_THE_TEXT) }
        if (text.isNullOrBlank() || !known(doc, id, now)) return null
        marks(doc, id)
        val area = doc.selectFirst(".title-area")
        items.saveParts(
            id,
            listOf(
                PartData(
                    partId,
                    -1,
                    area?.selectFirst("h2")?.text()?.trim(),
                    text,
                    area?.selectFirst(".part-date [title]")?.attr("title")?.let { PageText.russianDate(it) },
                )
            ),
        )
        return id
    }

    /**
     * Whether the work [id] is known — or made known by the page of its part, opened before the work's: its title is
     * the page's heading. No title, no work.
     */
    private fun known(doc: Document, id: String, now: Instant): Boolean {
        if (items.find(id) != null) return true
        val title = doc.selectFirst("h1.heading, h1[itemprop=name]")?.text()?.trim()?.ifEmpty { null }
        if (title != null) items.upsert(ItemHead(id, "$BASE/readfic/$id", title), now)
        return title != null
    }

    // --- Comments ---

    /**
     * The comments the page shows, on the work's own page of comments or under a part; quotes of other comments left
     * out.
     */
    private fun comments(doc: Document, id: String): Boolean {
        val list = doc.select("article.comment-container[id^=com]")
        if (list.isEmpty() || items.find(id) == null) return false
        items.saveReviews(
            id,
            list.mapNotNull { c ->
                val message = c.selectFirst(".js-comment-message") ?: return@mapNotNull null
                val text =
                    PageText.of(message, ".quoted, script, style").ifBlank {
                        return@mapNotNull null
                    }
                ReviewData(
                    c.id().removePrefix("com"),
                    c.selectFirst(".js-comment-author")?.text()?.trim(),
                    null,
                    text,
                    c.selectFirst("time.comment-date")?.let {
                        PageText.russianDate(it.attr("datetime").ifBlank { it.text() })
                    },
                )
            },
        )
        return true
    }

    companion object {
        /** A work's address: "/readfic/<id>", "/readfic/<id>/<part>", "/readfic/<id>/comments". */
        private val READFIC = Regex("/readfic/([0-9a-f-]+)/?([^/]*)/?")
        private val TAG_PAGE = Regex("/tags/\\d+/?")

        /** A fandom's own page, the list of its works: "/fanfiction/books/rouling_dzhoan___garri_potter". */
        private val FANDOM_PAGE = Regex("/fanfiction/([^/]+/[^/]+)/?")

        /** An author's list of series: "/authors/3093607/series". */
        private val AUTHOR_SERIES = Regex("/authors/[^/]+/series/?")

        /** The site's search of tags ([Ficbook.tagSearch]). */
        private const val TAG_SEARCH = "/tags/search"

        /**
         * What the site puts into the text of a part that is not the text: the promotion of another work, put by its
         * scripts into a placeholder among the paragraphs; and the scripts and styles.
         */
        private const val NOT_THE_TEXT = ".js-fanfic-text-promo-placeholder, [class*=promo], script, style"

        /** The id of the work a link leads to: "/readfic/7194723", "/readfic/019b5f41-…". */
        internal fun workId(href: String): String? =
            READFIC.matchEntire(href.substringBefore('?').substringBefore('#'))?.groupValues?.get(1)
    }
}
