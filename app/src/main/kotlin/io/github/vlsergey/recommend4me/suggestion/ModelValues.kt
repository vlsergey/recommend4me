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

    /**
     * How many values of a facet of the layer a work is offered at least that the user has not
     * answered on and the site does not give: besides every value more likely than not, the
     * likeliest others up to this many, likely or not — the user's choice.
     */
    const val OFFERED = 3

    /**
     * The values to offer beside those a work is shown with: of [chances] (value to chance), the
     * likeliest the work is not shown with, the user has not answered on and the site does not
     * give ([fromSite]) — as many as lack to make [OFFERED] with the [shown] values of that kind.
     */
    fun offered(chances: Map<String, Double>, shown: Set<String>, answered: Set<String>, fromSite: Set<String>): List<String> {
        val open = shown.count { it !in answered && it !in fromSite }
        return chances.entries.asSequence()
            .filter { (key, _) -> key !in shown && key !in answered && key !in fromSite }
            .sortedByDescending { it.value }.take((OFFERED - open).coerceAtLeast(0)).map { it.key }.toList()
    }

    /** Scores that are the chances themselves ([SuggestionScores]'s own). */
    val CHANCES = SuggestionScores { error("Of chances alone") }

    /**
     * Of a work's [scores] ([SuggestionScores.scores]) — of the values [allowed], in their order (null:
     * of every value, by its place) — the places of those it is shown besides what it has or was
     * answered on: every value more likely than not and the likeliest others up to [OFFERED] with them,
     * none of those [closed] (values, sorted). The likeliest first among the others, by their scores —
     * of equal scores the earlier value. The chance of a score made ([SuggestionScores.chance]) only
     * of one above the [half][SuggestionScores.half]: no other is likely.
     *
     * IN ONE PASS, THE NUMBER LOOKED AT FIRST: [closed] is asked only of a value that would stay. Of
     * every work a set asked of every value in five passes, a facet of thousands of characters over
     * tens of thousands of works took half a minute.
     */
    fun open(scores: FloatArray, allowed: IntArray?, closed: IntArray, of: SuggestionScores = CHANCES): IntArray {
        val likely = ArrayList<Int>()
        val others = IntArray(OFFERED)
        var kept = 0
        val half = of.half
        fun value(p: Int) = allowed?.get(p) ?: p
        fun shut(p: Int) = closed.binarySearch(value(p)) >= 0
        fun before(p: Int, other: Int) = scores[p] > scores[other] || scores[p] == scores[other] && value(p) < value(other)
        for (p in scores.indices) {
            if (scores[p] > half && of.chance(scores[p]) > LIKELY) {
                if (!shut(p)) likely += p
                continue
            }
            if (kept == OFFERED && !before(p, others[OFFERED - 1]) || shut(p)) continue
            var at = minOf(kept, OFFERED - 1)
            while (at > 0 && before(p, others[at - 1])) {
                others[at] = others[at - 1]
                at--
            }
            others[at] = p
            if (kept < OFFERED) kept++
        }
        return (likely + others.take(minOf(kept, (OFFERED - likely.size).coerceAtLeast(0)))).toIntArray()
    }

    fun of(schema: SourceSchema, site: Map<String, List<String>>, chances: Map<String, Map<String, Chance>>?): Map<String, List<String>> {
        if (chances.isNullOrEmpty()) return site
        val worked = schema.facets.filter { it.infer && it.key in chances }
        if (worked.isEmpty()) return site
        val out = LinkedHashMap(site)
        worked.forEach { def ->
            val own = site[def.key].orEmpty()
            // Of a facet of one value, the likeliest alone — and none beside the site's own; of one every
            // work has ([FacetDef.required]), the likeliest however likely
            val given = given(chances[def.key].orEmpty()).filter { it !in own }.let {
                if (!def.single) it
                else if (own.isNotEmpty()) emptyList()
                else it.take(1).ifEmpty { if (def.required) listOfNotNull(likeliest(chances[def.key].orEmpty())) else emptyList() }
            }
            if (given.isNotEmpty()) out[def.key] = own + given
        }
        return out
    }

    /** The likeliest value the item has not already of the [chances]; none of none. */
    fun likeliest(chances: Map<String, Chance>): String? = chances.values.filter { !it.had }.maxByOrNull { it.chance }?.key

    /** The values the model gives an item of a facet it works out — and every draft of the layer ([Chance.drafted]) — the likeliest first. */
    fun given(chances: Map<String, Chance>): List<String> =
        chances.values.filter { !it.had && (it.drafted || it.chance > LIKELY) }.sortedByDescending { it.chance }.map { it.key }

    /**
     * The values of an item's [chances] a card or a work's facets may show with their names: those it
     * has and those given. Not every value kept — thousands of a universe's characters are, of a work
     * of it — the few offered beside them named apart, when shown.
     */
    fun named(chances: Map<String, Chance>?): List<String> =
        chances.orEmpty().values.filter { it.had || it.drafted || it.chance > LIKELY }.map { it.key }
}
