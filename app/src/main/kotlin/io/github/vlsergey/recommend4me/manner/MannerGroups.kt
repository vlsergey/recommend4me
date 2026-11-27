package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.author.authorFacetOf
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.series.ItemSeries
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.work.WorkClusters

/**
 * THE MANNERS OF THE NORMALIZED AUTHORS AND OF THE NORMALIZED SERIES of the works a turn of the stage takes
 * ([Manners]), kept ([GroupMannerRepository]): of every author the mean of the manners of all their works that have a
 * text, of every series the mean of those of its works — a work's own the mean of its items'. The groups of a work
 * taken are those it is of now and those it was of as their manners were last made ([MannerMemberRepository]): each
 * made of what it holds now — an author's items found by the authors of the sites bound to them, a series' by the works
 * kept of it ([io.github.vlsergey.recommend4me.series.NormalizedSeriesRepository]) —, written where what it is made of
 * changed (the words, its works, the texts of their items), taken away of no work with a manner. Everything read by
 * keys: no group, work or item outside those taken and their groups; the vectors of the groups changed alone, a portion
 * at a time.
 */
internal object MannerGroups {

    /** Of a turn, how many groups' manners were written or taken away, and every item whose groups' manners did. */
    class Made(val groups: Int, val dated: Set<ItemKey>)

    /** A group whose manner is to be made anew: its works, by the item naming each, and what it is made of. */
    private class Changed(val group: GroupRef, val works: List<ItemKey>, val madeOf: String)

    /** The groups of the works of the [taken] items of the [type] made anew by the words [words] where they changed. */
    fun refresh(type: TypeStore, words: Long, taken: Collection<ItemKey>): Made {
        val clusters = WorkClusters.of(type.links.groupsOf(taken))
        val items = taken.flatMapTo(LinkedHashSet()) { clusters.members(it) }
        val before = type.mannerMembers.groupsOf(items)
        val now = authorsOf(type, items)
        ItemSeries.of(type, items).forEach { (item, series) ->
            now.getOrPut(item) { HashSet() } += GroupRef(MannerGroup.SERIES, "$series")
        }
        val touched = items.flatMapTo(HashSet()) { before[it].orEmpty() + now[it].orEmpty() }
        if (touched.isEmpty()) return Made(0, emptySet())
        val members = membersOf(type, touched)
        return made(type, words, members)
    }

