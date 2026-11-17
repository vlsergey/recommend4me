package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.FacetFilter
import io.github.vlsergey.recommend4me.api.model.FilterValue
import io.github.vlsergey.recommend4me.api.model.FilterValues
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.worklist.ListCounts
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import org.springframework.stereotype.Component

/** THE FILTERS OF THE LIST of a content type: its filter facets, the values of each, the steps of a scale. */
@Component
class ListFilters(private val stores: Stores, private val indexes: ListIndexes) {

    /**
     * The filter facets of a content type, without their values: no work read, no model run — shown at once. The values
     * of each are read apart, when it is opened or chosen by ([filterValues]).
     */
    fun facets(typeId: String): List<FacetFilter> {
        val type = stores.type(typeId) ?: return emptyList()
        val filters = LinkedHashMap<String, FacetFilter>()
        type.sources.forEach { s ->
            s.schema.facets
                .filter { it.filter }
                .forEach { def ->
                    filters.getOrPut(filterId(s, def)) {
                        FacetFilter(
                            id = filterId(s, def),
                            labelKey = I18nKeys.facetLabel(s.id, def),
                            noneLabelKey = if (def.none) I18nKeys.facetNoneLabel(s.id, def) else null,
                            ordered = (def.standard?.ordered == true).takeIf { it },
                        )
                    }
                }
        }
        return filters.values.toList()
    }

    /**
     * THE VALUES OF ONE FILTER FACET with how many works have each — every source of the type that has it — and how
     * many have none: counted exactly of the index of the list ([ListCounts]), of the site's values, the model's kept
     * and the user's answers on top; no work read of the databases, no model run (user, 2026-11-17: of the chain over
     * every work at every opening, of the model's shares counted otherwise). Of a scale every value in its order, one
     * no work has with nought; of any other the most works first. Null of no such type or filter.
     */
    fun filterValues(typeId: String, facetId: String): FilterValues? {
        val having =
            stores.type(typeId)?.sources.orEmpty().mapNotNull { s ->
                s.schema.facets.firstOrNull { it.filter && filterId(s, it) == facetId }?.let { s to it }
            }
        if (having.isEmpty()) return null
        val counted = ListCounts.of(indexes.of(typeId), facetId, having.map { it.first.id })
        val scale = having.firstNotNullOfOrNull { (_, def) -> def.standard?.takeIf { it.ordered } }
        val keys = counted.counts.keys
        val named = ValueNames.of(having, keys)
        fun value(key: String) =
            FilterValue(
                key = key,
                name = named.names[key] ?: key,
                nameKey = named.nameKeys[key] ?: scale?.let { I18nKeys.standardValueOf(it, key) },
                items = counted.counts[key] ?: 0,
            )
        val values = scale?.values?.map { value(it.key) } ?: keys.map(::value).sortedByDescending { it.items ?: 0 }
        return FilterValues(propertyValues = values, itemsWithout = counted.without)
    }

    /** The names of values of a filter facet: of the sites' words ([names]), of the interface's ([nameKeys]). */
    private class ValueNames(val names: Map<String, String>, val nameKeys: Map<String, String>) {
        companion object {
            /**
             * The names of the [keys] of the facet as the sources [having] it name them, a made-up value the user's.
             */
            fun of(having: List<Pair<SourceStore, FacetDef>>, keys: Set<String>): ValueNames {
                val names = HashMap<String, String>()
                val nameKeys = HashMap<String, String>()
                having.forEach { (s, def) ->
                    names += s.names(def.key, keys)
                    keys.forEach { key -> s.nameKey(def.key, key)?.let { nameKeys.putIfAbsent(key, it) } }
                }
                having.forEach { (s, def) ->
                    val unnamed = keys.filter { it !in names }
                    if (unnamed.isNotEmpty())
                        s.corrections.facetsWith(def.key, unnamed, named = true).forEach {
                            names.putIfAbsent(it.key, it.name!!)
                        }
                }
                return ValueNames(names, nameKeys)
            }
        }
    }

    /**
     * The values of the scale from [from] to [to] of the filter facet [facet] of the type; null of a facet that is no
     * scale.
     */
    fun rangeOf(typeId: String, facet: String, from: String?, to: String?): Set<String>? =
        stores
            .type(typeId)
            ?.sources
            ?.firstNotNullOfOrNull { s ->
                s.schema.facets.firstOrNull { it.filter && filterId(s, it) == facet }?.standard?.takeIf { it.ordered }
            }
            ?.between(from, to)
            ?.toSet()
}
