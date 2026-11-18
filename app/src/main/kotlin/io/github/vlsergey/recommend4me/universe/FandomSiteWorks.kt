package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.workload.Work
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
 * values of every item of every site, 5 s). Counted whole on the start; then, a quiet after the works of a site change,
 * of the fandoms of those works alone. A work that leaves a fandom leaves its count one too high till the next start.
 */
@Component
class FandomSiteWorks(private val stores: Stores, private val workload: Workload) {

    /** The keys of the sites' fandoms waiting to be counted again, by source; taken whole by the work. */
    private val waiting = HashMap<String, MutableSet<String>>()

    /** What the last run of the application left undone of the counting, planned again: of every fandom. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val left = workload.left(EVERY) + workload.left(CHANGED)
        if (fandoms() == null) return
        left
            .flatMap { it.reasons.keys }
            .distinct()
            .forEach { reason ->
                workload.plan(Work(EVERY), Instant.now(), reason) { countEvery() }
            }
    }

    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val (store, facet) = sites().firstOrNull { it.first.id == event.source && event.ids.isNotEmpty() } ?: return
        val keys = store.items.facetsOf(event.ids).values.flatMap { it[facet].orEmpty() }
        if (keys.isEmpty()) return
        synchronized(waiting) { waiting.getOrPut(store.id) { HashSet() } += keys }
        workload.plan(Work(CHANGED), Instant.now().plusSeconds(QUIET_SECONDS), "items") { countWaiting() }
    }

    /** Every source of every type with universes that has the site's fandoms, with the key of that facet. */
    private fun sites(): List<Pair<SourceStore, String>> =
        stores.types
            .filter { it.type.universes }
            .flatMap { it.sources }
            .mapNotNull { s -> UniverseFacet.fandomFacet(s.schema)?.let { s to it } }

    private fun fandoms(): FandomRepository? = stores.types.firstOrNull { it.type.universes }?.universes?.fandoms

    /** The works of every site's fandom counted, those changed kept. */
    private fun countEvery() {
        val fandoms = fandoms() ?: return
        val started = System.currentTimeMillis()
        val kept = fandoms.sites.works()
        val bound = fandoms.sites.bindings().keys
        val counted = HashMap<SiteFandomRef, Int>()
        sites().forEach { (s, facet) ->
            val uses = s.items.valueUses(facet)
            bound.filter { it.source == s.id }.forEach { ref -> counted[ref] = uses[ref.key] ?: 0 }
        }
        keep(fandoms, counted.filter { (ref, works) -> kept[ref] != works }, counted.size, started)
    }

    /** The works of the sites' fandoms waiting counted again, those changed kept. */
    private fun countWaiting() {
        val fandoms = fandoms() ?: return
        val asked = synchronized(waiting) { HashMap(waiting).also { waiting.clear() } }
        val started = System.currentTimeMillis()
        val counted = HashMap<SiteFandomRef, Int>()
        sites().forEach { (s, facet) ->
            val keys = asked[s.id] ?: return@forEach
            val uses = s.items.valueUsesOf(facet, keys)
            keys.forEach { key -> counted[SiteFandomRef(s.id, key)] = uses[key] ?: 0 }
        }
        keep(fandoms, counted, counted.size, started)
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
        /**
         * The works of every site's fandom counted; of those of the works changed, another work: neither replaces the
         * other.
         */
        const val EVERY = "fandomSiteWorks"
        const val CHANGED = "fandomSiteWorksChanged"

        /** The quiet after a change of the works before their fandoms are counted: an import's batches at once. */
        const val QUIET_SECONDS = 30L
    }
}
