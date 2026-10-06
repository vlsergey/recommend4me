package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.FacetFilter
import io.github.vlsergey.recommend4me.api.model.FacetValueUse
import io.github.vlsergey.recommend4me.api.model.ItemPage
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.search.Search
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component
import java.time.Instant

enum class ItemView { UNRATED, RATED, ALL }
enum class ItemOrder { SCORE, UPDATED, NUMBER }

/**
 * The list of the works of a content type. H2 joins no two files, so the list is put together here
 * for each request from what each file says, read in one batch per file and only the columns the
 * list needs: the items' keys and update times from the source databases, the values of the
 * filtered facets, the grades from the ratings databases, the predictions from the model database.
 * Nothing of it is kept after the request.
 */
@Component
class ItemList(private val stores: Stores, private val works: Works, private val cards: ItemCards, private val search: Search) {

    /** A facet as the filters of a content type know it: the shared key of its sources' facets, or "<source>.<key>" — as the model does. */
    private fun filterId(store: SourceStore, facet: io.github.vlsergey.recommend4me.source.FacetDef) = FeatureNames.facetId(store.source, facet)

    /** One row of the list before its card is read. */
    private class Row(val key: ItemKey, val updatedAt: Instant, val graded: Boolean, val prediction: Double?, val number: Double?)

    fun page(
        typeId: String,
        view: ItemView,
        order: ItemOrder,
        sortNumber: String?,
        line: String?,
        hidden: Set<String>,
        hiddenWithout: Set<String>,
        sources: Set<String>?,
        offset: Int,
        limit: Int,
    ): ItemPage {
        val type = stores.type(typeId) ?: return ItemPage(0, emptyList())
        val clusters = works.of(type)
        val hiddenMembers = clusters.hidden()
        val found = line?.takeIf { it.isNotBlank() }?.let { search.search(typeId, it) }
        val relevance = found?.keys?.map { clusters.representative(it) }?.distinct()?.withIndex()?.associate { (i, k) -> k to i }
        val hiddenValues = hidden.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to p[1] } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

