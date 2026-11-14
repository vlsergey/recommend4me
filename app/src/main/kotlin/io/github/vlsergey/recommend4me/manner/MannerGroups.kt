package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.series.TypeSeries
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.work.WorkClusters

/**
 * THE MANNERS OF THE NORMALIZED AUTHORS AND OF THE NORMALIZED SERIES of a content type, kept ([GroupMannerRepository]):
 * of every author the mean of the manners of all their works that have a text, of every series the mean of those of its
 * works — a work's own the mean of its items'. Made of the kept manners of the items, every work's authors and its
 * series as they are now, and written where what one is made of changed: a text read again, a work bound to another, an
 * author merged with another, a work put in a series.
 */
internal object MannerGroups {

    /** An item's manner, with what its texts were. */
    private class Item(val key: ItemKey, val textHash: String, val vector: FloatArray)

    /**
     * The manners of every author and series of the [type] made again where what they are made of changed, of the works
     * as [clusters] and [series] tell them; how many were written or taken away.
     */
    fun refresh(type: TypeStore, clusters: WorkClusters, series: TypeSeries): Int {
        val words = type.mannerWords.id() ?: return 0
        val works = HashMap<ItemKey, MutableList<Item>>()
        val authors = HashMap<String, MutableSet<ItemKey>>()
        type.sources.forEach { s ->
            val made = s.manner.made()
            s.manner.all(words).forEach { (id, v) ->
                val key = ItemKey(s.id, id)
                works.getOrPut(clusters.representative(key)) { ArrayList() } +=
                    Item(key, made[id]?.textHash.orEmpty(), v)
            }
            authorFacet(s)?.let { facet ->
                val bound = s.authors.bindingsOf(s.id)
                s.items.forEachFacet(facet) { id, keys ->
                    val work = clusters.representative(ItemKey(s.id, id))
                    keys.mapNotNull(bound::get).forEach { authors.getOrPut(it.toString()) { HashSet() } += work }
                }
            }
        }
        // Of an author, the works of theirs that have a manner
        val byAuthor = authors.mapValues { (_, own) -> own.filter { it in works } }.filterValues { it.isNotEmpty() }
        val bySeries =
            works.keys
                .mapNotNull { work -> series.keyOf(work)?.let { it to work } }
                .groupBy({ it.first }, { it.second })
        return written(type, MannerGroup.AUTHOR, byAuthor, works, words) +
            written(type, MannerGroup.SERIES, bySeries, works, words)
    }

    /**
     * The manners of the [groups] of the [kind] — each its works — written where what they are made of changed, those
     * of groups no more taken away; how many.
     */
    private fun written(
        type: TypeStore,
        kind: MannerGroup,
        groups: Map<String, List<ItemKey>>,
        works: Map<ItemKey, List<Item>>,
        words: Long,
    ): Int {
        val stored = type.groupManners.made(kind)
        var count = 0
        groups.forEach { (key, members) ->
            val own = members.sortedBy { it.toString() }
            val madeOf =
                Vectors.hash(
                    "$words\n" +
                        own.joinToString("\n") { work ->
                            works
                                .getValue(work)
                                .sortedBy { it.key.toString() }
                                .joinToString(",") {
                                    "${it.key}=${it.textHash}"
                                }
                        }
                )
            if (stored[key] == madeOf) return@forEach
            val vector = meanManner(own.map { work -> meanManner(works.getValue(work).map { it.vector })!! })!!
            type.groupManners.save(kind, key, words, GroupManner(madeOf, own.size, vector))
            count++
        }
        val gone = stored.keys - groups.keys
        type.groupManners.remove(kind, gone)
        return count + gone.size
    }
}

/** The facet of the authors of the source; none of a source that names none. */
internal fun authorFacet(s: SourceStore): String? = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.key

/** The mean of manners — vectors of one length —; none of none. */
internal fun meanManner(vectors: List<FloatArray>): FloatArray? =
    vectors.firstOrNull()?.let { first ->
        if (vectors.size == 1) first
        else
            FloatArray(first.size).also { out ->
                vectors.forEach { v -> for (k in out.indices) out[k] += v[k] }
                for (k in out.indices) out[k] /= vectors.size
            }
    }
