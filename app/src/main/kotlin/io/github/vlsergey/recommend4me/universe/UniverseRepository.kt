package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER_PROPERTY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_PROPERTY
import io.github.vlsergey.recommend4me.preferences.CatalogueRenameRepository
import java.time.Instant
import org.jooq.DSLContext

/** A universe of the dictionary by its key: its catalogue and its id there. */
data class UniverseKey(val catalogue: String, val id: String) {
    val value: String
        get() = UniverseFacet.valueOf(catalogue, id)
}

/** A universe of the dictionary, with how many of its entries are kept as characters, of how many. */
class StoredUniverse(
    val catalogue: String,
    val id: String,
    /** The catalogue's name, in the user's languages. */
    val catalogueName: String,
    val description: String?,
    val url: String,
    val addedAt: Instant,
    val refreshedAt: Instant,
    val characters: Int,
    val entries: Int,
    /** The languages the catalogue was asked in for it; none of a universe saved before they were kept. */
    val languages: List<String>,
    /**
     * The statements its characters were asked for with ([UniverseCharacter.properties]); none before they were kept.
     */
    val properties: Set<String>,
    /** The name the user gave it over the catalogue's; null when none. */
    val localName: String? = null,
) {
    val value: String
        get() = UniverseFacet.valueOf(catalogue, id)

    /** What it is shown by: the user's name, else the catalogue's. */
    val name: String
        get() = localName ?: catalogueName

    /** Every name it goes by, the shown one first: what its works' texts are read for. */
    val names: List<String>
        get() = listOfNotNull(localName, catalogueName).distinct()
}

/**
 * An entry of a universe of the dictionary, whether it is kept as a character ([included]), and the user's word on it
 * alone ([choice]; null when its classes decide). Its [character]'s names begin with the name the user gave it
 * ([localName]), when given.
 */
class KeptEntry(
    val character: UniverseCharacter,
    val included: Boolean,
    val choice: Boolean?,
    val localName: String? = null,
)

/**
 * A class the entries of a universe are of: whether its entries are characters, how many of the universe's entries are
 * of it, whether they are kept ([included]) and the user's word on it ([choice]; null when it is kept as the catalogue
 * says — kept when of characters).
 */
class ClassInUniverse(
    val id: String,
    val name: String,
    val character: Boolean,
    val entries: Int,
    val included: Boolean,
    val choice: Boolean?,
)

/**
 * THE DICTIONARY OF UNIVERSES, in the dictionaries: the universes — the normalized fandoms, the sites' fandoms bound to
 * them ([fandoms]) — and the entries of the catalogue they are bound to, as the catalogue gave them, with their
 * statements here; the entries' characters and the user's words on them ([entries]), the names the universes and the
 * characters are shown by ([names]) each of its own.
 */
