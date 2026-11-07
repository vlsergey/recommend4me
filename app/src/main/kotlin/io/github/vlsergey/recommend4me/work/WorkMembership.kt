package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK_RELATION
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FACET_COMPLETE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FACET_CORRECTION
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FACET_UNKNOWN
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FIELD_CORRECTION
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_INTEREST
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_RATING
import io.github.vlsergey.recommend4me.item.ItemKey
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * The items a normalized work is made of, changed — two works merged into one, an item taken out to a work of its own,
 * an item gone from the library — and what goes with a work as it is: the user's word on it and on its pairs, its
 * grades, its mark of interest. Nothing the user said is lost.
 */
class WorkMembership(private val db: DSLContext) {

    /**
     * The normalized work [from] merged into [into]: every item of it bound to [into], the words on it moved to [into]
     * — a word [into] has on the same work kept, none of [into] with itself — its grades, mark and corrections too, the
     * newest of the two the one that stands — of the [single] facets one value — and [from] gone.
     */
    fun merge(from: Long, into: Long, now: Instant, single: Set<String> = emptySet()) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.update(SITE_WORK)
            .set(SITE_WORK.NORMALIZED, into)
            .set(SITE_WORK.BOUND_AT, now)
            .where(SITE_WORK.NORMALIZED.eq(from))
            .execute()
        moveRelations(tx, from, into, now)
        tx.update(WORK_RATING).set(WORK_RATING.WORK, into).where(WORK_RATING.WORK.eq(from)).execute()
        keepNewestGrade(tx, into)
        val i = WORK_INTEREST
        val marks = tx.selectFrom(i).where(i.WORK.`in`(from, into)).fetch()
        marks
            .maxByOrNull { it.markedAt!! }
            ?.let { newest -> putInterest(tx, into, newest.interesting!!, newest.markedAt!!) }
        giveWord(tx, from, into, newestOnly = true)
        keepOneOfSingle(tx, into, single)
        tx.deleteFrom(NORMALIZED_WORK).where(NORMALIZED_WORK.ID.eq(from)).execute()
    }

    /**
     * The words on the pairs of the work [from] given to [into], a word [into] has kept, none of [into] with itself.
     */
    private fun moveRelations(tx: DSLContext, from: Long, into: Long, now: Instant) {
        val r = NORMALIZED_WORK_RELATION
        tx.select(r.FIRST_WORK, r.OTHER_WORK, r.KIND)
            .from(r)
            .where(r.FIRST_WORK.eq(from).or(r.OTHER_WORK.eq(from)))
            .fetch()
            .forEach { row ->
                val other = if (row.value1() == from) row.value2()!! else row.value1()!!
                if (other != into) {
                    val (first, second) = WorkRelations.pair(other, into)
                    tx.insertInto(r)
                        .set(r.FIRST_WORK, first)
                        .set(r.OTHER_WORK, second)
                        .set(r.KIND, row.value3())
                        .set(r.SAID_AT, now)
                        .onDuplicateKeyIgnore()
                        .execute()
                }
            }
    }

    /**
     * The corrections of the work [from] given to [to], besides its own. Of a value or a field both have the answer
     * given [from] stands unless, [newestOnly], the one of [to] is the newer; the facets all named and the values not
     * clear are all kept.
     */
    private fun giveWord(tx: DSLContext, from: Long, to: Long, newestOnly: Boolean) {
        val f = WORK_FACET_CORRECTION
        tx.selectFrom(f).where(f.WORK.eq(from)).fetch().forEach {
            val kept =
                tx.select(f.CORRECTED_AT)
                    .from(f)
                    .where(f.WORK.eq(to), f.FACET.eq(it.facet), f.VALUE_KEY.eq(it.valueKey))
                    .fetchOne()
                    ?.value1()
            if (kept != null && newestOnly && !kept.isBefore(it.correctedAt)) return@forEach
            tx.insertInto(f)
                .set(f.WORK, to)
                .set(f.FACET, it.facet)
                .set(f.VALUE_KEY, it.valueKey)
                .set(f.ADDED, it.added)
                .set(f.NAME, it.name)
                .set(f.CORRECTED_AT, it.correctedAt)
                .onDuplicateKeyUpdate()
                .set(f.ADDED, it.added)
                .set(f.NAME, it.name)
                .set(f.CORRECTED_AT, it.correctedAt)
                .execute()
        }
        val d = WORK_FIELD_CORRECTION
        tx.selectFrom(d).where(d.WORK.eq(from)).fetch().forEach {
            val kept = tx.select(d.CORRECTED_AT).from(d).where(d.WORK.eq(to), d.FIELD.eq(it.field)).fetchOne()?.value1()
            if (kept != null && newestOnly && !kept.isBefore(it.correctedAt)) return@forEach
            tx.insertInto(d)
                .set(d.WORK, to)
                .set(d.FIELD, it.field)
                .set(d.CONTENT, it.content)
                .set(d.CORRECTED_AT, it.correctedAt)
                .onDuplicateKeyUpdate()
                .set(d.CONTENT, it.content)
                .set(d.CORRECTED_AT, it.correctedAt)
                .execute()
        }
        val o = WORK_FACET_COMPLETE
        tx.selectFrom(o).where(o.WORK.eq(from)).fetch().forEach {
            tx.insertInto(o)
                .set(o.WORK, to)
                .set(o.FACET, it.facet)
                .set(o.COMPLETED_AT, it.completedAt)
                .onDuplicateKeyIgnore()
                .execute()
        }
        val u = WORK_FACET_UNKNOWN
        tx.selectFrom(u).where(u.WORK.eq(from)).fetch().forEach {
            tx.insertInto(u)
                .set(u.WORK, to)
                .set(u.FACET, it.facet)
                .set(u.VALUE_KEY, it.valueKey)
                .set(u.SAID_AT, it.saidAt)
                .onDuplicateKeyIgnore()
                .execute()
        }
    }

    /** Of a facet of one value, of the values added to the work the newest left: two works merged may bring two. */
    private fun keepOneOfSingle(tx: DSLContext, work: Long, single: Set<String>) {
        if (single.isEmpty()) return
        val f = WORK_FACET_CORRECTION
        tx.selectFrom(f)
            .where(f.WORK.eq(work), f.FACET.`in`(single), f.ADDED.isTrue)
            .fetch()
            .groupBy { it.facet!! }
            .forEach { (facet, added) ->
                added
                    .sortedByDescending { it.correctedAt!! }
                    .drop(1)
                    .forEach {
                        tx.deleteFrom(f)
                            .where(f.WORK.eq(work), f.FACET.eq(facet), f.VALUE_KEY.eq(it.valueKey))
                            .execute()
                    }
            }
    }

    /**
     * The item taken out of its work of several items, to a work of its own; the new id. Nothing the user said is lost:
     * the grades given through the item go with it, the work's current grade, its mark and its corrections stand on
     * both after.
     */
    fun takeOut(key: ItemKey, now: Instant): Long = db.transactionResult { c ->
        val tx = DSL.using(c)
        val old =
            tx.select(SITE_WORK.NORMALIZED)
                .from(SITE_WORK)
                .where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id))
                .fetchOne()!!
                .value1()!!
        val current = currentGrades(tx, old).firstOrNull()
        val mark = tx.selectFrom(WORK_INTEREST).where(WORK_INTEREST.WORK.eq(old)).fetchOne()
        val id =
            tx.insertInto(NORMALIZED_WORK).defaultValues().returningResult(NORMALIZED_WORK.ID).fetchOne()!!.value1()!!
        tx.update(SITE_WORK)
            .set(SITE_WORK.NORMALIZED, id)
            .set(SITE_WORK.BOUND_AT, now)
            .where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id))
            .execute()
        tx.update(WORK_RATING)
            .set(WORK_RATING.WORK, id)
            .where(WORK_RATING.WORK.eq(old), WORK_RATING.SOURCE.eq(key.source), WORK_RATING.ITEM.eq(key.id))
            .execute()
        if (current != null) {
            giveGrade(tx, id, key, current)
            members(tx, old).firstOrNull()?.let { giveGrade(tx, old, it, current) }
        }
        if (mark != null) putInterest(tx, id, mark.interesting!!, mark.markedAt!!)
        giveWord(tx, old, id, newestOnly = false)
        id
    }

    /**
     * The item gone from the library: its work with it, of no other item, with all the user said; else what was given
     * through it stays, through another item.
     */
    fun forget(key: ItemKey) = db.transaction { c ->
        val tx = DSL.using(c)
        val id =
            tx.select(SITE_WORK.NORMALIZED)
                .from(SITE_WORK)
                .where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id))
                .fetchOne()
                ?.value1() ?: return@transaction
        val current = currentGrades(tx, id).firstOrNull()
        tx.deleteFrom(SITE_WORK).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id)).execute()
        tx.deleteFrom(WORK_RATING)
            .where(WORK_RATING.WORK.eq(id), WORK_RATING.SOURCE.eq(key.source), WORK_RATING.ITEM.eq(key.id))
            .execute()
        val left = members(tx, id).firstOrNull()
        if (left == null) tx.deleteFrom(NORMALIZED_WORK).where(NORMALIZED_WORK.ID.eq(id)).execute()
        else if (current != null) giveGrade(tx, id, left, current)
    }

    /** A grade of a work: what is kept of it whatever item it stands through. */
    private class Given(val grade: Int, val ratedAt: Instant)

    private fun members(tx: DSLContext, work: Long): List<ItemKey> =
        tx.select(SITE_WORK.SOURCE, SITE_WORK.ITEM)
            .from(SITE_WORK)
            .where(SITE_WORK.NORMALIZED.eq(work))
            .orderBy(SITE_WORK.SOURCE, SITE_WORK.ITEM)
            .fetch { ItemKey(it.value1()!!, it.value2()!!) }

    /** The grades of the work at the version their item stands at now, the newest first. */
    private fun currentGrades(tx: DSLContext, work: Long): List<Given> {
        val r = WORK_RATING
        return tx.select(r.GRADE, r.RATED_AT)
            .from(r)
            .join(SITE_WORK)
            .on(SITE_WORK.SOURCE.eq(r.SOURCE), SITE_WORK.ITEM.eq(r.ITEM), SITE_WORK.VERSION.eq(r.VERSION))
            .where(r.WORK.eq(work))
            .orderBy(r.RATED_AT.desc())
            .fetch { Given(it.value1()!!, it.value2()!!) }
    }

    /**
     * The work given the grade, as of when it was, through the item at the version it stands at, unless it has a
     * current one.
     */
    private fun giveGrade(tx: DSLContext, work: Long, key: ItemKey, given: Given) {
        if (currentGrades(tx, work).isNotEmpty()) return
        val version =
            tx.select(SITE_WORK.VERSION)
                .from(SITE_WORK)
                .where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id))
                .fetchOne()!!
                .value1()!!
        val r = WORK_RATING
        tx.insertInto(r)
            .set(r.WORK, work)
            .set(r.SOURCE, key.source)
            .set(r.ITEM, key.id)
            .set(r.VERSION, version)
            .set(r.GRADE, given.grade)
            .set(r.RATED_AT, given.ratedAt)
            .execute()
    }

    /** Of the current grades of the work the newest left. */
    private fun keepNewestGrade(tx: DSLContext, work: Long) {
        val r = WORK_RATING
        val current =
            tx.select(r.SOURCE, r.ITEM, r.VERSION)
                .from(r)
                .join(SITE_WORK)
                .on(SITE_WORK.SOURCE.eq(r.SOURCE), SITE_WORK.ITEM.eq(r.ITEM), SITE_WORK.VERSION.eq(r.VERSION))
                .where(r.WORK.eq(work))
                .orderBy(r.RATED_AT.desc())
                .fetch()
        current.drop(1).forEach {
            tx.deleteFrom(r)
                .where(r.WORK.eq(work), r.SOURCE.eq(it.value1()), r.ITEM.eq(it.value2()), r.VERSION.eq(it.value3()))
                .execute()
        }
    }

    private fun putInterest(tx: DSLContext, work: Long, interesting: Boolean, at: Instant) {
        val i = WORK_INTEREST
        tx.insertInto(i)
            .set(i.WORK, work)
            .set(i.INTERESTING, interesting)
            .set(i.MARKED_AT, at)
            .onDuplicateKeyUpdate()
            .set(i.INTERESTING, interesting)
            .set(i.MARKED_AT, at)
            .execute()
    }
}
