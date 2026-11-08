package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER_CLASS
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER_PROPERTY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CLASS
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_PROPERTY
import io.github.vlsergey.recommend4me.universe.TextsByLanguage.encode
import io.github.vlsergey.recommend4me.universe.TextsByLanguage.encodeAll
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.Query

/**
 * A UNIVERSE AS ITS CATALOGUE GIVES IT NOW, WRITTEN INTO THE DICTIONARY: its name and every entry replaced by what the
 * catalogue says, with the classes of the entries and their statements of the properties asked for; the user's words on
 * its classes and entries are kept apart and stay.
 */
internal class UniverseFromCatalogue(private val db: DSLContext) {

    /**
     * Adds the universe, or refreshes it: its name and every entry replaced by what the catalogue says now, with the
     * [classes] of the entries and their statements of [properties], the ones asked for.
     */
    fun write(
        catalogue: String,
        entry: UniverseEntry,
        characters: List<UniverseCharacter>,
        classes: List<UniverseClass>,
        properties: Set<String>,
        now: Instant,
    ) {
        db.transaction { tx ->
            val t = tx.dsl()
            writeUniverse(t, catalogue, entry, properties, now)
            writeStatements(t, catalogue, entry)
            writeCharacters(t, catalogue, entry.id, characters)
            writeCharacterStatements(t, catalogue, entry.id, characters)
            writeCharacterClasses(t, catalogue, entry.id, characters)
            writeClasses(t, catalogue, classes)
        }
    }

    /** The universe's own row: its texts, the languages and the [properties] it was asked in and for. */
    private fun writeUniverse(
        t: DSLContext,
        catalogue: String,
        entry: UniverseEntry,
        properties: Set<String>,
        now: Instant,
    ) {
        val asked = entry.languages.joinToString(",")
        val askedProperties = properties.sorted().joinToString(",")
        t.insertInto(UNIVERSE)
            .set(UNIVERSE.CATALOGUE, catalogue)
            .set(UNIVERSE.UNIVERSE_ID, entry.id)
            .set(UNIVERSE.NAME, entry.name.take(NAME_WIDTH))
            .set(UNIVERSE.DESCRIPTION, entry.description?.take(TEXT_WIDTH))
            .set(UNIVERSE.URL, entry.url.take(TEXT_WIDTH))
            .set(UNIVERSE.LABELS, encode(entry.labels))
            .set(UNIVERSE.DESCRIPTIONS, encode(entry.descriptions))
            .set(UNIVERSE.LANGUAGES, asked)
            .set(UNIVERSE.PROPERTIES, askedProperties)
            .set(UNIVERSE.ADDED_AT, now)
            .set(UNIVERSE.REFRESHED_AT, now)
            .onDuplicateKeyUpdate()
            .set(UNIVERSE.NAME, entry.name.take(NAME_WIDTH))
            .set(UNIVERSE.DESCRIPTION, entry.description?.take(TEXT_WIDTH))
            .set(UNIVERSE.LABELS, encode(entry.labels))
            .set(UNIVERSE.DESCRIPTIONS, encode(entry.descriptions))
            .set(UNIVERSE.LANGUAGES, asked)
            .set(UNIVERSE.PROPERTIES, askedProperties)
            .set(UNIVERSE.URL, entry.url.take(TEXT_WIDTH))
            .set(UNIVERSE.REFRESHED_AT, now)
            .execute()
    }

    /** The statements of the universe and of the works within it, replaced. */
    private fun writeStatements(t: DSLContext, catalogue: String, entry: UniverseEntry) {
        t.deleteFrom(UNIVERSE_PROPERTY)
            .where(UNIVERSE_PROPERTY.CATALOGUE.eq(catalogue), UNIVERSE_PROPERTY.UNIVERSE_ID.eq(entry.id))
            .execute()
        val fields =
            listOf(
                UNIVERSE_PROPERTY.CATALOGUE,
                UNIVERSE_PROPERTY.UNIVERSE_ID,
                UNIVERSE_PROPERTY.PROPERTY,
                UNIVERSE_PROPERTY.PROPERTY_VALUE,
            )
        bound(
            t,
            t.insertInto(UNIVERSE_PROPERTY, fields).values(fields.map { null }),
            entry.properties.flatMap { (p, values) ->
                values.distinct().map { listOf(catalogue, entry.id, p, it.take(TEXT_WIDTH)) }
            },
        )
    }

    /**
     * The [rows] written by the one [statement], prepared once and bound a row each: not each row's own statement
     * parsed by the database apart.
     */
    private fun bound(t: DSLContext, statement: Query, rows: List<List<Any?>>) {
        rows.chunked(BATCH).forEach { chunk ->
            val batch = t.batch(statement)
            chunk.forEach { batch.bind(*it.toTypedArray()) }
            batch.execute()
        }
    }