        val rows = ArrayList<Row>()
        type.sources.filter { sources == null || it.id in sources }.forEach { s ->
            val keys = s.items.keys()
            val versions = keys.associate { it.id to it.version }
            val graded = cards.graded(s.ratings.all(), versions).filterValues { it.grade != null }.keys
            val predictions = type.models.predictions(s.id)
            val numbers = if (order == ItemOrder.NUMBER && sortNumber != null) numberOf(s, sortNumber) else emptyMap()
            val passes = facetFilter(s, hiddenValues, hiddenWithout)
            keys.forEach { k ->
                val key = ItemKey(s.id, k.id)
                if (key in hiddenMembers) return@forEach
                if (relevance != null && key !in relevance) return@forEach
                if (passes != null && !passes(k.id)) return@forEach
                rows += Row(key, k.updatedAt, k.id in graded, predictions[k.id], numbers[k.id])
            }
        }
        // A work graded in any of its sources is graded
        val gradedWorks = rows.filter { it.graded }.map { clusters.work(it.key) }.toSet()
        val viewed = rows.filter { row ->
            val graded = row.graded || (clusters.linked(row.key) && clusters.work(row.key) in gradedWorks)
            when (view) {
                ItemView.UNRATED -> !graded
                ItemView.RATED -> graded
                ItemView.ALL -> true
            }
        }
        val sorted = when {
            relevance != null -> viewed.sortedBy { relevance.getValue(it.key) }
            order == ItemOrder.SCORE -> viewed.sortedWith(compareByDescending<Row> { it.prediction ?: Double.NEGATIVE_INFINITY }.thenByDescending { it.updatedAt })
            order == ItemOrder.NUMBER -> viewed.sortedWith(compareByDescending<Row> { it.number ?: Double.NEGATIVE_INFINITY }.thenByDescending { it.updatedAt })
            else -> viewed.sortedByDescending { it.updatedAt }
        }
        val pageKeys = sorted.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500)).map { it.key }
        val matches = found?.let { search.matches(typeId, it, pageKeys) }.orEmpty()
        return ItemPage(total = sorted.size, items = cards.cards(type, pageKeys, matches, clusters))
    }

    /** The values of the number [numberId] (shared, or "<source>.<key>") of the source's items, as the user corrected them. */
    private fun numberOf(s: SourceStore, numberId: String): Map<String, Double> {
        val def = s.source.schema.numbers.firstOrNull { FeatureNames.numberId(s.source, it) == numberId } ?: return emptyMap()
        val site = s.items.number(def.key)
        val overrides = s.corrections.allFields().mapNotNull { (id, fields) ->
            fields[Corrected.numberField(def.key)]?.toDoubleOrNull()?.let { id to it }
        }.toMap()
        return site + overrides
    }

    /**
     * Whether an item of the source shows by the filters: in every filtered facet, one of its values
     * not [hidden] — or, without any, its facet's "none" line not [hiddenWithout]. A value the site
     * adds later is not hidden: it shows. Null when no facet of the source is filtered.
     */
    private fun facetFilter(s: SourceStore, hidden: Map<String, Set<String>>, hiddenWithout: Set<String>): ((String) -> Boolean)? {
        val filtered = s.source.schema.facets.filter { it.filter && (filterId(s, it) in hidden || filterId(s, it) in hiddenWithout) }
        if (filtered.isEmpty()) return null
        val corrections = s.corrections.allFacets().groupBy { it.itemId }
        val values = filtered.associate { def ->
            val byItem = HashMap<String, List<String>>()
            s.items.forEachFacet(def.key) { id, keys -> byItem[id] = keys }
            corrections.forEach { (id, list) ->
                val own = list.filter { it.facet == def.key }
                if (own.isNotEmpty()) byItem[id] = Corrected.facets(mapOf(def.key to byItem[id].orEmpty()), own)[def.key].orEmpty()
            }
            def to byItem
        }
        return { id ->
            values.all { (def, byItem) ->
                val keys = byItem[id].orEmpty()
                val id2 = filterId(s, def)
                if (keys.isEmpty()) id2 !in hiddenWithout else keys.any { it !in hidden[id2].orEmpty() }
            }
        }
    }

    /** The filter facets of a content type: their values with how many works have each, and the works with none. */
    fun facets(typeId: String): List<FacetFilter> {
        val type = stores.type(typeId) ?: return emptyList()
        class Group(val label: String, val noneLabel: String?) {
            val uses = HashMap<String, Int>()
            val names = HashMap<String, String>()
            var with = 0
        }
        val groups = LinkedHashMap<String, Group>()
        var total = 0
        type.sources.forEach { s ->
            total += s.items.count()
            val corrections = s.corrections.allFacets().groupBy { it.itemId }
            s.source.schema.facets.filter { it.filter }.forEach { def ->
                val group = groups.getOrPut(filterId(s, def)) { Group(def.label, def.noneLabel) }
                val byItem = HashMap<String, List<String>>()
                s.items.forEachFacet(def.key) { id, keys -> byItem[id] = keys }
                corrections.forEach { (id, list) ->
                    val own = list.filter { it.facet == def.key }
                    if (own.isNotEmpty()) byItem[id] = Corrected.facets(mapOf(def.key to byItem[id].orEmpty()), own)[def.key].orEmpty()
                }
                byItem.values.forEach { keys ->
                    if (keys.isNotEmpty()) group.with++
                    keys.forEach { group.uses.merge(it, 1, Int::plus) }
                }
                group.names += s.items.facetNames(def.key, group.uses.keys)
                corrections.values.flatten().filter { it.facet == def.key && it.name != null }.forEach { group.names.putIfAbsent(it.key, it.name!!) }
            }
        }
        return groups.map { (id, g) ->
            FacetFilter(
                id = id,
                label = g.label,
                noneLabel = g.noneLabel,
                itemsWithout = total - g.with,
                propertyValues = g.uses.entries.sortedByDescending { it.value }.map { (key, n) -> FacetValueUse(key, g.names[key] ?: key, n) },
            )
        }
    }
}
