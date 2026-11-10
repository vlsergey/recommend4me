package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM_ENTRY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE ENTRIES OF THE CATALOGUE THE NORMALIZED FANDOMS ARE BOUND TO, in the user's dictionaries ([db]); the entries
 * themselves, as the catalogue gave them, kept in [UniverseRepository].
 */
class FandomEntries(private val db: DSLContext) {

    /** Every normalized fandom's entries, in the order bound. */
    fun all(): Map<Long, List<UniverseKey>> =
        db.selectFrom(FANDOM_ENTRY)
            .orderBy(FANDOM_ENTRY.BOUND_AT, FANDOM_ENTRY.ENTRY)
            .fetch()
            .groupBy({ it.fandom!! }, { UniverseKey(it.catalogue!!, it.entry!!) })

    /** The entries of the fandom [id], in the order bound. */
    fun of(id: Long): List<UniverseKey> =
        db.selectFrom(FANDOM_ENTRY).where(FANDOM_ENTRY.FANDOM.eq(id)).orderBy(FANDOM_ENTRY.BOUND_AT).fetch {
            UniverseKey(it.catalogue!!, it.entry!!)
        }

    /** The normalized fandoms bound to the entry [key]. */
    fun fandomsOf(key: UniverseKey): List<Long> =
        db.select(FANDOM_ENTRY.FANDOM)
            .from(FANDOM_ENTRY)
            .where(FANDOM_ENTRY.CATALOGUE.eq(key.catalogue), FANDOM_ENTRY.ENTRY.eq(key.id))
            .fetch { it.value1()!! }

    /** Whether a fandom bound to the entry [key] reads its characters: the entry is read with them. */
    fun charactersRead(key: UniverseKey): Boolean =
        db.fetchExists(
            db.select(FANDOM_ENTRY.FANDOM)
                .from(FANDOM_ENTRY)
                .join(FANDOM)
                .on(FANDOM.ID.eq(FANDOM_ENTRY.FANDOM))
                .where(
                    FANDOM_ENTRY.CATALOGUE.eq(key.catalogue),
                    FANDOM_ENTRY.ENTRY.eq(key.id),
                    FANDOM.CHARACTERS.isTrue,
                )
        )

    /** The fandom [id] bound to the entry [key], kept in the dictionary already ([UniverseRepository.save]). */
    fun bind(id: Long, key: UniverseKey, now: Instant) {
        val row =
            DSL.and(FANDOM_ENTRY.FANDOM.eq(id), FANDOM_ENTRY.CATALOGUE.eq(key.catalogue), FANDOM_ENTRY.ENTRY.eq(key.id))
        if (db.fetchExists(FANDOM_ENTRY, row)) return
        db.insertInto(FANDOM_ENTRY)
            .set(FANDOM_ENTRY.FANDOM, id)
            .set(FANDOM_ENTRY.CATALOGUE, key.catalogue)
            .set(FANDOM_ENTRY.ENTRY, key.id)
            .set(FANDOM_ENTRY.BOUND_AT, now)
            .execute()
    }

    /**
     * The fandom [id] no more bound to the entry [key]; the entry, bound to no fandom then, gone from the dictionary
     * with its characters.
     */
    fun unbind(id: Long, key: UniverseKey) {
        db.deleteFrom(FANDOM_ENTRY)
            .where(FANDOM_ENTRY.FANDOM.eq(id), FANDOM_ENTRY.CATALOGUE.eq(key.catalogue), FANDOM_ENTRY.ENTRY.eq(key.id))
            .execute()
        if (fandomsOf(key).isEmpty()) {
            db.deleteFrom(UNIVERSE)
                .where(UNIVERSE.CATALOGUE.eq(key.catalogue), UNIVERSE.UNIVERSE_ID.eq(key.id))
                .execute()
        }
    }
}
