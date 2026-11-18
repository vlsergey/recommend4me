package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.interest.Interest
import io.github.vlsergey.recommend4me.interest.InterestMark
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemKeys
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.model.Predicted
import io.github.vlsergey.recommend4me.rating.Rating
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters
import java.time.Instant

/**
 * WHAT CHANGES OF A ROW OF THE LIST WITHOUT ITS ITEM CHANGING — of its work: whether it is graded, its prediction, its
 * chance of interest and the two multiplied, the order of the list by the formula ([Interest.expected]); null of none.
 */
class RowValues(val graded: Boolean, val prediction: Double?, val interest: Double?, val expected: Double?) {
    companion object {
        /** The values of an item of the [ratings] given at its [version], [predicted] so, [marked] of interest so. */
        fun of(version: String, ratings: List<Rating>?, predicted: Predicted?, marked: InterestMark?): RowValues {
            val chance = Interest.chance(marked?.interesting, predicted?.interest)
            return RowValues(
                graded = ratings.orEmpty().any { it.version == version },
                prediction = predicted?.score,
                interest = chance,
                expected = predicted?.let { Interest.expected(it.score, chance) },
            )
        }
    }
}

/**
 * ONE ITEM AS THE LIST OF WORKS READS IT: the [work] it is of — the item that names it, a work of several items one row
 * of the list — and its [rank] among the items of the work, the one the work is shown with ([WorkClusters.shown]) 0;
 * its dates, its [values], the values of every filter facet of its source as the user sees them ([facets], by the
 * facet's filter id — the site's, the model's kept, the user's word on top) and every number with the user's word on
 * top ([numbers], by the number's id).
 */
class ListRow(
    val key: ItemKey,
    val work: ItemKey,
    val rank: Int,
    val linked: Boolean,
    val updatedAt: Instant,
    val firstSeenAt: Instant,
    val values: RowValues,
    val facets: Map<String, List<String>>,
    val numbers: Map<String, Double>,
)

/**
 * THE ROWS OF THE LIST READ OF THE DATABASES, of one source: of every item in one pass over each table, or of the items
 * named by their ids. What the index of the list is made of ([ListIndex]); nothing of it kept after the reading.
 */
internal object ListRows {

    /** The rows of the items [ids] of the source [s] — every item when null —, of the works [clusters] tells. */
    fun read(type: TypeStore, s: SourceStore, ids: Collection<String>?, clusters: WorkClusters): List<ListRow> {
        val heads =
            if (ids == null) s.items.keys()
            else s.items.heads(ids).values.map { ItemKeys(it.id, it.version, it.updatedAt, it.firstSeenAt) }
        if (heads.isEmpty()) return emptyList()
        val ratings = s.ratings.all().groupBy { it.itemId }
        val predicted = if (ids == null) type.predictions.predicted(s.id) else type.predictions.predicted(s.id, ids)
        val marks = s.interest.all()
        val facets = facetsOf(s, ids)
        val numbers = numbersOf(s, ids)
        return heads.map { h ->
            val key = ItemKey(s.id, h.id)
            val members = clusters.fullestFirst(key)
            ListRow(
                key = key,
                work = clusters.representative(key),
                rank = members.indexOf(key).coerceAtLeast(0),
                linked = clusters.linked(key),
                updatedAt = h.updatedAt,
                firstSeenAt = h.firstSeenAt,
                values = RowValues.of(h.version, ratings[h.id], predicted[h.id], marks[h.id]),
                facets = facets.mapValues { (_, byItem) -> byItem[h.id].orEmpty() },
                numbers = numbers[h.id].orEmpty(),
            )
        }
    }

    /** The values of the items [ids] of the source that change without the item: the grade, prediction and interest. */
    fun valuesOf(type: TypeStore, s: SourceStore, ids: Collection<String>): Map<String, RowValues> {
        // Of many — the predictions of a training — every item's version read in one pass, not a thousand at a time
        val versions =
            if (ids.size > MANY)
                ids.toHashSet()
                    .let { asked -> s.items.keys().filter { it.id in asked } }
                    .associate { it.id to it.version }
            else s.items.heads(ids).mapValues { it.value.version }
        val ratings = s.ratings.ofItems(ids).groupBy { it.itemId }
        val predicted = type.predictions.predicted(s.id, ids)
        val marks = s.interest.ofItems(ids)
        return versions.mapValues { (id, version) -> RowValues.of(version, ratings[id], predicted[id], marks[id]) }
    }

