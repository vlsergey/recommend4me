package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.BOOKMARKS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.CATEGORY
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.COMMENTS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.HITS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.KUDOS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.LANGUAGE
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.PAIRING
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.PAIRINGS_LINE
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.PARTS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.SERIES
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.STATUS
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.TAG
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.WORDS
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.jsoup.nodes.Element

/**
 * The fields of a work as a card of a list and a work's header both tell them — its authors, fandoms, category, rating,
 * tags, series, statistics and language — written to the work's item: the card and the header find them in their own
 * markup, and the same words mean the same here.
 */
internal class Ao3Fields(private val items: ItemStore) {

    fun authors(id: String, links: List<Element>) {
        val authors =
            links
                .mapNotNull { a ->
                    // A user's pseudonym is the user: "/users/<login>/pseuds/<pseud>"
                    USER.find(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, a.text().trim()) }
                }
                .distinctBy { it.key }
        if (authors.isNotEmpty()) items.setFacet(id, AUTHOR, authors)
    }

    fun fandoms(id: String, links: List<Element>) {
        val fandoms = links.map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        if (fandoms.isEmpty()) return
        items.setFacet(id, FANDOM, fandoms.map { FacetValue(it, it) })
        val original = fandoms.all { it == ORIGINAL_WORK }
        items.setFacet(
            id,
            UniverseFacets.KIND,
            listOf(FacetValue(if (original) UniverseFacets.ORIGINAL else UniverseFacets.FANFICTION)),
        )
    }

    /** The categories as the standard directions, shared with ficbook's; "No category" is none. */
    fun category(id: String, written: List<String>) {
        items.setFacet(
            id,
            CATEGORY,
            written.mapNotNull { CATEGORIES[it] }.distinct().map { FacetValue(it.key, it.modelName) },
        )
    }

    /** The rating the archive states, on the age rating's scale; "Not Rated", or one unknown, tells nothing. */
    fun rating(id: String, written: String) {
        AgeRating.write(items, id, listOfNotNull(RATINGS[written]))
    }

    /**
     * The tags of the work: the relationships and the characters — one field, as written, and the line of them the
     * universes are worked out from — the warnings and the other tags, one field of the site's tags (the user may
     * ignore the warnings as any tag). A kind the page does not show is left as it was: the user may hide the warnings
     * and the other tags.
     */
    fun tagged(
        id: String,
        warnings: List<Element>,
        relationships: List<Element>,
        characters: List<Element>,
        freeforms: List<Element>,
    ) {
        fun names(links: List<Element>) = links.map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val pairings = names(relationships)
        val people = names(characters)
        if (pairings.isNotEmpty() || people.isNotEmpty()) {
            items.setFacet(id, PAIRING, (pairings + people).map(::entry).distinctBy { it.key })
            val line =
                listOfNotNull(
                        pairings.takeIf { it.isNotEmpty() }?.joinToString(", ", "Relationships: "),
                        people.takeIf { it.isNotEmpty() }?.joinToString(", ", "Characters: "),
                    )
                    .joinToString("\n")
            items.setTexts(id, mapOf(PAIRINGS_LINE to line))
        }
        val shownWarnings = names(warnings)
        val shownOthers = names(freeforms)
        if (shownWarnings.isEmpty() && shownOthers.isEmpty()) return
        // The kind not shown kept as it was: the warnings are the site's few, told from the other tags by their keys
        val before =
            if (shownWarnings.isEmpty() || shownOthers.isEmpty()) items.facets(id)[TAG].orEmpty() else emptyList()
        fun kept(warning: Boolean) = before.filter { (it in WARNINGS) == warning }.map { FacetValue(it) }
        val values =
            (if (shownWarnings.isEmpty()) kept(true) else shownWarnings.map { FacetValue(it.lowercase(), it) }) +
                (if (shownOthers.isEmpty()) kept(false) else shownOthers.map { FacetValue(it.lowercase(), it) })
        items.setFacet(id, TAG, values.distinctBy { it.key })
    }

    fun series(id: String, a: Element) {
        val key = a.attr("href").removePrefix("/series/").substringBefore('?').substringBefore('/')
        if (key.isNotEmpty()) items.setFacet(id, SERIES, listOf(FacetValue(key, a.text().trim())))
    }

    /**
     * The numbers of a card's or a header's statistics, and the status the chapters tell: "4/7" and "4/?" are in
     * progress.
     */
    fun stats(id: String, dl: Element) {
        fun count(name: String) = dl.selectFirst("dd.$name")?.text()?.let(::number)
        val chapters = dl.selectFirst("dd.chapters")?.text()?.trim()
        items.setNumbers(
            id,
            listOfNotNull(
                    count("words")?.let { WORDS to it },
                    chapters?.substringBefore('/')?.let(::number)?.let { PARTS to it },
                    count("kudos")?.let { KUDOS to it },
                    count("bookmarks")?.let { BOOKMARKS to it },
                    count("hits")?.let { HITS to it },
                    count("comments")?.let { COMMENTS to it },
                )
                .toMap(),
        )
        chapters
            ?.split('/')
            ?.takeIf { it.size == 2 }
            ?.let { (done, planned) ->
                val status = if (planned.trim() == done.trim()) Books.Status.FINISHED else Books.Status.IN_PROGRESS
                items.setFacet(id, STATUS, listOf(FacetValue(status.key, status.modelName)))
            }
        dl.selectFirst("dd.language")?.let { language(id, it) }
    }

    /** The language the element [dd] names, its code in its `lang`; none when it names none. */
    fun language(id: String, dd: Element) {
        val name = dd.text().trim()
        if (name.isNotEmpty())
            items.setFacet(id, LANGUAGE, listOf(FacetValue(dd.attr("lang").ifEmpty { name.lowercase() }, name)))
    }

    companion object {
        /** A user's page: "/users/<login>", its pseudonyms under it. */
        internal val USER = Regex("^/users/([^/?#]+)")

        /** The fandom of the original works. */
        private const val ORIGINAL_WORK = "Original Work"

        /** The keys of the site's warnings among its tags: the archive's own few, in every name they had. */
        private val WARNINGS =
            setOf(
                "no archive warnings apply",
                "creator chose not to use archive warnings",
                "graphic depictions of violence",
                "major character death",
                "rape/non-con",
                "underage",
                "underage sex",
            )

        private val CATEGORIES =
            mapOf(
                "Gen" to Books.Direction.GEN,
                "F/M" to Books.Direction.HET,
                "M/M" to Books.Direction.SLASH,
                "F/F" to Books.Direction.FEMSLASH,
                "Multi" to Books.Direction.MIXED,
                "Other" to Books.Direction.OTHER,
            )

        /** The archive's ratings by their names, on the age rating's scale; "Not Rated" none. */
        private val RATINGS =
            mapOf(
                "General Audiences" to AgeRating.G,
                "Teen And Up Audiences" to AgeRating.PG13,
                "Mature" to AgeRating.R,
                "Explicit" to AgeRating.NC17,
            )

        /** "6,284" → 6284. */
        private fun number(text: String): Double? =
            Regex("\\d[\\d,]*").find(text)?.value?.replace(",", "")?.toDoubleOrNull()

        /**
         * An entry of the relationships and characters as written — a romantic "A/B", a platonic "A & B", a character
         * alone: one whatever the order of its names — the key has them sorted.
         */
        private fun entry(written: String): FacetValue {
            val separator = if ('/' in written) "/" else if (" & " in written) " & " else null
            val key =
                separator?.let { s ->
                    written.split(s).map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString(s)
                } ?: written.trim().lowercase()
            return FacetValue(key, written)
        }
    }
}
