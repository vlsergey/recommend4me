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
 * A SITE'S FANDOM NO WORK OF ITS SITE HAS ANY MORE GOES WITH ITS UNIVERSE, WHEN THE TWO ARE ONE TO ONE (user,
 * 2026-11-14): the universe of no other site's fandom and of no entry of a catalogue (Wikidata) — a key the site's
 * pages gave up for another, a part of a name a line was cut at wrong. A site's fandom of a universe with others stays,
 * works or none. Never a universe the user said anything of: named it, put it under or above another, told it apart,
 * read its characters, answered a work with it.
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

    /** Every unused site's fandom one to one with its universe gone with it; how many. */
    fun letGo(): Int = stores.withUniverses().firstOrNull()?.universes?.fandoms?.let(::letGo) ?: 0

    private fun letGo(fandoms: FandomRepository): Int {
        val sources = stores.withUniverses().flatMap { it.sources }
        val used =
            sources.flatMapTo(HashSet()) { s ->
                UniverseFacet.fandomFacet(s.schema)
                    ?.let { f -> s.items.valueUses(f).keys.map { SiteFandomRef(s.id, it) } }
                    .orEmpty()
            }
        val said = said(fandoms) + answered(sources)
        val withEntries = fandoms.entries.all().keys
        val alone =
            fandoms.sites
                .bindings()
                .entries
                .groupBy({ it.value }, { it.key })
                .mapNotNull { (id, refs) -> refs.singleOrNull()?.let { id to it } }
                .filter { (id, ref) -> ref !in used && id !in withEntries && id !in said }
        if (alone.isEmpty()) return 0
        fandoms.transaction { f ->
            alone.forEach { (id, ref) ->
                f.sites.unbind(ref)
                f.drop(id)
            }
        }
        log.info(
            "{} sites' fandoms no work has, one to one with their universes, gone with them: {}",
            alone.size,
            alone,
        )
        return alone.size
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