    /**
     * Of every filter facet of the source, by its filter id, every item's values as the user sees them: the site's, the
     * drafts of the layer, the model's kept of a facet it works out
     * ([io.github.vlsergey.recommend4me.suggestion.ModelValueRepository]), the user's corrections on top — what the
     * index keeps of them, read of the databases: of the items [ids], what a screen shows of a few.
     */
    fun facetsOf(s: SourceStore, ids: Collection<String>?): Map<String, Map<String, List<String>>> {
        val defs = s.schema.facets.filter { it.filter }
        if (defs.isEmpty()) return emptyMap()
        val site: Map<String, Map<String, List<String>>> =
            if (ids == null) siteOfEvery(s, defs.map { it.key }.toSet()) else s.items.facetsOf(ids)
        val corrections =
            if (ids == null) s.corrections.allFacets().groupBy { it.itemId } else s.corrections.facetsOf(ids)
        // The drafts of the layer — the site's words bound to the layer's values, a site's tag to a tag — given as the
        // model's are, before any model is fitted
        val drafts = HashMap<String, HashMap<String, MutableList<String>>>()
        (if (ids == null) s.drafts.all() else s.drafts.ofItems(ids)).forEach { (id, own) ->
            own.forEach { d -> drafts.getOrPut(d.facet) { HashMap() }.getOrPut(id) { ArrayList() } += d.key }
        }
        // The same keys of a million works held once
        val keys = HashMap<String, String>()
        return defs.associate { def ->
            val model = if (def.infer) s.modelValues.of(def.key, ids) else emptyMap()
            val drafted = drafts[def.key].orEmpty()
            val byItem = HashMap<String, List<String>>()
            (site.keys + model.keys + drafted.keys + corrections.keys).forEach { id ->
                val given = site[id]?.get(def.key).orEmpty() + drafted[id].orEmpty() + model[id].orEmpty()
                val shown = shown(def, given, corrections[id])
                if (shown.isNotEmpty()) byItem[id] = shown.map { keys.getOrPut(it) { it } }
            }
            FeatureNames.facetId(s.source, def) to byItem
        }
    }

    /**
     * The values of the facet [def] of an item, the site's and the model's [given], with the user's word [own] on top.
     */
    private fun shown(def: FacetDef, given: List<String>, own: List<FacetCorrection>?): List<String> {
        val mine = own.orEmpty().filter { it.facet == def.key }
        if (mine.isEmpty()) return given.distinct()
        val single = if (def.single) setOf(def.key) else emptySet()
        return Corrected.facets(mapOf(def.key to given.distinct()), mine, single)[def.key].orEmpty()
    }

    /** The site's values of the facets [keys] of every item of the source, one pass of each facet. */
    private fun siteOfEvery(s: SourceStore, keys: Set<String>): Map<String, Map<String, List<String>>> {
        val out = HashMap<String, HashMap<String, List<String>>>()
        keys.forEach { facet ->
            s.items.forEachFacet(facet) { id, values -> out.getOrPut(id) { HashMap() }[facet] = values }
        }
        return out
    }

    /** Every number of the items [ids] — of every item when null — by the number's id, the user's word on top. */
    private fun numbersOf(s: SourceStore, ids: Collection<String>?): Map<String, Map<String, Double>> {
        val defs = s.schema.numbers.associateBy { it.key }
        if (defs.isEmpty()) return emptyMap()
        val out = HashMap<String, HashMap<String, Double>>()
        fun put(id: String, key: String, value: Double) {
            val def = defs[key] ?: return
            out.getOrPut(id) { HashMap() }[FeatureNames.numberId(s.source, def)] = value
        }
        if (ids == null) s.items.forEachNumber(::put)
        else s.items.numbersOf(ids).forEach { (id, numbers) -> numbers.forEach { (key, value) -> put(id, key, value) } }
        val wanted = ids?.toHashSet()
        s.corrections.allFields().forEach { (id, fields) ->
            if (wanted != null && id !in wanted) return@forEach
            defs.keys.forEach { key -> fields[Corrected.numberField(key)]?.toDoubleOrNull()?.let { put(id, key, it) } }
        }
        return out
    }

    /** Of more items than so many, every item read and picked from. */
    private const val MANY = 10_000
}