    /** Of the [items], the normalized authors each names, as groups. */
    private fun authorsOf(type: TypeStore, items: Collection<ItemKey>): HashMap<ItemKey, MutableSet<GroupRef>> {
        val out = HashMap<ItemKey, MutableSet<GroupRef>>()
        items.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            val s = type.source(source) ?: return@forEach
            val facet = authorFacetOf(s) ?: return@forEach
            val named = s.items.facetsOf(ids).mapValues { it.value[facet].orEmpty() }
            val bound = s.authors.bindingsOf(s.id, named.values.flatten())
            named.forEach { (id, authors) ->
                val own = authors.mapNotNull(bound::get)
                if (own.isNotEmpty())
                    out.getOrPut(ItemKey(source, id)) { HashSet() } += own.map { GroupRef(MannerGroup.AUTHOR, "$it") }
            }
        }
        return out
    }

    /**
     * The items every one of the [groups] holds now: of an author those naming the authors of the sites bound to them,
     * of a series those of its works kept; none of a group that is no more.
     */
    private fun membersOf(type: TypeStore, groups: Set<GroupRef>): Map<GroupRef, List<ItemKey>> {
        val out = groups.associateWithTo(HashMap()) { ArrayList<ItemKey>() }
        val authors = groups.filter { it.kind == MannerGroup.AUTHOR }.mapNotNull { it.key.toLongOrNull() }
        type.authors.sitesOf(authors).forEach { (author, sites) ->
            sites.groupBy({ it.source }, { it.author }).forEach { (source, named) ->
                val s = type.source(source) ?: return@forEach
                val facet = authorFacetOf(s) ?: return@forEach
                s.items.itemsWith(facet, named).values.flatten().forEach {
                    out.getValue(GroupRef(MannerGroup.AUTHOR, "$author")) += ItemKey(source, it)
                }
            }
        }
        val ofSeries = groups.filter { it.kind == MannerGroup.SERIES }.associateBy { it.key.toLongOrNull() }
        val worksOf = type.series.worksOf(ofSeries.keys.filterNotNull())
        val itemsOf = type.works.itemsOf(worksOf.values.flatten())
        worksOf.forEach { (series, works) ->
            ofSeries[series]?.let { group -> out.getValue(group) += works.flatMap { itemsOf[it].orEmpty() } }
        }
        return out
    }

    /**
     * The manners of the groups of their [members] written where what they are made of changed, taken away of those of
     * no work with a manner, and the items every one was made of kept.
     */
    private fun made(type: TypeStore, words: Long, members: Map<GroupRef, List<ItemKey>>): Made {
        val clusters = WorkClusters.of(type.links.groupsOf(members.values.flatten().distinct()))
        val worksOf = members.mapValues { (_, items) -> items.map(clusters::representative).distinct() }
        val itemsOfWorks = worksOf.values.flatten().distinct().flatMap(clusters::members)
        val made =
            itemsOfWorks
                .groupBy({ it.source }, { it.id })
                .flatMap { (source, ids) ->
                    type.source(source)?.manner?.madeOf(ids).orEmpty().mapNotNull { (id, m) ->
                        if (m.wordsId == words) ItemKey(source, id) to m.textHash else null
                    }
                }
                .toMap()
        val stored =
            members.keys
                .groupBy { it.kind }
                .flatMap { (kind, groups) ->
                    type.groupManners.madeOf(kind, groups.map { it.key }).map { (key, hash) ->
                        GroupRef(kind, key) to hash
                    }
                }
                .toMap()
        val changed = ArrayList<Changed>()
        val gone = ArrayList<GroupRef>()
        worksOf.forEach { (group, works) ->
            val own = works.filter { work -> clusters.members(work).any { it in made } }.sortedBy { it.toString() }
            if (own.isEmpty()) {
                if (group in stored) gone += group
                return@forEach
            }
            val madeOf =
                Vectors.hash(
                    "$words\n" +
                        own.joinToString("\n") { work ->
                            clusters
                                .members(work)
                                .filter { it in made }
                                .sortedBy { it.toString() }
                                .joinToString(",") { "$it=${made.getValue(it)}" }
                        }
                )
            if (stored[group] != madeOf) changed += Changed(group, own, madeOf)
        }
        inPortions(changed, clusters).forEach { written(type, words, it, clusters) }
        gone.groupBy({ it.kind }, { it.key }).forEach { (kind, keys) -> type.groupManners.remove(kind, keys) }
        kept(type, members)
        val dated =
            (changed.map { it.group } + gone).flatMapTo(HashSet()) { group ->
                worksOf.getValue(group).flatMap(clusters::members)
            }
        return Made(changed.size + gone.size, dated)
    }

    /** The items the [members] of every group are written as those its manner was made of, where they changed. */
    private fun kept(type: TypeStore, members: Map<GroupRef, List<ItemKey>>) {
        val before = type.mannerMembers.itemsOf(members.keys)
        members.forEach { (group, items) ->
            if (before[group].orEmpty().toSet() != items.toSet()) type.mannerMembers.replace(group, items)
        }
    }

    /** The [changed] groups in portions of about [PORTION] items of their works: a group whole in one. */
    private fun inPortions(changed: List<Changed>, clusters: WorkClusters): List<List<Changed>> {
        val portions = ArrayList<List<Changed>>()
        var portion = ArrayList<Changed>()
        var items = 0
        changed.forEach { group ->
            portion += group
            items += group.works.sumOf { clusters.members(it).size }
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
    private fun written(type: TypeStore, words: Long, portion: List<Changed>, clusters: WorkClusters) {
        val vectors = HashMap<ItemKey, FloatArray>()
        portion
            .flatMap { group -> group.works.flatMap(clusters::members) }
            .distinct()
            .groupBy({ it.source }, { it.id })
            .forEach { (source, ids) ->
                type.source(source)?.manner?.ofMany(ids, words)?.forEach { (id, v) -> vectors[ItemKey(source, id)] = v }
            }
        portion.forEach { changed ->
            val own = changed.works.mapNotNull { work -> meanManner(clusters.members(work).mapNotNull(vectors::get)) }
            val kind = changed.group.kind
            val key = changed.group.key
            meanManner(own)?.let { type.groupManners.save(kind, key, words, GroupManner(changed.madeOf, own.size, it)) }
        }
    }

    /** The items of the works whose vectors one portion reads: a bound on the memory, not a meaning. */
    private const val PORTION = 10_000
}

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
