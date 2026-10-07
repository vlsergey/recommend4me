package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.UNIVERSE
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.UNIVERSE_CHARACTER
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.UNIVERSE_CHARACTER_CHOICE
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.UNIVERSE_CHARACTER_CLASS
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.UNIVERSE_CLASS
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.UNIVERSE_CLASS_CHOICE
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** A universe of the dictionary, with how many of its entries are kept as characters, of how many. */
class StoredUniverse(
    val catalogue: String,
    val id: String,
    val name: String,
    val description: String?,
    val url: String,
    val addedAt: Instant,
    val refreshedAt: Instant,
    val characters: Int,
    val entries: Int,
) {
    val value: String get() = UniverseFacet.valueOf(catalogue, id)
}

/**
 * An entry of a universe of the dictionary, whether it is kept as a character ([included]), and
 * the user's word on it alone ([choice]; null when its classes decide).
 */
class KeptEntry(val character: UniverseCharacter, val included: Boolean, val choice: Boolean?)

/**
 * A class the entries of a universe are of: whether its entries are characters, how many of the
 * universe's entries are of it, whether they are kept ([included]) and the user's word on it
 * ([choice]; null when it is kept as the catalogue says — kept when of characters).
 */
class ClassInUniverse(val id: String, val name: String, val character: Boolean, val entries: Int, val included: Boolean, val choice: Boolean?)

/**
 * The dictionary of universes the user agreed on and their entries, in the type's corrections
 * database. AN ENTRY IS KEPT AS A CHARACTER by the user's word on it; else when any of its classes
 * is kept — a class by the user's word on it within the universe, else when its entries are
 * characters; an entry the catalogue gave no class is kept: nothing says it is not one.
 */
class UniverseRepository(private val db: DSLContext) {

    fun all(): List<StoredUniverse> = db.selectFrom(UNIVERSE).orderBy(UNIVERSE.NAME).fetch().map { u ->
        val entries = entries(u.catalogue!!, u.universeId!!)
        StoredUniverse(u.catalogue!!, u.universeId!!, u.name!!, u.description, u.url!!, u.addedAt!!, u.refreshedAt!!, entries.count { it.included }, entries.size)
    }

    fun find(catalogue: String, id: String): StoredUniverse? = all().firstOrNull { it.catalogue == catalogue && it.id == id }

