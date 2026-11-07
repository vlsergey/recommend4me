package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.FORM
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.TAG
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.jsoup.nodes.Element

/**
 * The form, the genres and the tags of a work as a card or a work's page shows them, and the kind of the work its
 * genres tell.
 */
internal class AuthorTodayGenres(private val items: ItemStore) {

    /**
     * The form, the genres and the tags a card or a work's page shows. The genres are the site's tags as its tags are,
     * by their names (user, 2026-10-27): of a page, its [tags] and its genres the work's tags of the site; of a card —
     * no tags on it, [tags] null — its genres added to the tags known, none of them taken away.
     */
    fun write(id: String, form: FacetValue?, genres: List<FacetValue>, tags: List<FacetValue>?) {
        form?.let { items.setFacet(id, FORM, listOf(it)) }
        val genreTags = genres.mapNotNull { g -> g.name?.takeIf { it.isNotBlank() }?.let { siteTag(it) } }
        if (tags != null) items.setFacet(id, TAG, tags + genreTags)
        else if (genreTags.isNotEmpty()) {
            val known = items.facets(id)[TAG].orEmpty()
            items.setFacet(id, TAG, known.map { FacetValue(it) } + genreTags.filter { it.key !in known })
        }
        if (genres.isNotEmpty()) {
            // The site's genres "Фанфик", "Эротический фанфик" are what fan fiction is filed under, its
            // "Альтернативная история" our world with its history changed; a work shown with neither is an original one
            val kind =
                when {
                    genres.any { it.key.startsWith(FANFICTION_GENRE) } -> UniverseFacets.FANFICTION
                    genres.any { it.key == ALTERNATIVE_HISTORY_GENRE } -> UniverseFacets.ALTERNATIVE_HISTORY
                    else -> UniverseFacets.ORIGINAL
                }
            items.setFacet(id, UniverseFacets.KIND, listOf(FacetValue(kind)))
        }
    }

    companion object {
        /** The genre fan fiction is filed under. */
        private const val FANFICTION_GENRE = "fanfiction"
        private const val ALTERNATIVE_HISTORY_GENRE = "sf-history"

        /** A tag of the site by its name: its key the name in lower case. */
        fun siteTag(name: String) = FacetValue(name.lowercase(), name)

        /** "/work/genre/all/any/novel" is the form (Роман); "/work/genre/<slug>" a genre. */
        fun genresIn(links: List<Element>): Pair<FacetValue?, List<FacetValue>> {
            var form: FacetValue? = null
            val genres = ArrayList<FacetValue>()
            links.forEach { a ->
                val href = a.attr("href").substringBefore('?')
                val name = a.text().trim()
                if (href.startsWith("/work/genre/all/any/"))
                    form = FacetValue(href.removePrefix("/work/genre/all/any/"), name)
                else
                    href
                        .removePrefix("/work/genre/")
                        .takeIf { it.isNotEmpty() && '/' !in it }
                        ?.let { genres += FacetValue(it, name) }
            }
            return form to genres
        }
    }
}
