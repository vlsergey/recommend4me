package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.WorkKind
import io.github.vlsergey.recommend4me.item.SiteItemWriteEvent
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.WorkKeys
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(FandomSiteWorks::class.java)

/**
 * THE WORKS OF EVERY SITE'S FANDOM, COUNTED IN THE QUEUE AND KEPT ([FandomSites.works]) — what the screens of the
 * universes show and sort by, read, never counted at a request (user, 2026-11-17: a universe's screen counted the
 * values of every item of every site, 5 s). Counted of the fandoms waiting, kept in the queue ([WorkKeys.want]): those
 * of the works of a site written, a quiet after; those bound and not counted yet, at the start. A work that leaves a
 * fandom leaves its count one too high till its fandom is counted again.
 */
@Component
class FandomSiteWorks(private val stores: Stores, private val workload: Workload) {

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        workload.resumption.resumes(CHANGED) { _, reason -> planChanged(reason) }
    }

    /**
     * The sites' fandoms bound and not counted yet, counted — what the data say, not a counting "just in case" (owner,
     * 2026-11-19: every universe showed 0 works, none of the sites' fandoms ever counted): kept waiting in the queue as
     * those of the works written are.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val uncounted = fandoms()?.sites?.uncounted().orEmpty()
        if (uncounted.isEmpty()) return
        workload.keys.want(CHANGING, uncounted.map { "${it.source}$SEP${it.key}" })
        planChanged(UNCOUNTED)
    }

    private fun planChanged(reason: String) =
        workload.plan(CHANGING, Instant.now().plusSeconds(QUIET_SECONDS), reason) { countWaiting() }

    @EventListener
    fun itemsChanged(event: SiteItemWriteEvent) {
        val (store, facet) = sites().firstOrNull { it.first.id == event.source && event.ids.isNotEmpty() } ?: return
        val keys = store.items.facetsOf(event.ids).values.flatMap { it[facet].orEmpty() }
        if (keys.isEmpty()) return
        workload.keys.want(CHANGING, keys.map { "${store.id}$SEP$it" })
        planChanged("items")
    }

    /** Every source of every type with universes that has the site's fandoms, with the key of that facet. */
    private fun sites(): List<Pair<SourceStore, String>> =
        stores.types
            .filter { it.type.universes }
            .flatMap { it.sources }
            .mapNotNull { s -> UniverseFacet.fandomFacet(s.schema)?.let { s to it } }

    private fun fandoms(): FandomRepository? = stores.types.firstOrNull { it.type.universes }?.universes?.fandoms

    /** The works of the sites' fandoms waiting counted again, those changed kept. */
    private fun countWaiting() {
        val fandoms = fandoms() ?: return
        val taken = workload.keys.wanted(CHANGING)
        val asked = taken.groupBy({ it.substringBefore(SEP) }, { it.substringAfter(SEP) })
        val started = System.currentTimeMillis()
        val counted = HashMap<SiteFandomRef, Int>()
        sites().forEach { (s, facet) ->
            val keys = asked[s.id] ?: return@forEach
            val uses = s.items.valueUsesOf(facet, keys)
            keys.forEach { key -> counted[SiteFandomRef(s.id, key)] = uses[key] ?: 0 }
        }
        keep(fandoms, counted, counted.size, started)
        workload.keys.done(CHANGING, taken)
    }

    private fun keep(fandoms: FandomRepository, changed: Map<SiteFandomRef, Int>, of: Int, started: Long) {
        fandoms.sites.works(changed)
        log.info(
            "the works of {} sites' fandoms counted in {} ms, {} of them changed",
            of,
            System.currentTimeMillis() - started,
            changed.size,
        )
    }

    private companion object {
        /** The works of the sites' fandoms waiting counted: of the works written, of those not counted yet. */
        val CHANGED = WorkKind.FANDOM_SITE_WORKS_CHANGED
        val CHANGING = Work(CHANGED)

        /** The key of a site's fandom waiting in the queue: its source and its key. */
        const val SEP = '\t'

        /** Why every fandom is counted at a start: one bound is not counted yet. */
        const val UNCOUNTED = "uncounted"

        /** The quiet after a change of the works before their fandoms are counted: an import's batches at once. */
        const val QUIET_SECONDS = 30L
    }
}