    /**
     * Adds the universe, or refreshes it: its name and every entry replaced by what the catalogue
     * says now, with the [classes] of the entries; the user's words on its classes and entries stay.
     */
    fun save(catalogue: String, entry: UniverseEntry, characters: List<UniverseCharacter>, classes: List<UniverseClass>, now: Instant) {
        db.transaction { tx ->
            val t = tx.dsl()
            t.insertInto(UNIVERSE)
                .set(UNIVERSE.CATALOGUE, catalogue).set(UNIVERSE.UNIVERSE_ID, entry.id).set(UNIVERSE.NAME, entry.name.take(1000))
                .set(UNIVERSE.DESCRIPTION, entry.description?.take(2000)).set(UNIVERSE.URL, entry.url.take(2000))
                .set(UNIVERSE.ADDED_AT, now).set(UNIVERSE.REFRESHED_AT, now)
                .onDuplicateKeyUpdate()
                .set(UNIVERSE.NAME, entry.name.take(1000)).set(UNIVERSE.DESCRIPTION, entry.description?.take(2000))
                .set(UNIVERSE.URL, entry.url.take(2000)).set(UNIVERSE.REFRESHED_AT, now)
                .execute()
            t.deleteFrom(UNIVERSE_CHARACTER).where(UNIVERSE_CHARACTER.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER.UNIVERSE_ID.eq(entry.id)).execute()
            characters.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { c ->
                    t.insertInto(UNIVERSE_CHARACTER)
                        .set(UNIVERSE_CHARACTER.CATALOGUE, catalogue).set(UNIVERSE_CHARACTER.UNIVERSE_ID, entry.id)
                        .set(UNIVERSE_CHARACTER.CHARACTER_ID, c.id).set(UNIVERSE_CHARACTER.NAMES, c.names.joinToString("\n"))
                        .set(UNIVERSE_CHARACTER.DESCRIPTION, c.description?.take(2000)).set(UNIVERSE_CHARACTER.URL, c.url.take(2000))
                }).execute()
            }
            characters.flatMap { c -> c.classes.distinct().map { c.id to it } }.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { (character, cls) ->
                    t.insertInto(UNIVERSE_CHARACTER_CLASS)
                        .set(UNIVERSE_CHARACTER_CLASS.CATALOGUE, catalogue).set(UNIVERSE_CHARACTER_CLASS.UNIVERSE_ID, entry.id)
                        .set(UNIVERSE_CHARACTER_CLASS.CHARACTER_ID, character).set(UNIVERSE_CHARACTER_CLASS.CLASS_ID, cls)
                }).execute()
            }
            classes.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { c ->
                    t.insertInto(UNIVERSE_CLASS)
                        .set(UNIVERSE_CLASS.CATALOGUE, catalogue).set(UNIVERSE_CLASS.CLASS_ID, c.id)
                        .set(UNIVERSE_CLASS.NAME, c.name.take(1000)).set(UNIVERSE_CLASS.IS_CHARACTER, c.character)
                        .onDuplicateKeyUpdate()
                        .set(UNIVERSE_CLASS.NAME, c.name.take(1000)).set(UNIVERSE_CLASS.IS_CHARACTER, c.character)
                }).execute()
            }
        }
    }

    fun remove(catalogue: String, id: String) {
        db.deleteFrom(UNIVERSE).where(UNIVERSE.CATALOGUE.eq(catalogue), UNIVERSE.UNIVERSE_ID.eq(id)).execute()
    }

    /** The entries of the universe kept as characters. */
    fun characters(catalogue: String, id: String): List<UniverseCharacter> = entries(catalogue, id).filter { it.included }.map { it.character }

    /** Every entry of the universe, kept or not, by name. */
    fun entries(catalogue: String, id: String): List<KeptEntry> {
        val classes = classesOf(catalogue, id)
        val ofEntry = HashMap<String, MutableList<String>>()
        db.select(UNIVERSE_CHARACTER_CLASS.CHARACTER_ID, UNIVERSE_CHARACTER_CLASS.CLASS_ID).from(UNIVERSE_CHARACTER_CLASS)
            .where(UNIVERSE_CHARACTER_CLASS.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER_CLASS.UNIVERSE_ID.eq(id))
            .fetch { ofEntry.getOrPut(it.value1()!!) { ArrayList() } += it.value2()!! }
        val choices = db.select(UNIVERSE_CHARACTER_CHOICE.CHARACTER_ID, UNIVERSE_CHARACTER_CHOICE.INCLUDED).from(UNIVERSE_CHARACTER_CHOICE)
            .where(UNIVERSE_CHARACTER_CHOICE.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER_CHOICE.UNIVERSE_ID.eq(id))
            .fetchMap({ it.value1()!! }, { it.value2()!! })
        val kept = classes.mapValues { (_, c) -> c.included }
        return db.selectFrom(UNIVERSE_CHARACTER).where(UNIVERSE_CHARACTER.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER.UNIVERSE_ID.eq(id))
            .fetch { r ->
                val own = ofEntry[r.characterId!!].orEmpty()
                val character = UniverseCharacter(r.characterId!!, r.names!!.split('\n'), r.description, r.url!!, own)
                val choice = choices[r.characterId!!]
                KeptEntry(character, choice ?: (own.isEmpty() || own.any { kept[it] == true }), choice)
            }
            .sortedBy { it.character.names.first().lowercase() }
    }

    /** Every class the entries of the universe are of, the most entries first. */
    fun classes(catalogue: String, id: String): List<ClassInUniverse> = classesOf(catalogue, id).values.sortedByDescending { it.entries }

    private fun classesOf(catalogue: String, id: String): Map<String, ClassInUniverse> {
        val counts = db.select(UNIVERSE_CHARACTER_CLASS.CLASS_ID, DSL.count()).from(UNIVERSE_CHARACTER_CLASS)
            .where(UNIVERSE_CHARACTER_CLASS.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER_CLASS.UNIVERSE_ID.eq(id))
            .groupBy(UNIVERSE_CHARACTER_CLASS.CLASS_ID)
            .fetchMap({ it.value1()!! }, { it.value2()!! })
        if (counts.isEmpty()) return emptyMap()
        val known = db.selectFrom(UNIVERSE_CLASS).where(UNIVERSE_CLASS.CATALOGUE.eq(catalogue), UNIVERSE_CLASS.CLASS_ID.`in`(counts.keys))
            .fetchMap({ it.classId!! }, { it })
        val choices = db.select(UNIVERSE_CLASS_CHOICE.CLASS_ID, UNIVERSE_CLASS_CHOICE.INCLUDED).from(UNIVERSE_CLASS_CHOICE)
            .where(UNIVERSE_CLASS_CHOICE.CATALOGUE.eq(catalogue), UNIVERSE_CLASS_CHOICE.UNIVERSE_ID.eq(id))
            .fetchMap({ it.value1()!! }, { it.value2()!! })
        return counts.mapValues { (cls, n) ->
            val character = known[cls]?.isCharacter ?: false
            val choice = choices[cls]
            ClassInUniverse(cls, known[cls]?.name ?: cls, character, n, choice ?: character, choice)
        }
    }

    /** The user's word on a class within the universe: its entries kept, or left out; null takes it back. */
    fun chooseClass(catalogue: String, id: String, cls: String, included: Boolean?) {
        db.deleteFrom(UNIVERSE_CLASS_CHOICE)
            .where(UNIVERSE_CLASS_CHOICE.CATALOGUE.eq(catalogue), UNIVERSE_CLASS_CHOICE.UNIVERSE_ID.eq(id), UNIVERSE_CLASS_CHOICE.CLASS_ID.eq(cls))
            .execute()
        if (included != null) {
            db.insertInto(UNIVERSE_CLASS_CHOICE)
                .set(UNIVERSE_CLASS_CHOICE.CATALOGUE, catalogue).set(UNIVERSE_CLASS_CHOICE.UNIVERSE_ID, id)
                .set(UNIVERSE_CLASS_CHOICE.CLASS_ID, cls).set(UNIVERSE_CLASS_CHOICE.INCLUDED, included)
                .execute()
        }
    }

    /** The user's word on one entry of the universe, over its classes; null takes it back. */
    fun chooseEntry(catalogue: String, id: String, character: String, included: Boolean?) {
        db.deleteFrom(UNIVERSE_CHARACTER_CHOICE)
            .where(UNIVERSE_CHARACTER_CHOICE.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER_CHOICE.UNIVERSE_ID.eq(id), UNIVERSE_CHARACTER_CHOICE.CHARACTER_ID.eq(character))
            .execute()
        if (included != null) {
            db.insertInto(UNIVERSE_CHARACTER_CHOICE)
                .set(UNIVERSE_CHARACTER_CHOICE.CATALOGUE, catalogue).set(UNIVERSE_CHARACTER_CHOICE.UNIVERSE_ID, id)
                .set(UNIVERSE_CHARACTER_CHOICE.CHARACTER_ID, character).set(UNIVERSE_CHARACTER_CHOICE.INCLUDED, included)
                .execute()
        }
    }

    companion object {
        private const val BATCH = 1000
    }
}
