package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.author.authorFacetOf
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.series.ItemSeries
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters

/**
 * THE MANNERS THE MODEL READS OF A WORK, three blocks of its input: its own ([WORK]) — the mean of those of its items
 * that have a text —, its authors' ([AUTHOR]) — every normalized author's kept manner ([MannerGroups]), of several
 * authors the mean of theirs — and its series' ([SERIES]) — its normalized series' kept manner. A work of no text of
 * its own has its authors' and its series' all the same: what is known of how they write. Read for one operation.
 */
class TypeManners
private constructor(
    private val clusters: WorkClusters,
    /** Of every work read, by the item that names it. */
    private val ofWork: Map<ItemKey, FloatArray>,
    private val authorsOf: Map<ItemKey, Set<Long>>,
    private val ofAuthor: Map<String, FloatArray>,
    private val seriesOf: (ItemKey) -> String?,
    private val ofSeries: Map<String, FloatArray>,
) {
    /** The blocks of the manners of the work of the item [key]: none of a work none of whose is known. */
    fun blocks(key: ItemKey): Map<String, FloatArray> {
        val work = clusters.representative(key)
        val out = HashMap<String, FloatArray>()
        ofWork[work]?.let { out[WORK] = it }
        meanManner(authorsOf[work].orEmpty().mapNotNull { ofAuthor[it.toString()] })?.let { out[AUTHOR] = it }
        seriesOf(work)?.let(ofSeries::get)?.let { out[SERIES] = it }
        return out
    }

    /** The [vectors] of the items [keys] with the blocks of the manners of their works. */
    fun with(
        vectors: Map<ItemKey, Map<String, FloatArray>>,
        keys: Collection<ItemKey>,
    ): Map<ItemKey, Map<String, FloatArray>> =
        keys
            .mapNotNull { key ->
                val own = vectors[key].orEmpty() + blocks(key)
                if (own.isEmpty()) null else key to own
            }
            .toMap()

    companion object {
        const val WORK = "manner:work"
        const val AUTHOR = "manner:author"
        const val SERIES = "manner:series"

        /** None of the type's manners: of a type of no texts read, or before the words are fitted. */
        private fun none(clusters: WorkClusters) =
            TypeManners(clusters, emptyMap(), emptyMap(), emptyMap(), { null }, emptyMap())

        /**
         * The manners of the works of the items [keys] alone — of a request, of a portion of every work: their items'
         * manners, their authors' and their series' kept ones, of no other work.
         */
        fun of(
            type: TypeStore,
            clusters: WorkClusters,
            keys: Collection<ItemKey>,
            /** The site's values of the items read already, by source: not read again. */
            read: Map<String, Map<Long, Map<String, List<String>>>> = emptyMap(),
        ): TypeManners {
            val words = type.mannerWords.id() ?: return none(clusters)
            val members = keys.flatMapTo(LinkedHashSet()) { clusters.members(it) }
            val items = HashMap<ItemKey, MutableList<FloatArray>>()
            val authorsOf = HashMap<ItemKey, MutableSet<Long>>()
            members.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
                val s = type.source(source) ?: return@forEach
                s.manner.ofMany(ids, words).forEach { (id, v) ->
                    items.getOrPut(clusters.representative(ItemKey(source, id))) { ArrayList() } += v
                }
                val facet = authorFacetOf(s) ?: return@forEach
                val facets = read[source] ?: s.items.facetsOf(ids)
                val bound = s.authors.bindingsOf(s.id, facets.values.flatMap { it[facet].orEmpty() })
                facets.forEach { (id, ofItem) ->
                    val own = ofItem[facet].orEmpty().mapNotNull(bound::get)
                    if (own.isNotEmpty())
                        authorsOf.getOrPut(clusters.representative(ItemKey(source, id))) { HashSet() } += own
                }
            }
            val series = ItemSeries.of(type, members)
            val seriesOf = { key: ItemKey -> series[key]?.toString() }
            return TypeManners(
                clusters,
                items.mapValues { meanManner(it.value)!! },
                authorsOf,
                type.groupManners.of(MannerGroup.AUTHOR, authorsOf.values.flatten().map { it.toString() }, words),
                seriesOf,
                type.groupManners.of(MannerGroup.SERIES, members.mapNotNull(seriesOf), words),
            )
        }
    }
}
