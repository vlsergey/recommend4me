package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(UnusedFandoms::class.java)

/**
 * A SITE'S FANDOM NO WORK OF ITS SITE HAS ANY MORE (user, 2026-11-14) — a key the site's pages gave up for another, a
 * part of a name a line was cut at wrong:
 * - goes with its universe when the two are one to one: the universe of no other site's fandom and of no entry of a
 *   catalogue (Wikidata) — two keys of one site of one name, both of no works, one fandom seen ("Mandy" and
 *   "book/Mandy"). Never a universe the user said anything of: named it, put it under or above another, told it apart,
 *   read its characters, answered a work with it;
 * - goes alone, its universe staying, when the universe has another fandom of the same site of the same name that works
 *   have: the one the works have now ("Supergirl" and "tv/Supergirl" of fanfiction.net — two of one seen).
 *
 * A site's fandom of no works in a universe with others of other names stays.
 */
@Component
class UnusedFandoms(private val stores: Stores) {

    /** At the start, the sites' fandoms bound first ([FandomNormalization.start]), before anything reads them. */
    @Order(FandomNormalization.BEFORE_READING + 1)
    @EventListener(ApplicationReadyEvent::class)
    // The start of the application: whatever fails is told in the log, and the fandoms stay as they were
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        try {
            letGo()
        } catch (e: Exception) {
            log.error("The unused fandoms were not let go of", e)
        }
    }

    /** Every unused site's fandom let go of — with its universe, or alone; how many. */
    fun letGo(): Int = stores.withUniverses().firstOrNull()?.universes?.fandoms?.let(::letGo) ?: 0

    private fun letGo(fandoms: FandomRepository): Int {
        val sources = stores.withUniverses().flatMap { it.sources }
        val bindings = fandoms.sites.bindings()
        val seen = Seen.of(sources, bindings.keys)
        val byUniverse = bindings.entries.groupBy({ it.value }, { it.key })
        val kept = said(fandoms) + answered(sources) + fandoms.entries.all().keys
        val alone = byUniverse.filter { (id, refs) ->
            id !in kept && refs.all { it in seen.unused } && seen.oneFandom(refs)
        }
        val twins = byUniverse.values.flatMap { refs -> refs.filter { seen.twin(it, refs) } }
        if (alone.isEmpty() && twins.isEmpty()) return 0
        fandoms.transaction { f ->
            alone.forEach { (id, refs) ->
                refs.forEach(f.sites::unbind)
                f.drop(id)
            }
            twins.forEach(f.sites::unbind)
        }
        log.info("Sites' fandoms no work has gone with their universes: {}; gone alone, of a twin: {}", alone, twins)
        return alone.values.sumOf { it.size } + twins.size
    }

    /** Of the sites' fandoms, those no work has, and the name each is shown by, alike of any case. */
    private class Seen(val unused: Set<SiteFandomRef>, val names: Map<SiteFandomRef, String>) {
        /** Whether [refs] are one fandom seen: of one site, of one name. */
        fun oneFandom(refs: List<SiteFandomRef>): Boolean = refs.map { it.source to names[it] }.distinct().size == 1

        /** Whether [ref], of no works, has a fandom of its site of its name among [refs] that works have. */
        fun twin(ref: SiteFandomRef, refs: List<SiteFandomRef>): Boolean =
            ref in unused && refs.any { it !in unused && it.source == ref.source && names[it] == names[ref] }

        companion object {
            fun of(sources: List<SourceStore>, refs: Collection<SiteFandomRef>): Seen {
                val unused = HashSet<SiteFandomRef>()
                val names = HashMap<SiteFandomRef, String>()
                sources.forEach { s ->
                    val facet = UniverseFacet.fandomFacet(s.schema) ?: return@forEach
                    val uses = s.items.valueUses(facet)
                    val named = s.items.facetNames(facet)
                    refs
                        .filter { it.source == s.id }
                        .forEach { ref ->
                            if ((uses[ref.key] ?: 0) == 0) unused += ref
                            names[ref] = (named[ref.key] ?: ref.key).trim().lowercase()
                        }
                }
                return Seen(unused, names)
            }
        }
    }

    /** The universes the user named, put under or above another, told apart, or read the characters of. */
    private fun said(fandoms: FandomRepository): Set<Long> =
        fandoms.all().filter { it.name != null || it.characters }.map { it.id }.toSet() +
            fandoms.relations.parents().flatMap { (child, parents) -> parents + child } +
            fandoms.relations.distinct().flatMap { listOf(it.first, it.second) }

    /** The universes a work was answered with, of every source. */
    private fun answered(sources: List<SourceStore>): Set<Long> =
        sources.flatMapTo(HashSet()) { s ->
            s.corrections
                .allFacets()
                .filter { it.facet == UniverseFacet.KEY }
                .mapNotNull { FandomRepository.idOf(it.key) }
        }
}
