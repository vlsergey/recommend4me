package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.WholeReadRefused

/**
 * THE ENTRIES OF THE DICTIONARY'S UNIVERSES AS ONE OPERATION READS THEM: every universe's read at its first need and
 * once, the names the user gave read once for them all — handed to every part of the operation that wants them, of
 * which it lives no longer. A pass of the chain of the facets read a universe's characters for the main characters,
 * again for the secondary ones, again for the pairings, and the names the user gave again for every universe; a reading
 * of every universe with its counts ([UniverseRepository.all]) and then of their characters read every universe twice.
 *
 * MANY UNIVERSES READ AT ONCE ([read], [every]): every table of the dictionary once for them all — a universe at a time
 * was seven queries a universe, a reading of the chances every universe so.
 */
class CharactersRead(private val universes: UniverseRepository) {
    val local: Map<String, String> by lazy { universes.names.localNames() }

    /** The universes read so far; guarded by itself. */
    private val read = HashMap<UniverseKey, List<KeptEntry>>()

    /** Whether every universe of the dictionary is read: one not among them has no entry. */
    private var whole = false

    /** Every entry of the [universe], kept or not ([UniverseEntries.of]). */
    fun entries(universe: UniverseKey): List<KeptEntry> =
        synchronized(read) {
            read[universe]
                ?: if (whole) emptyList()
                else universes.entries.of(universe.catalogue, universe.id, local).also { read[universe] = it }
        }

    /** The entries of the [universe] kept as characters ([UniverseEntries.characters]). */
    fun of(universe: UniverseKey): List<UniverseCharacter> =
        entries(universe).filter { it.included }.map { it.character }

    /** The [wanted] universes not read yet read at once, before they are asked for one by one. */
    fun read(wanted: Collection<UniverseKey>) =
        synchronized(read) {
            if (whole) return@synchronized
            val missing = wanted.filter { it !in read }.distinct()
            if (missing.isEmpty()) return@synchronized
            val found = universes.entries.ofUniverses(missing, local)
            missing.forEach { read[it] = found[it].orEmpty() }
        }

    /**
     * Of the universes read so far, the characters [values] ("<catalogue>:<id>") kept among them: what the values a
     * work may have are told by, of its universes read already — no universe read again, none read whole.
     */
    fun ofValues(values: Set<String>): Map<String, UniverseCharacter> =
        synchronized(read) {
            val out = HashMap<String, UniverseCharacter>()
            read.forEach { (u, entries) ->
                entries.forEach { e ->
                    val value = UniverseFacet.valueOf(u.catalogue, e.character.id)
                    if (e.included && value in values) out[value] = e.character
                }
            }
            out
        }

    /** Every universe of the dictionary read at once. */
    fun every() =
        synchronized(read) {
            WholeReadRefused.check("every universe's characters")
            if (whole) return@synchronized
            universes.entries.ofUniverses(null, local).forEach { (key, entries) -> read.putIfAbsent(key, entries) }
            whole = true
        }
}
