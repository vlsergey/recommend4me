package io.github.vlsergey.recommend4me.universe

/**
 * THE ENTRIES OF THE DICTIONARY'S UNIVERSES AS ONE OPERATION READS THEM: every universe's read at its
 * first need and once, the names the user gave read once for them all — handed to every part of the
 * operation that wants them, of which it lives no longer. A pass of the chain of the facets read a
 * universe's characters for the main characters, again for the secondary ones, again for the
 * pairings, and the names the user gave again for every universe; a reading of every universe with
 * its counts ([UniverseRepository.all]) and then of their characters read every universe twice.
 *
 * MANY UNIVERSES READ AT ONCE ([read], [every]): every table of the dictionary once for them all —
 * a universe at a time was seven queries a universe, a reading of the chances every universe so.
 */
class CharactersRead(private val universes: UniverseRepository) {
    val local: Map<String, String> by lazy { universes.localNames() }

    /** The universes read so far; guarded by itself. */
    private val read = HashMap<UniverseKey, List<KeptEntry>>()

    /** Whether every universe of the dictionary is read: one not among them has no entry. */
    private var whole = false

    /** Every entry of the [universe], kept or not ([UniverseRepository.entries]). */
    fun entries(universe: UniverseKey): List<KeptEntry> = synchronized(read) {
        read[universe] ?: if (whole) emptyList() else universes.entries(universe.catalogue, universe.id, local).also { read[universe] = it }
    }

    /** The entries of the [universe] kept as characters ([UniverseRepository.characters]). */
    fun of(universe: UniverseKey): List<UniverseCharacter> = entries(universe).filter { it.included }.map { it.character }

    /** The [wanted] universes not read yet read at once, before they are asked for one by one. */
    fun read(wanted: Collection<UniverseKey>) = synchronized(read) {
        if (whole) return@synchronized
        val missing = wanted.filter { it !in read }.distinct()
        if (missing.isEmpty()) return@synchronized
        val found = universes.entriesOf(missing, local)
        missing.forEach { read[it] = found[it].orEmpty() }
    }

    /** Every universe of the dictionary read at once. */
    fun every() = synchronized(read) {
        if (whole) return@synchronized
        universes.entriesOf(null, local).forEach { (key, entries) -> read.putIfAbsent(key, entries) }
        whole = true
    }
}
