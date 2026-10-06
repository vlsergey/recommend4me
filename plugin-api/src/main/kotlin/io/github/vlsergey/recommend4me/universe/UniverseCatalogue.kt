package io.github.vlsergey.recommend4me.universe

/**
 * An entry of a catalogue of fictional universes: a universe, a franchise, a series, a work —
 * whatever the catalogue holds that fan fiction is written about — with its name and a line that
 * tells it from its namesakes.
 */
class UniverseEntry(
    /** Stable within the catalogue: "Q8337". */
    val id: String,
    val name: String,
    val description: String?,
    /** The entry's page for the user to look at. */
    val url: String,
)

/**
 * A character of a universe: every name it goes by in the languages the catalogue was asked for,
 * the main one first — authors write the one they like.
 */
class UniverseCharacter(
    val id: String,
    val names: List<String>,
    val description: String?,
    val url: String,
)

/**
 * A catalogue of fictional universes and their characters — Wikidata, kept by people and kept up
 * to date. The application asks it for the universes a fandom of a site may be, and for the
 * characters of the universes the user linked a fandom to; what it answers is kept and asked for
 * again when the user wants it fresh.
 *
 * Implemented by a plugin and declared as a Spring bean. The calls go to the catalogue's service
 * and may take seconds.
 */
interface UniverseCatalogue {
    /** Stable: the ids of its entries are kept with it. */
    val id: String

    /** The name shown to the user. */
    val title: String

    /** The entries a name of a fandom may stand for, the likeliest first. */
    fun findUniverses(name: String, languages: List<String>): List<UniverseEntry>

    fun universe(id: String, languages: List<String>): UniverseEntry?

    /** Every character of the universe [id] and of the works, series and franchises within it. */
    fun characters(id: String, languages: List<String>): List<UniverseCharacter>
}