class UniverseRepository(
    private val db: DSLContext,
    /** The user's languages: the texts are shown in the first of them that has one. */
    private val languages: List<String>,
    /** The names the user gives the universes and the characters, in the user's preferences. */
    renames: CatalogueRenameRepository,
) {
    /** The names and the descriptions the universes and their characters are shown by. */
    val names = DictionaryNames(db, languages, renames)

    /** The entries of the universes, which of them are kept as characters, and the user's words on them. */
    val entries = UniverseEntries(db, languages, names)

    /** The universes: the normalized fandoms, the sites' fandoms and the entries bound to them. */
    val fandoms = FandomRepository(db)

    /** The universes as one operation reads them. */
    fun dictionary(): FandomDictionary = FandomDictionary.load(fandoms)

    /** The names and the descriptions the universes are shown by. */
    val shown = FandomNames(names, ::dictionary)

    /** What a catalogue answers of a universe, written into the dictionary. */
    private val fromCatalogue = UniverseFromCatalogue(db)

    /** Every entry of the catalogue the dictionary keeps, by its key alone, its characters not read. */
    fun entryKeys(): List<UniverseKey> =
        db.select(UNIVERSE.CATALOGUE, UNIVERSE.UNIVERSE_ID).from(UNIVERSE).fetch {
            UniverseKey(it.value1()!!, it.value2()!!)
        }

    /**
     * Every entry of the catalogue the dictionary keeps with its counts; its characters of [read], read once for the
     * operation that reads them too.
     */
    fun allEntries(read: CharactersRead = CharactersRead(this)): List<StoredUniverse> {
        val local = read.local
        read.every()
        return db.selectFrom(UNIVERSE)
            .fetch()
            .map { u ->
                val entries = read.entries(UniverseKey(u.catalogue!!, u.universeId!!))
                StoredUniverse(
                    // Of a universe saved before the texts were kept by language, the ones shown then
                    u.catalogue!!,
                    u.universeId!!,
                    if (u.labels != null) TextsByLanguage.shown(u.labels, languages) ?: u.universeId!! else u.name!!,
                    if (u.labels != null) TextsByLanguage.shown(u.descriptions, languages) else u.description,
                    u.url!!,
                    u.addedAt!!,
                    u.refreshedAt!!,
                    entries.count { it.included },
                    entries.size,
                    u.languages?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
                    u.properties?.split(',')?.filter { it.isNotEmpty() }.orEmpty().toSet(),
                    local[UniverseFacet.valueOf(u.catalogue!!, u.universeId!!)],
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun findEntry(catalogue: String, id: String): StoredUniverse? =
        allEntries().firstOrNull { it.catalogue == catalogue && it.id == id }

    /**
     * Adds the universe, or refreshes it: its name and every entry replaced by what the catalogue says now, with the
     * [classes] of the entries and their statements of [properties], the ones asked for; the user's words on its
     * classes and entries stay.
     */
    fun save(
        catalogue: String,
        entry: UniverseEntry,
        characters: List<UniverseCharacter>,
        classes: List<UniverseClass>,
        properties: Set<String>,
        now: Instant,
    ) = fromCatalogue.write(catalogue, entry, characters, classes, properties, now)

    /** Adds the entry, or refreshes it, its characters not read: those kept before stay as they are. */
    fun saveEntry(catalogue: String, entry: UniverseEntry, properties: Set<String>, now: Instant) =
        fromCatalogue.writeEntry(catalogue, entry, properties, now)

    /** Every statement the dictionary keeps of its entries and the works within them: entry value, property, value. */
    fun universeStatements(): List<Triple<String, String, String>> =
        db.select(
                UNIVERSE_PROPERTY.CATALOGUE,
                UNIVERSE_PROPERTY.UNIVERSE_ID,
                UNIVERSE_PROPERTY.PROPERTY,
                UNIVERSE_PROPERTY.PROPERTY_VALUE,
            )
            .from(UNIVERSE_PROPERTY)
            .fetch { Triple(UniverseFacet.valueOf(it.value1()!!, it.value2()!!), it.value3()!!, it.value4()!!) }

    /** Every statement the dictionary keeps of the characters of its universes: character value, property, value. */
    fun characterStatements(): List<Triple<String, String, String>> =
        db.selectDistinct(
                UNIVERSE_CHARACTER_PROPERTY.CATALOGUE,
                UNIVERSE_CHARACTER_PROPERTY.CHARACTER_ID,
                UNIVERSE_CHARACTER_PROPERTY.PROPERTY,
                UNIVERSE_CHARACTER_PROPERTY.PROPERTY_VALUE,
            )
            .from(UNIVERSE_CHARACTER_PROPERTY)
            .fetch { Triple(UniverseFacet.valueOf(it.value1()!!, it.value2()!!), it.value3()!!, it.value4()!!) }

    /**
     * The entries every character of the dictionary is of: character value to entry values; the universes of them of
     * [FandomDictionary.universesOfCharacters].
     */
    fun characterEntries(): Map<String, Set<String>> =
        db.select(UNIVERSE_CHARACTER.CATALOGUE, UNIVERSE_CHARACTER.CHARACTER_ID, UNIVERSE_CHARACTER.UNIVERSE_ID)
            .from(UNIVERSE_CHARACTER)
            .fetch()
            .groupBy(
                { UniverseFacet.valueOf(it.value1()!!, it.value2()!!) },
                { UniverseFacet.valueOf(it.value1()!!, it.value3()!!) },
            )
            .mapValues { it.value.toSet() }

    /**
     * The universes every character of the dictionary is of: character value to universe values, of the universes whose
     * characters are read ([FandomDictionary.universesOfCharacters]).
     */
    fun characterUniverses(dictionary: FandomDictionary = dictionary()): Map<String, Set<String>> =
        dictionary.universesOfCharacters(characterEntries())
}
