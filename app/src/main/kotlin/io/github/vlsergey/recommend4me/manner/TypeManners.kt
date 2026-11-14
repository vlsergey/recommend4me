package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.series.TypeSeries
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

    /** Of every work of the [keys] — each the item that names it — that has a block of the manners, its blocks. */
    fun forEachWork(keys: Collection<ItemKey>, action: (key: ItemKey, blocks: Map<String, FloatArray>) -> Unit) =
        keys.forEach { key ->
            blocks(key).takeIf { it.isNotEmpty() }?.let { action(key, it) }
        }

    companion object {
        const val WORK = "manner:work"
        const val AUTHOR = "manner:author"
        const val SERIES = "manner:series"

        /** None of the type's manners: of a type of no texts read, or before the words are fitted. */
        private fun none(clusters: WorkClusters) =
            TypeManners(clusters, emptyMap(), emptyMap(), emptyMap(), { null }, emptyMap())

        /**
         * The manners of every work of the [type], the works and the [series] as the operation read them: every kept
         * manner of an item, an author and a series, every item's authors, one pass a source.
         */
        fun read(type: TypeStore, clusters: WorkClusters, series: TypeSeries): TypeManners {
            val words = type.mannerWords.id() ?: return none(clusters)
            val items = HashMap<ItemKey, MutableList<FloatArray>>()
            val authorsOf = HashMap<ItemKey, MutableSet<Long>>()
            type.sources.forEach { s ->
                s.manner.all(words).forEach { (id, v) ->
                    items.getOrPut(clusters.representative(ItemKey(s.id, id))) { ArrayList() } += v
                }
                val facet = authorFacet(s) ?: return@forEach
                val bound = s.authors.bindingsOf(s.id)
                s.items.forEachFacet(facet) { id, keys ->
                    val own = keys.mapNotNull(bound::get)
                    if (own.isNotEmpty())
                        authorsOf.getOrPut(clusters.representative(ItemKey(s.id, id))) { HashSet() } += own
                }
            }
            return TypeManners(
                clusters,
                items.mapValues { meanManner(it.value)!! },
                authorsOf,
                type.groupManners.all(MannerGroup.AUTHOR, words),
                series::keyOf,
                type.groupManners.all(MannerGroup.SERIES, words),
            )
        }

        /**
         * The manners of the works of the items [keys] alone, for a request: their items' manners, their authors' and
         * their series' kept ones, of no other work.
         */
        fun of(type: TypeStore, clusters: WorkClusters, keys: Collection<ItemKey>): TypeManners {
            val words = type.mannerWords.id() ?: return none(clusters)
            val members = keys.flatMapTo(LinkedHashSet()) { clusters.members(it) }
            val items = HashMap<ItemKey, MutableList<FloatArray>>()
            val authorsOf = HashMap<ItemKey, MutableSet<Long>>()
            members.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
                val s = type.source(source) ?: return@forEach
                s.manner.ofMany(ids, words).forEach { (id, v) ->
                    items.getOrPut(clusters.representative(ItemKey(source, id))) { ArrayList() } += v
                }
                val facet = authorFacet(s) ?: return@forEach
                val bound = s.authors.bindingsOf(s.id)
                s.items.facetsOf(ids).forEach { (id, facets) ->
                    val own = facets[facet].orEmpty().mapNotNull(bound::get)
                    if (own.isNotEmpty())
                        authorsOf.getOrPut(clusters.representative(ItemKey(source, id))) { HashSet() } += own
                }
            }
            val works = type.works.worksOf(members)
            val series = TypeSeries.around(type, works.values)
            val seriesOf = { key: ItemKey -> works[key]?.let(series::ofWork)?.key }
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
