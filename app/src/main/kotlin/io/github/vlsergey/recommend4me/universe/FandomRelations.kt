package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM_DISTINCT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.FANDOM_PARENT
import java.time.Instant
import org.jooq.DSLContext

/**
 * WHAT THE USER SAID ONE NORMALIZED FANDOM IS TO ANOTHER, in the user's dictionaries ([db]): one under another — a work
 * of the child is of the parent too — and two told apart, never offered as one again.
 */
class FandomRelations(private val db: DSLContext) {

    /** Every fandom's parents: child to parents. */
    fun parents(): Map<Long, Set<Long>> =
        db.select(FANDOM_PARENT.CHILD, FANDOM_PARENT.PARENT)
            .from(FANDOM_PARENT)
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })
            .mapValues { it.value.toSet() }

    fun link(child: Long, parent: Long, now: Instant) {
        if (db.fetchExists(FANDOM_PARENT, FANDOM_PARENT.CHILD.eq(child), FANDOM_PARENT.PARENT.eq(parent))) return
        db.insertInto(FANDOM_PARENT)
            .set(FANDOM_PARENT.CHILD, child)
            .set(FANDOM_PARENT.PARENT, parent)
            .set(FANDOM_PARENT.LINKED_AT, now)
            .execute()
    }

    fun unlink(child: Long, parent: Long) {
        db.deleteFrom(FANDOM_PARENT).where(FANDOM_PARENT.CHILD.eq(child), FANDOM_PARENT.PARENT.eq(parent)).execute()
    }

    /** Every pair the user told apart, the smaller id first. */
    fun distinct(): Set<Pair<Long, Long>> =
        db.select(FANDOM_DISTINCT.FIRST_FANDOM, FANDOM_DISTINCT.OTHER_FANDOM).from(FANDOM_DISTINCT).fetchSet {
            it.value1()!! to it.value2()!!
        }

    fun tellApart(a: Long, b: Long, now: Instant) {
        val (first, other) = FandomRepository.pair(a, b)
        val said = pairRow(first, other)
        if (db.fetchExists(FANDOM_DISTINCT, said)) return
        db.insertInto(FANDOM_DISTINCT)
            .set(FANDOM_DISTINCT.FIRST_FANDOM, first)
            .set(FANDOM_DISTINCT.OTHER_FANDOM, other)
            .set(FANDOM_DISTINCT.SAID_AT, now)
            .execute()
    }

    fun forgetApart(a: Long, b: Long) {
        val (first, other) = FandomRepository.pair(a, b)
        db.deleteFrom(FANDOM_DISTINCT).where(pairRow(first, other)).execute()
    }

    /** The links and the pairs told apart of the fandom [from] made of [into]: what a merge keeps of it. */
    fun move(from: Long, into: Long, now: Instant) {
        parents().forEach { (child, of) ->
            if (child == from) of.filter { it != into }.forEach { link(into, it, now) }
            if (from in of && child != into) link(child, into, now)
        }
        distinct().forEach { (a, b) ->
            if (a == from && b != into) tellApart(into, b, now)
            if (b == from && a != into) tellApart(a, into, now)
        }
    }

    private companion object {
        /** The row of the pair [first], [other] told apart. */
        fun pairRow(first: Long, other: Long) =
            FANDOM_DISTINCT.FIRST_FANDOM.eq(first).and(FANDOM_DISTINCT.OTHER_FANDOM.eq(other))
    }
}
