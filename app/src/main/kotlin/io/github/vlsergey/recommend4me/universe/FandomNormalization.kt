package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.layer.LayerValues
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
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
class FandomNormalization(private val stores: Stores) {

    /** Every source of every type with universes that has the site's fandoms, with the key of that facet. */
    private fun sites(): List<Pair<SourceStore, String>> =
        stores.types
            .filter { it.type.universes }
            .flatMap { it.sources }
            .mapNotNull { s -> UniverseFacet.fandomFacet(s.schema)?.let { s to it } }

    private fun fandoms(): FandomRepository? = stores.types.firstOrNull { it.type.universes }?.universes?.fandoms

    /** Every site's fandom not bound yet bound now, every fandom's name worked out again; how many bound. */
    fun bindNew(): Int {
        val fandoms = fandoms() ?: return 0
        val sites = sites()
        val made = bind(fandoms, sites) { s, f -> s.items.valueUses(f).keys }
        rename(fandoms, sites, null)
        return made
    }

    /**
     * The site's fandoms of the works [event] names bound at once, before anything reads the works: a work captured is
     * read with its universes.
     */
    @Order(FIRST)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val fandoms = fandoms() ?: return
        val keys =
            sites()
                .firstOrNull { it.first.id == event.source }
                ?.let { (source, facet) -> source.items.facetsOf(event.ids).values.flatMap { it[facet].orEmpty() } }
                .orEmpty()
                .toSet()
        if (keys.isNotEmpty() && bind(fandoms, sites()) { s, _ -> if (s.id == event.source) keys else emptySet() } > 0)
            rename(fandoms, sites(), null)
    }

    /**
     * The site's fandoms [keysOf] gives of every source bound, the ones not bound yet; how many. One at a time: two at
     * once would make two fandoms of one name.
     */
    @Synchronized
    private fun bind(
        fandoms: FandomRepository,
        sites: List<Pair<SourceStore, String>>,
        keysOf: (SourceStore, String) -> Set<String>,
    ): Int {
        val bound = fandoms.sites.bindings()
        val fresh =
            sites
                .associate { (s, f) -> s to (f to keysOf(s, f).filter { SiteFandomRef(s.id, it) !in bound }) }
                .filterValues { it.second.isNotEmpty() }
        if (fresh.isEmpty()) return 0
        val names = sites.associate { (s, f) -> s.id to s.items.facetNames(f) }
        // The normalized fandoms every name is bound to, of every site
        val byName = HashMap<String, MutableSet<Long>>()
        bound.forEach { (ref, id) ->
            byName.getOrPut(LayerValues.keyOf(names[ref.source]?.get(ref.key) ?: ref.key)) { HashSet() } += id
        }
        val now = Instant.now()
        var made = 0
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
                made++
            }
        }
        log.info("{} fandoms of the sites bound to normalized fandoms", made)
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
            val names = s.items.facetNames(f)
            val uses = s.items.valueUses(f)
            bound
                .filterKeys { it.source == s.id }
                .forEach { (ref, id) ->
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

    /** Before anything reads the universes: every site's fandom not bound yet bound. */
    @Order(BEFORE_READING)
    @EventListener(ApplicationReadyEvent::class)
    // The start of the application: whatever fails is told in the log, and the fandoms are bound as works come
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        try {
            bindNew()
        } catch (e: Exception) {
            log.error("The fandoms were not normalized", e)
        }
    }

    companion object {
        /** Before every other listener of the works: their sites' fandoms are bound before anything reads them. */
        const val FIRST = -100

        /** Of the start, before anything reads the universes. */
        const val BEFORE_READING = -10
    }
}
