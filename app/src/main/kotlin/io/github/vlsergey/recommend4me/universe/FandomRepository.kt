package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM_ENTRY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM_SITE
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * A normalized fandom as kept: the name the user gave it, if any, the name worked out of its sites' fandoms
 * ([calculatedName]) and whether the characters of its entries are read ([characters]).
 */
class FandomRow(val id: Long, val name: String?, val calculatedName: String?, val characters: Boolean)

/**
 * THE NORMALIZED FANDOMS — THE UNIVERSES — in the user's dictionaries ([db]): every one an id, the name the user gave
 * it and whether the characters of its entries are read. Every fandom of every site bound to exactly one of them is
 * kept by [sites], the entries of a catalogue each is bound to by [entries] (the entries themselves, as the catalogue
 * gave them, in [UniverseRepository]), one under another and two told apart by [relations].
 *
 * A normalized fandom is known to the application — as a value of the works' universes, in the user's answers — by its
 * id, written as a text ([value]).
 */
class FandomRepository(private val db: DSLContext) {

    /** The sites' fandoms and the normalized fandoms they are bound to. */
    val sites = FandomSites(db)

    /** The entries of the catalogue the normalized fandoms are bound to. */
    val entries = FandomEntries(db)

    /** One under another, two told apart. */
    val relations = FandomRelations(db)

    /**
     * The writes of one action of the user on the fandoms — every one of them, or, failed, none: the repository [block]
     * is given writes in one transaction.
     */
    fun <T> transaction(block: (FandomRepository) -> T): T = db.transactionResult { c ->
        block(FandomRepository(DSL.using(c)))
    }

    fun all(): List<FandomRow> =
        db.selectFrom(FANDOM).fetch { FandomRow(it.id!!, it.name, it.calculatedName, it.characters!!) }

    fun one(id: Long): FandomRow? =
        db.selectFrom(FANDOM).where(FANDOM.ID.eq(id)).fetchOne {
            FandomRow(it.id!!, it.name, it.calculatedName, it.characters!!)
        }

    fun exists(id: Long): Boolean = db.fetchExists(FANDOM, FANDOM.ID.eq(id))

    /** A new normalized fandom, its characters read when [characters]; its id. */
    fun create(now: Instant, characters: Boolean = false, calculatedName: String? = null): Long =
        db.insertInto(FANDOM)
            .set(FANDOM.CHARACTERS, characters)
            .set(FANDOM.CALCULATED_NAME, calculatedName?.take(NAME_WIDTH))
            .set(FANDOM.MADE_AT, now)
            .returningResult(FANDOM.ID)
            .fetchOne()!!
            .value1()!!

    /** The name the user gives the fandom [id]; null takes it back. */
    fun name(id: Long, name: String?) {
        db.update(FANDOM).set(FANDOM.NAME, name?.take(NAME_WIDTH)).where(FANDOM.ID.eq(id)).execute()
    }

    /** The name worked out of the sites' fandoms of the fandom [id]. */
    fun calculatedName(id: Long, name: String?) {
        db.update(FANDOM).set(FANDOM.CALCULATED_NAME, name?.take(NAME_WIDTH)).where(FANDOM.ID.eq(id)).execute()
    }

    /** Whether the characters of the entries of the fandom [id] are read and given to its works. */
    fun characters(id: Long, read: Boolean) {
        db.update(FANDOM).set(FANDOM.CHARACTERS, read).where(FANDOM.ID.eq(id)).execute()
    }

    /**
     * What was of the fandom [from] — its sites' fandoms, its entries, its parents and children, the pairs told apart —
     * of [into], and [from] gone.
     */
    fun merge(from: Long, into: Long, now: Instant) {
        db.update(FANDOM_SITE)
            .set(FANDOM_SITE.FANDOM, into)
            .set(FANDOM_SITE.BOUND_AT, now)
            .where(FANDOM_SITE.FANDOM.eq(from))
            .execute()
        entries.of(from).forEach { entries.bind(into, it, now) }
        relations.move(from, into, now)
        db.deleteFrom(FANDOM_ENTRY).where(FANDOM_ENTRY.FANDOM.eq(from)).execute()
        db.deleteFrom(FANDOM).where(FANDOM.ID.eq(from)).execute()
    }

    /** The fandom [id] gone, with whatever entries of a catalogue it had; its sites' fandoms are let go of before. */
    fun drop(id: Long) {
        db.deleteFrom(FANDOM_ENTRY).where(FANDOM_ENTRY.FANDOM.eq(id)).execute()
        db.deleteFrom(FANDOM).where(FANDOM.ID.eq(id)).execute()
    }

    companion object {
        /** A normalized fandom as a value of the works' universes: its id written out. */
        fun value(id: Long): String = id.toString()

        /** The id of a value of the works' universes; null for one that is no normalized fandom. */
        fun idOf(value: String): Long? = value.toLongOrNull()

        /** A pair of fandoms, the smaller id first. */
        fun pair(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a

        /** The widest name kept: the width of the columns. */
        const val NAME_WIDTH = 1000
    }
}
