package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.UNIVERSE
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.UNIVERSE_CHARACTER
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** A universe of the dictionary, with how many characters it has. */
class StoredUniverse(
    val catalogue: String,
    val id: String,
    val name: String,
    val description: String?,
    val url: String,
    val addedAt: Instant,
    val refreshedAt: Instant,
    val characters: Int,
) {
    val value: String get() = UniverseFacet.valueOf(catalogue, id)
}

/** The dictionary of universes the user agreed on and their characters, in the type's corrections database. */
class UniverseRepository(private val db: DSLContext) {

    fun all(): List<StoredUniverse> {
        val counts = db.select(UNIVERSE_CHARACTER.CATALOGUE, UNIVERSE_CHARACTER.UNIVERSE_ID, DSL.count()).from(UNIVERSE_CHARACTER)
            .groupBy(UNIVERSE_CHARACTER.CATALOGUE, UNIVERSE_CHARACTER.UNIVERSE_ID)
            .fetchMap({ it.value1()!! to it.value2()!! }, { it.value3()!! })
        return db.selectFrom(UNIVERSE).orderBy(UNIVERSE.NAME).fetch { u ->
            StoredUniverse(u.catalogue!!, u.universeId!!, u.name!!, u.description, u.url!!, u.addedAt!!, u.refreshedAt!!, counts[u.catalogue!! to u.universeId!!] ?: 0)
        }
    }

    fun find(catalogue: String, id: String): StoredUniverse? = all().firstOrNull { it.catalogue == catalogue && it.id == id }

    /** Adds the universe, or refreshes it: its name and every character replaced by what the catalogue says now. */
    fun save(catalogue: String, entry: UniverseEntry, characters: List<UniverseCharacter>, now: Instant) {
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
        }
    }

    fun remove(catalogue: String, id: String) {
        db.deleteFrom(UNIVERSE).where(UNIVERSE.CATALOGUE.eq(catalogue), UNIVERSE.UNIVERSE_ID.eq(id)).execute()
    }

    fun characters(catalogue: String, id: String): List<UniverseCharacter> =
        db.selectFrom(UNIVERSE_CHARACTER).where(UNIVERSE_CHARACTER.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER.UNIVERSE_ID.eq(id))
            .fetch { UniverseCharacter(it.characterId!!, it.names!!.split('\n'), it.description, it.url!!) }
            .sortedBy { it.names.first().lowercase() }

    companion object {
        private const val BATCH = 1000
    }
}
