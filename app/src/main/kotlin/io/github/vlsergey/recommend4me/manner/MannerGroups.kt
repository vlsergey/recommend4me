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
 * author merged with another, a work put in a series. What a group is made of is told by the items' keys and the hashes
 * of their texts alone; the vectors are read, by their keys, of the works of the groups changed only, a portion at a
 * time — never every item's at once (1.4 million of them, 3.5 GB, took the whole memory twice on 2026-11-21).
 */
internal object MannerGroups {

    /** An item with a manner, and what its texts were. */
    private class Item(val key: ItemKey, val textHash: String)

    /** A group whose manner is to be made anew: its key, its works, what it is made of. */
    private class Changed(val key: String, val works: List<ItemKey>, val madeOf: String)

    /**
     * The manners of every author and series of the [type] made again where what they are made of changed, of the works
     * as [clusters] and [series] tell them; how many were written or taken away.
     */
    fun refresh(type: TypeStore, clusters: WorkClusters, series: TypeSeries): Int {
        val words = type.mannerWords.id() ?: return 0
        val works = HashMap<ItemKey, MutableList<Item>>()
        val authors = HashMap<String, MutableSet<ItemKey>>()
        type.sources.forEach { s ->
            s.manner.made().forEach { (id, made) ->
                if (made.wordsId != words) return@forEach
                val key = ItemKey(s.id, id)
                works.getOrPut(clusters.representative(key)) { ArrayList() } += Item(key, made.textHash)
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
        val changed = groups.mapNotNull { (key, members) ->
            val own = members.sortedBy { it.toString() }
            val madeOf =
                Vectors.hash(
                    "$words\n" +
                        own.joinToString("\n") { work ->
                            works
                                .getValue(work)
                                .sortedBy { it.key.toString() }
                                .joinToString(",") { "${it.key}=${it.textHash}" }
                        }
                )
            if (stored[key] == madeOf) null else Changed(key, own, madeOf)
        }
        inPortions(changed, works).forEach { portion -> made(type, kind, portion, works, words) }
        val gone = stored.keys - groups.keys
        type.groupManners.remove(kind, gone)
        return changed.size + gone.size
    }

    /** The [changed] groups in portions of about [PORTION] items of their works: a group whole in one. */
    private fun inPortions(changed: List<Changed>, works: Map<ItemKey, List<Item>>): List<List<Changed>> {
        val portions = ArrayList<List<Changed>>()
        var portion = ArrayList<Changed>()
        var items = 0
        changed.forEach { group ->
            portion += group
            items += group.works.sumOf { works.getValue(it).size }
            if (items >= PORTION) {
                portions += portion
                portion = ArrayList()
                items = 0
            }
        }
        if (portion.isNotEmpty()) portions += portion
        return portions
    }

    /** The manners of the groups of a [portion] made of their works' items' vectors, read by their keys, and kept. */
    private fun made(
        type: TypeStore,
        kind: MannerGroup,
        portion: List<Changed>,
        works: Map<ItemKey, List<Item>>,
        words: Long,
    ) {
        val vectors = HashMap<ItemKey, FloatArray>()
        portion
            .flatMap { group -> group.works.flatMap { works.getValue(it) } }
            .map { it.key }
            .distinct()
            .groupBy({ it.source }, { it.id })
            .forEach { (source, ids) ->
                type.source(source)?.manner?.ofMany(ids, words)?.forEach { (id, v) -> vectors[ItemKey(source, id)] = v }
            }
        portion.forEach { group ->
            val own = group.works.mapNotNull { work -> meanManner(works.getValue(work).mapNotNull { vectors[it.key] }) }
            meanManner(own)?.let {
                type.groupManners.save(kind, group.key, words, GroupManner(group.madeOf, own.size, it))
            }
        }
    }

    /** The items of the works whose vectors one portion reads: a bound on the memory, not a meaning. */
    private const val PORTION = 10_000
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
