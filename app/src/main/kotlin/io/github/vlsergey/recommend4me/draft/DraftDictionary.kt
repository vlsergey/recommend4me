package io.github.vlsergey.recommend4me.draft

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM_ENTRY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM_PARENT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM_SITE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER_PROPERTY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_PROPERTY
import io.github.vlsergey.recommend4me.universe.FandomRepository
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseKey
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * A statement asked of the dictionary: of a universe or of a character, of the [property], of the [value] lowercased.
 */
data class Asked(val character: Boolean, val catalogue: String, val property: String, val value: String)

/**
 * THE DICTIONARY OF UNIVERSES AS THE DRAFTS OF A FEW WORKS READ IT — by the keys they have, never whole (owner,
 * 2026-11-19: "an attempt to load ALL the values of any dictionary while loading the card of one work must fail"): the
 * universes the sites' fandoms are bound to and every one above them, the universes and the characters stating the
 * values asked of their entries, the universes the characters drafted are of.
 */
class DraftDictionary(private val db: DSLContext) {

    /**
     * Of the site's fandoms [keys] of the [source], the universes each is bound to and every universe above it, as
     * values; a fandom not bound left out.
     */
    fun universesOfSites(source: String, keys: Collection<String>): Map<String, List<String>> {
        val bound = HashMap<String, Long>()
        keys.distinct().chunked(BATCH).forEach { chunk ->
            db.select(FANDOM_SITE.SITE_KEY, FANDOM_SITE.FANDOM)
                .from(FANDOM_SITE)
                .where(FANDOM_SITE.SOURCE.eq(source), FANDOM_SITE.SITE_KEY.`in`(chunk))
                .fetch { bound[it.value1()!!] = it.value2()!! }
        }
        val above = ancestors(bound.values.toSet())
        return bound.mapValues { (_, id) -> (listOf(id) + above[id].orEmpty()).map(FandomRepository::value) }
    }

