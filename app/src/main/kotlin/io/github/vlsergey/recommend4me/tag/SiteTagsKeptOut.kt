package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.SourceStore
import org.springframework.stereotype.Component

/**
 * THE TAGS OF A SITE FORBIDDEN TO IMPORT, the user's setting of every site with tags ([KEY]) — kept in the user's own
 * settings, never in the code: the site's tags as it writes them, by their keys or their names, any case, a comma or a
 * line apart. A work the site gives one of them is not kept ([KeptOutWorks]): a word on the site's own tags, read
 * before any of them is bound to a normalized tag — the first work of such a tag is kept out too.
 */
@Component
class SiteTagsKeptOut(private val settings: Settings) {

    /** The site's tags forbidden to import, as the user wrote them, in lower case. */
    fun of(sourceId: String): Set<String> =
        settings
            .of(sourceId)
            .get(KEY)
            ?.split(',', ';', '\n')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    /** Whether the value [value] of the site's tags is one of the [forbidden]: by its key or its name. */
    fun forbids(forbidden: Set<String>, value: FacetValue): Boolean =
        value.key.lowercase() in forbidden || value.name?.trim()?.lowercase() in forbidden

    /** The keys of the site's tags of its [facet] the [forbidden] name, by their keys or their names. */
    fun keysOf(store: SourceStore, facet: String, forbidden: Set<String>): Set<String> {
        if (forbidden.isEmpty()) return emptySet()
        val names = store.names(facet)
        return store.items.valueUses(facet).keys.filterTo(HashSet()) { key ->
            key.lowercase() in forbidden || names[key]?.trim()?.lowercase() in forbidden
        }
    }

    companion object {
        /** The setting of a site, "source.<id>.keptOutTags"; its words the standard "setting.keptOutTags". */
        const val KEY = "keptOutTags"
    }
}
