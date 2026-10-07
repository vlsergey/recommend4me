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
 * the main one first — authors write the one they like. What the catalogue says it is in [classes]
 * (a human, a wizard, a shop, a spell): a universe holds more than its characters.
 */
class UniverseCharacter(
    val id: String,
    val names: List<String>,
    val description: String?,
    val url: String,
    /** The ids of the catalogue's classes the entry is of; none when the catalogue does not say. */
    val classes: List<String> = emptyList(),
    /** The character's sex or gender as the catalogue gives it; null when it does not say. */
    val sex: CharacterSex? = null,
)

/**
 * A character's sex or gender: male, female, or anything else the catalogue says — agender,
 * transgender, non-binary, genderless.
 */
enum class CharacterSex { MALE, FEMALE, OTHER }

/**
 * A class of the catalogue's entries, named in the languages asked for. [character]: its entries
 * are characters — people, creatures, beings a story is about — rather than places, groups,
 * things, spells or events.
 */
class UniverseClass(val id: String, val name: String, val character: Boolean)

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

    /**
     * Every entry said to be in the universe [id] and in the works, series and franchises within
     * it, with its classes: the characters and whatever else the catalogue puts there.
     */
    fun characters(id: String, languages: List<String>): List<UniverseCharacter>

    /** The classes [ids], named, each with whether its entries are characters; none of a catalogue without classes. */
    fun classes(ids: Collection<String>, languages: List<String>): List<UniverseClass> = emptyList()
}
