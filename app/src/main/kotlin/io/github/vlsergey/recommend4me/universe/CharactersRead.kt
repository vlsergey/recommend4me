package io.github.vlsergey.recommend4me.universe

/**
 * THE ENTRIES OF THE DICTIONARY'S UNIVERSES AS ONE OPERATION READS THEM: every universe's read at its
 * first need and once, the names the user gave read once for them all — handed to every part of the
 * operation that wants them, of which it lives no longer. A pass of the chain of the facets read a
 * universe's characters for the main characters, again for the secondary ones, again for the
 * pairings, and the names the user gave again for every universe; a reading of every universe with
 * its counts ([UniverseRepository.all]) and then of their characters read every universe twice.
 */
class CharactersRead(private val universes: UniverseRepository) {
    val local: Map<String, String> by lazy { universes.localNames() }

    /** The universes read so far; guarded by itself. */
    private val read = HashMap<UniverseKey, List<KeptEntry>>()

    /** Every entry of the [universe], kept or not ([UniverseRepository.entries]). */
    fun entries(universe: UniverseKey): List<KeptEntry> = synchronized(read) {
        read.getOrPut(universe) { universes.entries(universe.catalogue, universe.id, local) }
    }

    /** The entries of the [universe] kept as characters ([UniverseRepository.characters]). */
    fun of(universe: UniverseKey): List<UniverseCharacter> = entries(universe).filter { it.included }.map { it.character }
}
