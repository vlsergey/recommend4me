package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.FacetFilter
import io.github.vlsergey.recommend4me.api.model.FilterValue
import io.github.vlsergey.recommend4me.api.model.FilterValues
import io.github.vlsergey.recommend4me.contenttype.StandardFacet
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import org.springframework.stereotype.Component

/** THE FILTERS OF THE LIST of a content type: its filter facets, the values of each, the steps of a scale. */
@Component
class ListFilters(
    private val stores: Stores,
    private val list: ItemList,
    private val suggestions: FacetSuggestions,
) {

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
     * THE VALUES OF ONE FILTER FACET with how many works have each — every source of the type that has it, summed. Of a
     * facet the site gives, its works' own values with the user's answers, counted. Of a facet a model works out (the
     * universe, the languages), NO WORK READ AND NO MODEL RUN: the values of the model kept with how many works it
     * expects of each — its share of the works it was fitted on ([FacetSuggestions.weights]) — and those the site or
     * the user give that it does not know, counted; the works without any value not told. (Run over every work at every
     * opening of the filters, the chain of the models took 150-195 s of the 75 621 works of fanfics.me.) Null of no
     * such type or filter.
     */
    fun filterValues(typeId: String, facetId: String): FilterValues? {
        val count = ValueCount()
        stores.type(typeId)?.sources?.forEach { s ->
            s.schema.facets.firstOrNull { it.filter && filterId(s, it) == facetId }?.let { def -> count.add(s, def) }
        }
        return count.values()
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

    /** The values of one filter facet counted over every source that has it ([filterValues]). */
    private inner class ValueCount {
        private val counted = HashMap<String, Int>()
        private val expected = HashMap<String, Double>()
        private val names = HashMap<String, String>()
        private val nameKeys = HashMap<String, String>()
        private var scale: StandardFacet? = null
        private var with = 0
        private var total = 0
        private var withKnown = true
        private var found = false

        /** The values of the facet [def] of the source [s] counted in, and their names. */
        fun add(s: SourceStore, def: FacetDef) {
            found = true
            def.standard?.takeIf { it.ordered }?.let { scale = it }
            val corrections = s.corrections.allFacets().filter { it.facet == def.key }
            val weights = if (def.infer) suggestions.weights(s, def.key) else null
            if (!def.infer) countGiven(s, def, corrections) else countExpected(s, def, corrections, weights)
            val keys = counted.keys + expected.keys
            names += s.names(def.key, keys)
            keys.forEach { key -> s.nameKey(def.key, key)?.let { nameKeys.putIfAbsent(key, it) } }
            corrections.filter { it.name != null }.forEach { names.putIfAbsent(it.key, it.name!!) }
        }

        /** Of a facet the site gives: its works' own values with the user's answers, counted. */
        private fun countGiven(s: SourceStore, def: FacetDef, corrections: List<FacetCorrection>) {
            total += s.items.count()
            list.valuesOf(s, def, corrections.groupBy { it.itemId }).values.forEach { keys ->
                if (keys.isNotEmpty()) with++
                keys.forEach { counted.merge(it, 1, Int::plus) }
            }
        }

        /**
         * Of a facet a model works out: the model's share of a value over the works it was fitted on ([weights]), as so
         * many of this source's works; what the site or the user gives that the model does not know, counted as given.
         */
        private fun countExpected(
            s: SourceStore,
            def: FacetDef,
            corrections: List<FacetCorrection>,
            weights: Map<String, Double>?,
        ) {
            withKnown = false
            val works = s.items.count()
            weights?.forEach { (key, share) -> expected.merge(key, share * works, Double::plus) }
            s.items.valueUses(def.key).forEach { (key, n) ->
                if (weights?.containsKey(key) != true) counted.merge(key, n, Int::plus)
            }
            corrections
                .filter { it.added && weights?.containsKey(it.key) != true }
                .forEach { counted.merge(it.key, 1, Int::plus) }
        }

        /**
         * The values counted: of a scale every value in its order, one no work has with nought; of any other the most
         * works first. Null when no source has the facet.
         */
        fun values(): FilterValues? {
            if (!found) return null
            val values =
                scale?.values?.map { value(it.key) }
                    ?: (counted.keys + expected.keys).map(::value).sortedByDescending { it.items ?: 0 }
            return FilterValues(propertyValues = values, itemsWithout = if (withKnown) total - with else null)
        }

        /** One value with its name and how many works have it — counted, expected, or both summed. */
        private fun value(key: String): FilterValue {
            val estimate = expected[key]
            val count = counted[key]
            return FilterValue(
                key = key,
                name = names[key] ?: key,
                nameKey = nameKeys[key] ?: scale?.let { I18nKeys.standardValueOf(it, key) },
                items = estimate?.let { Math.round(it + (count ?: 0)).toInt() } ?: count,
                estimated = (estimate != null).takeIf { it },
            )
        }
    }
}
