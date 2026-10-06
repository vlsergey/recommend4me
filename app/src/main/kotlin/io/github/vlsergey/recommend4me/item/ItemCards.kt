package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.ItemFacet
import io.github.vlsergey.recommend4me.api.model.ItemSummary
import io.github.vlsergey.recommend4me.api.model.LinkedItem
import io.github.vlsergey.recommend4me.api.model.Prediction
import io.github.vlsergey.recommend4me.api.model.SearchMatch
import io.github.vlsergey.recommend4me.api.model.TextRange
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.rating.Rating
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component
import java.time.ZoneOffset

/** The grade of the current version of a work, and the latest grade of an earlier one with its version. */
data class Graded(val grade: Int?, val previousGrade: Int?, val previousVersion: String?)

/** The cards of works as the list shows them, read in batch: one query per table per source. */
@Component
class ItemCards(private val stores: Stores, private val works: Works) {

    /** The grades of the items [ids] of a source at their [versions]. */
    fun graded(ratings: List<Rating>, versions: Map<String, String>): Map<String, Graded> =
        ratings.groupBy { it.itemId }.mapNotNull { (id, list) ->
            val version = versions[id] ?: return@mapNotNull null
            val current = list.filter { it.version == version }.maxByOrNull { it.ratedAt }
            val earlier = list.filter { it.version != version }.maxByOrNull { it.ratedAt }
            id to Graded(current?.grade, earlier?.grade, earlier?.version)
        }.toMap()

    /** The facets of an item for the interface: every value with its name and the user's correction. */
    fun facets(
        store: SourceStore,
        site: Map<String, List<String>>,
        corrections: List<FacetCorrection>,
        names: Map<String, Map<String, String>>,
        only: (String) -> Boolean,
    ): List<ItemFacet> {
        val corrected = Corrected.facets(site, corrections)
        return store.source.schema.facets.filter { only(it.key) }.mapNotNull { def ->
            val keys = corrected[def.key].orEmpty()
            val removed = corrections.filter { it.facet == def.key && !it.added && it.key in site[def.key].orEmpty() }.map { it.key }
            val added = corrections.filter { it.facet == def.key && it.added }.associate { it.key to it.name }
            if (keys.isEmpty() && removed.isEmpty()) return@mapNotNull null
            fun name(key: String) = added[key] ?: names[def.key]?.get(key) ?: key
            ItemFacet(
                facet = def.key,
                label = def.label,
                propertyValues = keys.map { k ->
                    FacetValueInfo(k, name(k), if (k in added && k !in site[def.key].orEmpty()) FacetValueInfo.Corrected.ADDED else null)
                } + removed.map { FacetValueInfo(it, name(it), FacetValueInfo.Corrected.REMOVED) },
            )
        }
    }

    /**
     * The cards of [keys] of one content type, in their order; [matches] what a search found in
     * them. Every source's items are read in one go.
     */
    fun cards(
        type: TypeStore,
        keys: List<ItemKey>,
        matches: Map<ItemKey, io.github.vlsergey.recommend4me.search.SearchMatch> = emptyMap(),
        clusters: WorkClusters = works.of(type),
    ): List<ItemSummary> {
        val out = HashMap<ItemKey, ItemSummary>()
        val members = keys.flatMap { clusters.members(it) }.distinct()
        // The heads of every member, to name the linked items
        val heads = members.groupBy { it.source }.flatMap { (source, list) ->
            val store = stores.source(source) ?: return@flatMap emptyList()
            store.items.heads(list.map { it.id }).map { (id, head) ->
                ItemKey(source, id) to (head to Corrected.title(head.title, store.corrections.fieldsOf(id)))
            }
        }.toMap()
        keys.groupBy { it.source }.forEach { (source, list) ->
            val store = stores.source(source) ?: return@forEach
            val ids = list.map { it.id }
            val facets = store.items.facetsOf(ids)
            val corrections = store.corrections.facetsOf(ids)
            val fields = store.corrections.fieldsOf(ids)
            val onCard = store.source.schema.facets.filter { it.onCard }.map { it.key }.toSet()
            val names = onCard.associateWith { facet -> store.items.facetNames(facet, ids.flatMap { facets[it]?.get(facet).orEmpty() }) }
            val numbers = store.items.numbersOf(ids)
            val pictures = store.pictures.countsOf(ids)
            val ratings = store.ratings.all().filter { it.itemId in ids.toSet() }
            val versions = ids.mapNotNull { id -> heads[ItemKey(source, id)]?.first?.let { id to it.version } }.toMap()
            val graded = graded(ratings, versions)
            val predictions = type.models.predictions(source)
            val signals = store.signals.all()
            ids.forEach { id ->
                val key = ItemKey(source, id)
                val (head, title) = heads[key] ?: return@forEach
                val g = graded[id]
                out[key] = ItemSummary(
                    source = source,
                    item = id,
                    title = title,
                    version = head.version,
                    url = head.url,
                    updatedAt = head.updatedAt.atOffset(ZoneOffset.UTC),
                    facets = facets(store, facets[id].orEmpty(), corrections[id].orEmpty(), names) { it in onCard },
                    numbers = Corrected.numbers(numbers[id].orEmpty(), fields[id].orEmpty()),
                    hasCover = pictures[id]?.second ?: false,
                    pictureCount = pictures[id]?.first ?: 0,
                    linked = clusters.members(key).drop(1).mapNotNull { m ->
                        heads[m]?.let { (h, t) -> LinkedItem(m.source, m.id, t, h.url) }
                    },
                    grade = g?.grade ?: clusters.members(key).drop(1).firstNotNullOfOrNull { m -> memberGrade(m, heads) },
                    previousGrade = g?.previousGrade,
                    previousGradeVersion = g?.previousVersion,
                    prediction = predictions[id]?.let { Prediction(it) },
                    searchMatch = matches[key]?.let { m ->
                        SearchMatch(m.field, m.byMeaning, m.text, m.highlights.map { (s, e) -> TextRange(s, e) })
                    },
                    signals = signals[id]?.takeIf { it.isNotEmpty() },
                )
            }
        }
        return keys.mapNotNull { out[it] }
    }

    /** The grade of the current version of a linked item, read alone: links are few. */
    private fun memberGrade(key: ItemKey, heads: Map<ItemKey, Pair<io.github.vlsergey.recommend4me.source.StoredItem, String>>): Int? {
        val store = stores.source(key.source) ?: return null
        val version = heads[key]?.first?.version ?: return null
        return store.ratings.ofItem(key.id).filter { it.version == version }.maxByOrNull { it.ratedAt }?.grade
    }
}
