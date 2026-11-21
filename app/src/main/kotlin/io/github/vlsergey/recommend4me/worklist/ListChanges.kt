package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters
import java.time.Instant
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(ListChanges::class.java)

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
        val told = HashMap<String, Int>()
        type.sources
            .filter { it.id !in whole }
            .forEach { s ->
                val changed = s.changes.since(since)
                if (changed.isNotEmpty()) told[s.id] = changed.size
                changed.forEach { keys += clusters.members(ItemKey(s.id, it)) }
            }
        val works = type.works.changedSince(since)
        works.forEach { keys += clusters.members(it) }
        val named = keys.filter { it.source !in whole }.groupBy({ it.source }, { it.id }).mapValues { it.value.toSet() }
        log.info(
            "{}: changed since {}: items {}, items of the works changed {}; named with their works' items {}",
            type.id,
            since,
            told,
            works.size,
            named.mapValues { it.value.size },
        )
        return named
    }
}
