package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters
import java.time.Instant

/**
 * WHAT CHANGED OF A CONTENT TYPE SINCE ITS INDEX OF THE LIST WAS LAST WRITTEN, by the dates the databases keep
 * ([io.github.vlsergey.recommend4me.item.ItemChanges], [io.github.vlsergey.recommend4me.work.WorkChanges]): the sources
 * changed whole, the items changed and those of the works changed — what [ListIndexing] writes anew.
 */
internal object ListChanges {

    /** Whether anything of the [type] changed at [since] or after. */
    fun any(type: TypeStore, since: Instant): Boolean =
        type.works.anySince(since) || type.sources.any { it.changes.everySince(since) || it.changes.anySince(since) }

    /** The sources of the [type] every item of which changed at [since] or after. */
    fun whole(type: TypeStore, since: Instant): Set<String> =
        type.sources.filter { it.changes.everySince(since) }.mapTo(HashSet()) { it.id }

    /**
     * The items whose documents are made anew, by source, of a source not made anew [whole]: those changed [since], and
     * the items of the works changed since, with every item of their works as the [clusters] tell them.
     */
    fun named(
        type: TypeStore,
        clusters: WorkClusters,
        since: Instant,
        whole: Set<String>,
    ): Map<String, Set<String>> {
        val keys = HashSet<ItemKey>()
        type.sources
            .filter { it.id !in whole }
            .forEach { s ->
                s.changes.since(since).forEach { keys += clusters.members(ItemKey(s.id, it)) }
            }
        type.works.changedSince(since).forEach { keys += clusters.members(it) }
        return keys.filter { it.source !in whole }.groupBy({ it.source }, { it.id }).mapValues { it.value.toSet() }
    }
}
