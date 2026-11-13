package io.github.vlsergey.recommend4me.source.ffn

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.CATEGORY
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.FAVS
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.FOLLOWS
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.LANGUAGE
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.PAIRING
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.PAIRINGS_LINE
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.PARTS
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.REVIEWS
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.STATUS
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.TAG
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.WORDS
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.util.Locale

/** The fields of a work as its page and its cards tell them alike, written to the work's item. */
internal class FfnFields(private val items: ItemStore) {

    /**
     * What the site's line of a work tells ([FfnLine]): its rating, language, genres as its tags, characters and
     * pairings, status and numbers; its fandoms of a line that names them.
     */
    fun line(id: String, line: FfnLine) {
        if (line.fandoms.isNotEmpty()) fandoms(id, line.fandoms)
        line.rating?.let { r -> RATINGS[r]?.let { AgeRating.write(items, id, listOf(it)) } }
        line.language?.let { items.setFacet(id, LANGUAGE, listOf(FacetValue(languageCode(it), it))) }
        items.setFacet(id, TAG, line.genres.map { FacetValue(it.lowercase(), it) })
        val cast = line.characters?.let(FfnLine::castOf).orEmpty()
        items.setFacet(id, PAIRING, cast.map(::entry).distinctBy { it.key })
        items.setTexts(id, mapOf(PAIRINGS_LINE to line.characters))
        val status = if (line.complete) Books.Status.FINISHED else Books.Status.IN_PROGRESS
        items.setFacet(id, STATUS, listOf(FacetValue(status.key, status.modelName)))
        items.setNumbers(
            id,
            COUNTS.mapNotNull { (caption, key) -> line.counts[caption]?.let { key to it.toDouble() } }.toMap(),
        )
    }

    /** The work's fandoms by their names; every work of the site is fan fiction. */
    fun fandoms(id: String, names: List<String>) {
        val fandoms = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (fandoms.isEmpty()) return
        items.setFacet(id, FANDOM, fandoms.map { FacetValue(it, it) })
        items.setFacet(id, UniverseFacets.KIND, listOf(FacetValue(UniverseFacets.FANFICTION)))
    }

    /** The site's section the work's fandom is in: "Books", "Anime/Manga". */
    fun category(id: String, name: String) = items.setFacet(id, CATEGORY, listOf(FacetValue(name, name)))

    companion object {
        /**
         * The site's ratings on the age rating's scale, by their definitions at fictionratings.com, which the site
         * links them to: K for every age, K+ for nine years and older, T for thirteen and older, M for sixteen and
         * older.
         */
        private val RATINGS = mapOf("K" to AgeRating.G, "K+" to AgeRating.G, "T" to AgeRating.PG13, "M" to AgeRating.R)

        /** The numbers of the line by their captions. */
        private val COUNTS =
            listOf("Chapters" to PARTS, "Words" to WORDS, "Reviews" to REVIEWS, "Favs" to FAVS, "Follows" to FOLLOWS)

        /** The codes of the languages by their English names, as the site writes them: "English" → "en". */
        private val LANGUAGES: Map<String, String> =
            Locale.getAvailableLocales()
                .filter { it.language.isNotEmpty() }
                .associate { it.getDisplayLanguage(Locale.ENGLISH).lowercase() to it.language }

        /** The language's code; of a name no locale has, its name. */
        private fun languageCode(name: String) = LANGUAGES[name.lowercase()] ?: name.lowercase()

        /** A pairing "Harry P./Hermione G." one whatever the order of its names; a character alone its name. */
        private fun entry(written: String): FacetValue =
            FacetValue(
                written.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString("/"),
                written,
            )
    }
}
