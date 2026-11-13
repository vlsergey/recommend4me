package io.github.vlsergey.recommend4me.source.molwe

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.contenttype.StandardValue
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.DIRECTION
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.PAIRING
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.PAIRINGS_LINE
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.STATUS
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.TAG
import io.github.vlsergey.recommend4me.universe.UniverseFacets

/**
 * The fields of a work as its page and its cards tell them alike — its badges, its fandoms, its line of pairings and
 * characters, its tags — written to the work's item.
 */
internal class MolweFields(private val items: ItemStore) {

    /**
     * The badges over the work's cover, "16+", "Гет", "Завершено": its rating, the kind of its relationships, its
     * status, each told by its word; a word of none of them says nothing.
     */
    fun badges(id: String, words: List<String>) {
        val said = words.map { it.trim() }
        said.firstNotNullOfOrNull { RATINGS[it] }?.let { AgeRating.write(items, id, listOf(it)) }
        said
            .firstNotNullOfOrNull { DIRECTIONS[it.lowercase()] }
            ?.let { items.setFacet(id, DIRECTION, listOf(value(it))) }
        said.firstNotNullOfOrNull { STATUSES[it.lowercase()] }?.let { items.setFacet(id, STATUS, listOf(value(it))) }
    }

    /**
     * The work's fandoms by their names — the site's own word for the original writing, "Ориджинал", one of them — and
     * whether the work is fan fiction or original writing, by the catalogue it is in.
     */
    fun fandoms(id: String, names: List<String>, original: Boolean?) {
        val fandoms = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (fandoms.isNotEmpty()) items.setFacet(id, FANDOM, fandoms.map { FacetValue(it.lowercase(), it) })
        original?.let { kind(id, it) }
    }

    /** Whether the work is fan fiction or original writing. */
    fun kind(id: String, original: Boolean) {
        val kind = if (original) UniverseFacets.ORIGINAL else UniverseFacets.FANFICTION
        items.setFacet(id, UniverseFacets.KIND, listOf(FacetValue(kind)))
    }

    /**
     * The work's line of pairings and characters as the site shows it: every entry as written, a pairing "A / B" or a
     * character alone, one field; and the line whole, pairings first, what the application works the cast out of.
     */
    fun cast(id: String, entries: List<String>) {
        val written = entries.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        items.setFacet(id, PAIRING, written.map(::entry).distinctBy { it.key })
        items.setTexts(id, mapOf(PAIRINGS_LINE to written.joinToString(", ").ifEmpty { null }))
    }

    /** The work's tags by their names: one name of the site's two catalogues one tag. */
    fun tags(id: String, names: List<String>) =
        items.setFacet(
            id,
            TAG,
            names.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { FacetValue(it.lowercase(), it) },
        )

    companion object {
        /**
         * The site's age ratings on the age rating's scale: no definition of them published, they are the steps of the
         * Russian law of the information for children — 0+ and 6+ for any reader, 12+, 16+, 18+ — and the site's 21+
         * above them, onto the scale's steps in their order.
         */
        private val RATINGS =
            mapOf(
                "0+" to AgeRating.G,
                "6+" to AgeRating.G,
                "12+" to AgeRating.PG13,
                "16+" to AgeRating.R,
                "18+" to AgeRating.NC17,
                "21+" to AgeRating.NC21,
            )

        /** The site's words of the kind of the relationships, as its badges write them, to the standard ones. */
        private val DIRECTIONS =
            listOf(
                    Books.Direction.GEN,
                    Books.Direction.HET,
                    Books.Direction.SLASH,
                    Books.Direction.FEMSLASH,
                    Books.Direction.MIXED,
                    Books.Direction.OTHER,
                    Books.Direction.ARTICLE,
                )
                .associateBy { it.modelName.lowercase() } +
                mapOf(
                    "слеш" to Books.Direction.SLASH,
                    "фемслеш" to Books.Direction.FEMSLASH,
                    "смешанный" to Books.Direction.MIXED,
                    "другое" to Books.Direction.OTHER,
                )

        /** The site's statuses, as its badges write them, to the standard ones. */
        private val STATUSES =
            listOf(Books.Status.FINISHED, Books.Status.IN_PROGRESS, Books.Status.FROZEN).associateBy {
                it.modelName.lowercase()
            }

        private fun value(standard: StandardValue) = FacetValue(standard.key, standard.modelName)

        /** An entry as written, "Гермиона Грейнджер / Драко Малфой": one whatever the order of its names. */
        private fun entry(written: String): FacetValue =
            FacetValue(
                written.split('/').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.sorted().joinToString("/"),
                written,
            )
    }
}