    /**
     * Every entry of the universe [universe] replaced by the [characters]; with them go their statements and classes,
     * written after.
     */
    private fun writeCharacters(
        t: DSLContext,
        catalogue: String,
        universe: String,
        characters: List<UniverseCharacter>,
    ) {
        t.deleteFrom(UNIVERSE_CHARACTER)
            .where(UNIVERSE_CHARACTER.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER.UNIVERSE_ID.eq(universe))
            .execute()
        val fields =
            listOf(
                UNIVERSE_CHARACTER.CATALOGUE,
                UNIVERSE_CHARACTER.UNIVERSE_ID,
                UNIVERSE_CHARACTER.CHARACTER_ID,
                UNIVERSE_CHARACTER.NAMES,
                UNIVERSE_CHARACTER.DESCRIPTION,
                UNIVERSE_CHARACTER.URL,
                UNIVERSE_CHARACTER.SEX,
                UNIVERSE_CHARACTER.LABELS,
                UNIVERSE_CHARACTER.ALIASES,
                UNIVERSE_CHARACTER.DESCRIPTIONS,
            )
        bound(
            t,
            t.insertInto(UNIVERSE_CHARACTER, fields).values(fields.map { null }),
            characters.map { c ->
                listOf(
                    catalogue,
                    universe,
                    c.id,
                    c.names.joinToString("\n"),
                    c.description?.take(TEXT_WIDTH),
                    c.url.take(TEXT_WIDTH),
                    c.sex?.name,
                    encode(c.labels),
                    encodeAll(c.aliases),
                    encode(c.descriptions),
                )
            },
        )
    }

    /** The statements of every entry of the universe [universe]: an entry given twice states them once. */
    private fun writeCharacterStatements(
        t: DSLContext,
        catalogue: String,
        universe: String,
        characters: List<UniverseCharacter>,
    ) {
        val fields =
            listOf(
                UNIVERSE_CHARACTER_PROPERTY.CATALOGUE,
                UNIVERSE_CHARACTER_PROPERTY.UNIVERSE_ID,
                UNIVERSE_CHARACTER_PROPERTY.CHARACTER_ID,
                UNIVERSE_CHARACTER_PROPERTY.PROPERTY,
                UNIVERSE_CHARACTER_PROPERTY.PROPERTY_VALUE,
            )
        bound(
            t,
            t.insertInto(UNIVERSE_CHARACTER_PROPERTY, fields).values(fields.map { null }),
            characters
                .distinctBy { it.id }
                .flatMap { c ->
                    c.properties.flatMap { (p, values) ->
                        values.distinct().map { listOf(catalogue, universe, c.id, p, it.take(TEXT_WIDTH)) }
                    }
                },
        )
    }

    /** The classes every entry of the universe [universe] is of. */
    private fun writeCharacterClasses(
        t: DSLContext,
        catalogue: String,
        universe: String,
        characters: List<UniverseCharacter>,
    ) {
        val fields =
            listOf(
                UNIVERSE_CHARACTER_CLASS.CATALOGUE,
                UNIVERSE_CHARACTER_CLASS.UNIVERSE_ID,
                UNIVERSE_CHARACTER_CLASS.CHARACTER_ID,
                UNIVERSE_CHARACTER_CLASS.CLASS_ID,
            )
        bound(
            t,
            t.insertInto(UNIVERSE_CHARACTER_CLASS, fields).values(fields.map { null }),
            characters.flatMap { c -> c.classes.distinct().map { listOf(catalogue, universe, c.id, it) } },
        )
    }

    /** The [classes] of the catalogue, named, each with whether its entries are characters: added or refreshed. */
    private fun writeClasses(t: DSLContext, catalogue: String, classes: List<UniverseClass>) {
        val fields =
            listOf(
                UNIVERSE_CLASS.CATALOGUE,
                UNIVERSE_CLASS.CLASS_ID,
                UNIVERSE_CLASS.NAME,
                UNIVERSE_CLASS.IS_CHARACTER,
                UNIVERSE_CLASS.LABELS,
            )
        bound(
            t,
            t.mergeInto(UNIVERSE_CLASS, fields)
                .key(UNIVERSE_CLASS.CATALOGUE, UNIVERSE_CLASS.CLASS_ID)
                .values(fields.map { null }),
            classes.map { c -> listOf(catalogue, c.id, c.name.take(NAME_WIDTH), c.character, encode(c.labels)) },
        )
    }

    private companion object {
        /** The rows of a batch of inserts: memory, not meaning. */
        const val BATCH = 1000

        /** The width of the columns of a name (VARCHAR(1000)): a longer one is cut. */
        const val NAME_WIDTH = 1000

        /**
         * The width of the columns of a description, an address and a statement's value (VARCHAR(2000)): a longer one
         * is cut.
         */
        const val TEXT_WIDTH = 2000
    }
}
