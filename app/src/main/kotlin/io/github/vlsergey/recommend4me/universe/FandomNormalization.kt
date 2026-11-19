package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.SiteItemWriteEvent
import io.github.vlsergey.recommend4me.layer.LayerValues
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(FandomNormalization::class.java)

/**
 * EVERY SITE'S FANDOM IS BOUND TO A NORMALIZED FANDOM ([FandomRepository]). A site's fandom seen first is bound to the
 * normalized fandom the sites' fandoms of its name are bound to — "Гарри Поттер" of one site and of another one
 * universe — when there is exactly one such; else to a new normalized fandom of its own. From then on the user binds
 * it: merges fandoms, takes one out.
 *
 * What a fandom without a name of the user's or of an entry is shown by is worked out here too
 * ([FandomRepository.calculatedName]): the name of its sites' fandom of the most works.
 */
@Component
class FandomNormalization(private val stores: Stores, private val tasks: Tasks) {

    // What the last run of the application left undone of it: planned again by the queue at its start, of the fandoms
    // the queue kept
    init {
        tasks.resumption.resumes(TaskKind.RENAME_FANDOMS) { _, reason -> planRename(reason) }
    }

    /** Every source of every type with universes that has the site's fandoms, with the key of that facet. */
    private fun sites(): List<Pair<SourceStore, String>> =
        stores.types
            .filter { it.type.universes }
            .flatMap { it.sources }
            .mapNotNull { s -> UniverseFacet.fandomFacet(s.schema)?.let { s to it } }

    private fun fandoms(): FandomRepository? = stores.types.firstOrNull { it.type.universes }?.universes?.fandoms

    /**
     * The site's fandoms of the works [event] names bound at once, before anything reads the works: a work captured is
     * read with its universes.
     */
    @Order(FIRST)
    @EventListener
    fun itemsChanged(event: SiteItemWriteEvent) {
        val fandoms = fandoms() ?: return
        val keys =
            sites()
                .firstOrNull { it.first.id == event.source }
                ?.let { (source, facet) -> source.items.facetsOf(event.ids).values.flatMap { it[facet].orEmpty() } }
                .orEmpty()
                .toSet()
        if (keys.isEmpty()) return
        // The names of the fandoms bound to alone worked out again: of every fandom, a batch of a dump's import a pass
        // over every value of every site, slower with every work it added (user, 2026-11-17)
        val touched = bind(fandoms, sites()) { s, _ -> if (s.id == event.source) keys else emptySet() }
        if (touched.isNotEmpty()) renameLater(touched)
    }

    /**
     * The names of the fandoms [ids] worked out again in the queue of the computations: counting the works of their
     * sites' fandoms reads every work of a large one — seconds of a fandom of a hundred thousand works — which no
     * request waits for (user, 2026-11-17: a merging of universes took 15 s). Those gone by then are left out. The
     * fandoms waiting kept in the queue ([io.github.vlsergey.recommend4me.task.TaskKeys]): a stop leaves them to the
     * next start.
     */
    fun renameLater(ids: Collection<Long>) {
        tasks.keys.want(RENAME, ids.map { it.toString() })
        planRename("universes")
    }

    /** The names of the fandoms waiting worked out again, planned for the [reason]. */
    private fun planRename(reason: String) =
        tasks.plan(RENAME, Instant.now(), reason) {
            val taken = tasks.keys.wanted(RENAME)
            val ids = taken.mapNotNull { it.toLongOrNull() }
            val fandoms = fandoms()
            if (fandoms != null && ids.isNotEmpty()) rename(fandoms, sites(), ids.filter(fandoms::exists))
            tasks.keys.done(RENAME, taken)
        }

    /**
     * The site's fandoms [keysOf] gives of every source bound, the ones not bound yet; the normalized fandoms they were
     * bound to. One at a time: two at once would make two fandoms of one name.
     */
    @Synchronized
    private fun bind(
        fandoms: FandomRepository,
        sites: List<Pair<SourceStore, String>>,
        keysOf: (SourceStore, String) -> Set<String>,
    ): Set<Long> {
        val bound = fandoms.sites.bindings()
        val fresh =
            sites
                .associate { (s, f) -> s to (f to keysOf(s, f).filter { SiteFandomRef(s.id, it) !in bound }) }
                .filterValues { it.second.isNotEmpty() }
        if (fresh.isEmpty()) return emptySet()
        val names = sites.associate { (s, f) -> s.id to s.items.facetNames(f) }
        // The normalized fandoms every name is bound to, of every site
        val byName = HashMap<String, MutableSet<Long>>()
        bound.forEach { (ref, id) ->
            byName.getOrPut(LayerValues.keyOf(names[ref.source]?.get(ref.key) ?: ref.key)) { HashSet() } += id
        }
        val now = Instant.now()
        val made = HashSet<Long>()
        fresh.forEach { (s, of) ->
            of.second.forEach { key ->
                val written = (names[s.id]?.get(key) ?: key).trim()
                val name = LayerValues.keyOf(written)
                val id =
                    byName[name]?.singleOrNull()
                        ?: fandoms.create(now, calculatedName = written).also {
                            byName.getOrPut(name) { HashSet() } += it
                        }
                fandoms.sites.bind(SiteFandomRef(s.id, key), id, now)
                made += id
            }
        }
        log.info(
            "{} fandoms of the sites bound to {} normalized fandoms",
            fresh.values.sumOf { it.second.size },
            made.size,
        )
        return made
    }

    /**
     * The name every fandom of [only] — every one when null — is shown by without a name of the user's or of an entry,
     * worked out again: of its sites' fandoms, the name of the one of the most works; of the same, by the alphabet.
     */
    fun rename(only: Collection<Long>?) {
        val fandoms = fandoms() ?: return
        rename(fandoms, sites(), only)
    }

    private fun rename(fandoms: FandomRepository, sites: List<Pair<SourceStore, String>>, only: Collection<Long>?) {
        val wanted = only?.toSet()
        val bound = fandoms.sites.bindings().filterValues { wanted == null || it in wanted }
        // Of every fandom, its sites' fandoms' names with their works: the most works first, of as many by the alphabet
        val named = HashMap<Long, MutableList<Pair<String, Int>>>()
        sites.forEach { (s, f) ->
            val own = bound.filterKeys { it.source == s.id }
            // Of a few fandoms, the names and the works of their sites' fandoms alone
            val keys = own.keys.map { it.key }
            val names = if (wanted == null) s.items.facetNames(f) else s.items.facetNames(f, keys)
            val uses = if (wanted == null) s.items.valueUses(f) else s.items.valueUsesOf(f, keys)
            own.forEach { (ref, id) ->
                named.getOrPut(id) { ArrayList() } += (names[ref.key] ?: ref.key).trim() to (uses[ref.key] ?: 0)
            }
        }
        val best = named.mapValues { (_, of) ->
            of.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).first().first
        }
        fandoms
            .all()
            .filter { wanted == null || it.id in wanted }
            .forEach { row ->
                val name = best[row.id]
                if (name != row.calculatedName) fandoms.calculatedName(row.id, name)
            }
    }

    companion object {
        /** Before every other listener of the works: their sites' fandoms are bound before anything reads them. */
        const val FIRST = -100

        /** The names of fandoms worked out again ([renameLater]). */
        private val RENAME = Task(TaskKind.RENAME_FANDOMS)
    }
}
