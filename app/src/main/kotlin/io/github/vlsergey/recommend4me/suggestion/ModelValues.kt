package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.SourceSchema

/**
 * The values of an item's facets as the application reads them before the user's corrections: the
 * site's — never any taken away — and, of a facet the application works out
 * ([FacetDef.infer][io.github.vlsergey.recommend4me.source.FacetDef.infer]), every value the model
 * says the item more likely has than not.
 */
object ModelValues {

    /** More likely than not: the one boundary a chance is read by. */
    const val LIKELY = 0.5

    fun of(schema: SourceSchema, site: Map<String, List<String>>, chances: Map<String, Map<String, Chance>>?): Map<String, List<String>> {
        if (chances.isNullOrEmpty()) return site
        val worked = schema.facets.filter { it.infer && it.key in chances }
        if (worked.isEmpty()) return site
        val out = LinkedHashMap(site)
        worked.forEach { def ->
            val own = site[def.key].orEmpty()
            val given = given(chances[def.key].orEmpty()).filter { it !in own }
            if (given.isNotEmpty()) out[def.key] = own + given
        }
        return out
    }

    /** The values the model gives an item of a facet it works out, the likeliest first. */
    fun given(chances: Map<String, Chance>): List<String> =
        chances.values.filter { !it.had && it.chance > LIKELY }.sortedByDescending { it.chance }.map { it.key }
}