    /**
     * Of the fandoms [ids], every fandom above each, its parents' parents too, never itself: read a level at a time.
     */
    fun ancestors(ids: Set<Long>): Map<Long, Set<Long>> {
        val parents = HashMap<Long, Set<Long>>()
        var level = ids
        while (level.isNotEmpty()) {
            val read = parentsOf(level)
            level.forEach { parents[it] = read[it].orEmpty() }
            level = read.values.flatten().filterTo(HashSet()) { it !in parents }
        }
        return ids.associateWith { id ->
            val out = LinkedHashSet<Long>()
            val queue = ArrayDeque(parents[id].orEmpty())
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (next != id && out.add(next)) queue += parents[next].orEmpty()
            }
            out
        }
    }

    private fun parentsOf(ids: Collection<Long>): Map<Long, Set<Long>> {
        val out = HashMap<Long, MutableSet<Long>>()
        ids.chunked(BATCH).forEach { chunk ->
            db.select(FANDOM_PARENT.CHILD, FANDOM_PARENT.PARENT)
                .from(FANDOM_PARENT)
                .where(FANDOM_PARENT.CHILD.`in`(chunk))
                .fetch { out.getOrPut(it.value1()!!) { HashSet() } += it.value2()!! }
        }
        return out
    }

    /**
     * What the dictionary answers the statements [asked]: the universes bound to the entries stating it, of a statement
     * of a universe; the characters stating it, of a statement of a character. A statement none states left out.
     */
    fun answers(asked: Collection<Asked>): Map<Asked, Set<String>> {
        val (characters, universes) = asked.partition { it.character }
        return ofEntries(stated(universes, entries = true)) + stated(characters, entries = false)
    }

    /** Of the statements [asked], the entries — or the characters — stating each, as values. */
    private fun stated(asked: List<Asked>, entries: Boolean): Map<Asked, Set<String>> {
        val out = HashMap<Asked, MutableSet<String>>()
        asked
            .groupBy { it.catalogue to it.property }
            .forEach { (of, all) ->
                val (catalogue, property) = of
                val byValue = all.associateBy { it.value }
                byValue.keys.chunked(BATCH).forEach { chunk ->
                    if (entries) {
                        val p = UNIVERSE_PROPERTY
                        db.select(p.UNIVERSE_ID, p.VALUE_LOWER)
                            .from(p)
                            .where(p.CATALOGUE.eq(catalogue), p.PROPERTY.eq(property), p.VALUE_LOWER.`in`(chunk))
                            .fetch { r ->
                                out.getOrPut(byValue.getValue(r.value2()!!)) { HashSet() } +=
                                    UniverseFacet.valueOf(catalogue, r.value1()!!)
                            }
                    } else {
                        val p = UNIVERSE_CHARACTER_PROPERTY
                        db.selectDistinct(p.CHARACTER_ID, p.VALUE_LOWER)
                            .from(p)
                            .where(p.CATALOGUE.eq(catalogue), p.PROPERTY.eq(property), p.VALUE_LOWER.`in`(chunk))
                            .fetch { r ->
                                out.getOrPut(byValue.getValue(r.value2()!!)) { HashSet() } +=
                                    UniverseFacet.valueOf(catalogue, r.value1()!!)
                            }
                    }
                }
            }
        return out
    }

    /**
     * Of the statements answered by the entries [stated], the universes bound to them instead: an entry of none, none.
     */
    private fun ofEntries(stated: Map<Asked, Set<String>>): Map<Asked, Set<String>> {
        val fandoms = fandomsOfEntries(stated.values.flatten().toSet())
        return stated
            .mapValues { (_, entries) -> entries.flatMapTo(HashSet()) { fandoms[it].orEmpty() } }
            .filterValues { it.isNotEmpty() }
    }

    /**
     * Of the entries [values] ("wikidata:Q5410773"), the fandoms bound to each, as values; of those whose characters
     * are read alone when [characters].
     */
    private fun fandomsOfEntries(values: Set<String>, characters: Boolean = false): Map<String, Set<String>> {
        val keys = values.mapNotNull { v -> UniverseFacet.parse(v)?.let { UniverseKey(it.first, it.second) } }
        val out = HashMap<String, MutableSet<String>>()
        keys.chunked(BATCH).forEach { chunk ->
            db.select(FANDOM_ENTRY.CATALOGUE, FANDOM_ENTRY.ENTRY, FANDOM_ENTRY.FANDOM)
                .from(FANDOM_ENTRY)
                .join(FANDOM)
                .on(FANDOM.ID.eq(FANDOM_ENTRY.FANDOM))
                .where(
                    DSL.row(FANDOM_ENTRY.CATALOGUE, FANDOM_ENTRY.ENTRY)
                        .`in`(chunk.map { DSL.row(it.catalogue, it.id) }),
                    if (characters) FANDOM.CHARACTERS.isTrue else DSL.noCondition(),
                )
                .fetch { r ->
                    out.getOrPut(UniverseFacet.valueOf(r.value1()!!, r.value2()!!)) { HashSet() } +=
                        FandomRepository.value(r.value3()!!)
                }
        }
        return out
    }

    /**
     * Of the characters [values], the universes each is of: the fandoms bound to its entries whose characters are read.
     * A character of none left out.
     */
    fun universesOfCharacters(values: Collection<String>): Map<String, Set<String>> {
        val entries = HashMap<String, MutableSet<String>>()
        val keys = values.mapNotNull { v -> UniverseFacet.parse(v)?.let { v to it } }
        keys.chunked(BATCH).forEach { chunk ->
            val c = UNIVERSE_CHARACTER
            db.select(c.CATALOGUE, c.CHARACTER_ID, c.UNIVERSE_ID)
                .from(c)
                .where(DSL.row(c.CATALOGUE, c.CHARACTER_ID).`in`(chunk.map { (_, k) -> DSL.row(k.first, k.second) }))
                .fetch { r ->
                    entries.getOrPut(UniverseFacet.valueOf(r.value1()!!, r.value2()!!)) { HashSet() } +=
                        UniverseFacet.valueOf(r.value1()!!, r.value3()!!)
                }
        }
        val fandoms = fandomsOfEntries(entries.values.flatten().toSet(), characters = true)
        return entries
            .mapValues { (_, of) -> of.flatMapTo(HashSet()) { fandoms[it].orEmpty() } }
            .filterValues { it.isNotEmpty() }
    }

    private companion object {
        /** The keys one statement asks for: the length of its list, not a meaning. */
        const val BATCH = 1000
    }
}
