package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.series.TypeSeries
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters

/**
 * THE MANNERS THE MODEL READS OF A WORK, three blocks of its input: its own ([WORK]) — the mean of those of its items
 * that have a text —, its authors' ([AUTHOR]) — of every normalized author of it the mean of the manners of all their
 * works, of several authors the mean of theirs — and its series' ([SERIES]) — the mean of the manners of the works of
 * its normalized series. A work of no text of its own has its authors' and its series' all the same: what is known of
 * how they write. Made of the kept manners of the items ([MannerRepository]) when read, for one operation: an author's
 * and a series' are never kept, so they are of the works as they are now, a work read or an author bound changing them
 * at once.
 */
class TypeManners
private constructor(
    private val clusters: WorkClusters,
    /** Of every work read, by the item that names it. */
    private val ofWork: Map<ItemKey, FloatArray>,
    private val authorsOf: Map<ItemKey, Set<Long>>,
    private val ofAuthor: Map<Long, FloatArray>,
    private val seriesOf: Map<ItemKey, String>,
    private val ofSeries: Map<String, FloatArray>,
) {
    /** The blocks of the manners of the work of the item [key]: none of a work none of whose is known. */
    fun blocks(key: ItemKey): Map<String, FloatArray> {
        val work = clusters.representative(key)
        val out = HashMap<String, FloatArray>()
        ofWork[work]?.let { out[WORK] = it }
        mean(authorsOf[work].orEmpty().mapNotNull(ofAuthor::get))?.let { out[AUTHOR] = it }
        seriesOf[work]?.let(ofSeries::get)?.let { out[SERIES] = it }
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

    /** Every work read that has a block of the manners, by the item that names it, with its blocks. */
    fun forEachWork(action: (key: ItemKey, blocks: Map<String, FloatArray>) -> Unit) =
        (ofWork.keys + authorsOf.keys + seriesOf.keys).forEach { key ->
            blocks(key).takeIf { it.isNotEmpty() }?.let { action(key, it) }
        }

    companion object {
        const val WORK = "manner:work"
        const val AUTHOR = "manner:author"
        const val SERIES = "manner:series"

        /** None of the type's manners: of a type of no texts read, or before the words are fitted. */
        private fun none(clusters: WorkClusters) =
            TypeManners(clusters, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

        /**
         * The manners of every work of the [type], the works and the [series] as the operation read them: every kept
         * manner, every item's authors, one pass a source.
         */
        fun read(type: TypeStore, clusters: WorkClusters, series: TypeSeries): TypeManners {
            val words = type.mannerWords.id() ?: return none(clusters)
            val made = Made(clusters)
            type.sources.forEach { s ->
                s.manner.all(words).forEach { (id, v) -> made.vector(ItemKey(s.id, id), v) }
                val facet = authorFacet(s) ?: return@forEach
                val bound = s.authors.bindingsOf(s.id)
                s.items.forEachFacet(facet) { id, keys -> made.authors(ItemKey(s.id, id), keys.mapNotNull(bound::get)) }
            }
            made.series { series.keyOf(it) }
            return made.manners()
        }

        /**
         * The manners of the works of the items [keys] alone, for a request: the manners of every work of their authors
         * and of their series read, of no other.
         */
        fun of(type: TypeStore, clusters: WorkClusters, keys: Collection<ItemKey>): TypeManners {
            val words = type.mannerWords.id() ?: return none(clusters)
            val made = Made(clusters)
            val members = keys.flatMapTo(LinkedHashSet()) { clusters.members(it) }
            // The authors of the works asked for, and every item of theirs, of every site
            val authors = HashSet<Long>()
            // Every site's authors bound to the normalized ones, read once
            val boundOf = type.sources.associate { s -> s.id to s.authors.bindingsOf(s.id) }
            type.sources.forEach { s ->
                val facet = authorFacet(s) ?: return@forEach
                val bound = boundOf.getValue(s.id)
                val ids = members.filter { it.source == s.id }.map { it.id }
                s.items.facetsOf(ids).forEach { (id, facets) ->
                    val own = facets[facet].orEmpty().mapNotNull(bound::get)
                    made.authors(ItemKey(s.id, id), own)
                    authors += own
                }
            }
            val fellows = HashSet<ItemKey>(members)
            type.sources.forEach { s ->
                val facet = authorFacet(s) ?: return@forEach
                val siteKeys = boundOf.getValue(s.id).filterValues { it in authors }
                s.items.itemsWith(facet, siteKeys.keys).forEach { (siteKey, items) ->
                    items.forEach { id ->
                        fellows += ItemKey(s.id, id)
                        made.authors(ItemKey(s.id, id), listOf(siteKeys.getValue(siteKey)))
                    }
                }
            }
            // The series of the works asked for, and every item of their works
            val works = type.works.worksOf(members)
            val series = TypeSeries.around(type, works.values)
            val seriesOf = HashMap<ItemKey, String>()
            works.values
                .mapNotNull(series::ofWork)
                .distinctBy { it.key }
                .forEach { one ->
                    type.works.itemsOf(one.works).values.flatten().forEach { item ->
                        fellows += item
                        seriesOf[item] = one.key
                    }
                }
            made.series(seriesOf::get)
            fellows.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
                type.source(source)?.manner?.ofMany(ids, words)?.forEach { (id, v) ->
                    made.vector(ItemKey(source, id), v)
                }
            }
            return made.manners()
        }

        private fun authorFacet(s: SourceStore): String? =
            s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.key
    }

    /** The manners of the works put together of what was read of their items. */
    private class Made(private val clusters: WorkClusters) {
        private val items = HashMap<ItemKey, MutableList<FloatArray>>()
        private val authorsOf = HashMap<ItemKey, MutableSet<Long>>()
        private var seriesOf: (ItemKey) -> String? = { null }

        /** The manner of the item [key]: of its work. */
        fun vector(key: ItemKey, v: FloatArray) {
            items.getOrPut(clusters.representative(key)) { ArrayList() } += v
        }

        /** The normalized [authors] of the item [key]: of its work. */
        fun authors(key: ItemKey, authors: Collection<Long>) {
            if (authors.isNotEmpty()) authorsOf.getOrPut(clusters.representative(key)) { HashSet() } += authors
        }

        /** The key of the series of an item's work. */
        fun series(of: (ItemKey) -> String?) {
            seriesOf = of
        }

        fun manners(): TypeManners {
            val ofWork = items.mapNotNull { (work, own) -> mean(own)?.let { work to it } }.toMap()
            val byAuthor = HashMap<Long, MutableList<FloatArray>>()
            val bySeries = HashMap<String, MutableList<FloatArray>>()
            val series = HashMap<ItemKey, String>()
            (ofWork.keys + authorsOf.keys).forEach { work -> seriesOf(work)?.let { series[work] = it } }
            ofWork.forEach { (work, v) ->
                authorsOf[work].orEmpty().forEach { byAuthor.getOrPut(it) { ArrayList() } += v }
                series[work]?.let { bySeries.getOrPut(it) { ArrayList() } += v }
            }
            return TypeManners(
                clusters,
                ofWork,
                authorsOf,
                byAuthor.mapValues { mean(it.value)!! },
                series,
                bySeries.mapValues { mean(it.value)!! },
            )
        }
    }
}

/** The mean of [vectors] of one length; none of none. */
private fun mean(vectors: List<FloatArray>): FloatArray? =
    vectors.firstOrNull()?.let { first ->
        if (vectors.size == 1) first
        else
            FloatArray(first.size).also { out ->
                vectors.forEach { v -> for (k in out.indices) out[k] += v[k] }
                for (k in out.indices) out[k] /= vectors.size
            }
    }
