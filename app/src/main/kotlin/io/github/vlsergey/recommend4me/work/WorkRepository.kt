package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK_RELATION
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_INTEREST
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_RATING
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/**
 * The normalized works, in the dictionaries: every item of every site bound to one, at first of its
 * own, merged when the user says two are one work; the user's word on two works that stay two; and
 * what goes with a work as it is merged, split or left by an item: its grades and its mark of interest.
 */
class WorkRepository(private val db: DSLContext) {

    /** Those of the [items] of the [source] bound to a normalized work, with the version kept of each. */
    fun boundAmong(source: String, items: Collection<String>): Map<String, String> =
        items.chunked(BATCH).flatMap { chunk ->
            db.select(SITE_WORK.ITEM, SITE_WORK.VERSION).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(source), SITE_WORK.ITEM.`in`(chunk)).fetch { it.value1()!! to it.value2()!! }
        }.toMap()

    /** The [items] of the [source], none bound yet, with their versions, bound to a normalized work each of its own, at once. */
    fun bindNew(source: String, items: Map<String, String>, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        items.forEach { (item, version) ->
            val id = tx.insertInto(NORMALIZED_WORK).defaultValues().returningResult(NORMALIZED_WORK.ID).fetchOne()!!.value1()!!
            tx.insertInto(SITE_WORK).set(SITE_WORK.SOURCE, source).set(SITE_WORK.ITEM, item).set(SITE_WORK.VERSION, version).set(SITE_WORK.NORMALIZED, id).set(SITE_WORK.BOUND_AT, now).execute()
        }
    }

    /** The version the item stands at now. */
    fun setVersion(key: ItemKey, version: String) {
        db.update(SITE_WORK).set(SITE_WORK.VERSION, version).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id), SITE_WORK.VERSION.ne(version)).execute()
    }

    /** The normalized work of the item; one of its own bound now when it has none. */
    fun of(key: ItemKey, now: Instant): Long {
        boundTo(key)?.let { return it }
        bindNew(key.source, mapOf(key.id to ""), now)
        return boundTo(key)!!
    }

    private fun boundTo(key: ItemKey): Long? =
        db.select(SITE_WORK.NORMALIZED).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id)).fetchOne()?.value1()

    /** The normalized works of [sources] of several items, with their items. */
    fun groups(sources: Collection<String>): Map<Long, List<ItemKey>> {
        val several = DSL.select(SITE_WORK.NORMALIZED).from(SITE_WORK).groupBy(SITE_WORK.NORMALIZED).having(DSL.count().gt(1))
        return db.select(SITE_WORK.NORMALIZED, SITE_WORK.SOURCE, SITE_WORK.ITEM).from(SITE_WORK)
            .where(SITE_WORK.NORMALIZED.`in`(several), SITE_WORK.SOURCE.`in`(sources))
            .fetch().groupBy({ it.value1()!! }, { ItemKey(it.value2()!!, it.value3()!!) })
    }

    /** The items of [works] among the [sources]. */
    fun itemsOf(works: Collection<Long>, sources: Collection<String>): Map<Long, List<ItemKey>> =
        works.distinct().chunked(BATCH).flatMap { chunk ->
            db.select(SITE_WORK.NORMALIZED, SITE_WORK.SOURCE, SITE_WORK.ITEM).from(SITE_WORK)
                .where(SITE_WORK.NORMALIZED.`in`(chunk), SITE_WORK.SOURCE.`in`(sources))
                .fetch { it.value1()!! to ItemKey(it.value2()!!, it.value3()!!) }
        }.groupBy({ it.first }, { it.second })

    /**
     * The normalized work [from] merged into [into]: every item of it bound to [into], the words on
     * it moved to [into] — a word [into] has on the same work kept, none of [into] with itself — its
     * grades and mark too, the newest of the two the one that stands, and [from] gone.
     */
    fun merge(from: Long, into: Long, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.update(SITE_WORK).set(SITE_WORK.NORMALIZED, into).set(SITE_WORK.BOUND_AT, now).where(SITE_WORK.NORMALIZED.eq(from)).execute()
        val r = NORMALIZED_WORK_RELATION
        tx.select(r.FIRST_WORK, r.OTHER_WORK, r.KIND).from(r).where(r.FIRST_WORK.eq(from).or(r.OTHER_WORK.eq(from))).fetch().forEach { row ->
            val other = if (row.value1() == from) row.value2()!! else row.value1()!!
            if (other != into) {
                val (first, second) = pair(other, into)
                tx.insertInto(r).set(r.FIRST_WORK, first).set(r.OTHER_WORK, second).set(r.KIND, row.value3()).set(r.SAID_AT, now).onDuplicateKeyIgnore().execute()
            }
        }
        tx.update(WORK_RATING).set(WORK_RATING.WORK, into).where(WORK_RATING.WORK.eq(from)).execute()
        keepNewestGrade(tx, into)
        val i = WORK_INTEREST
        val marks = tx.selectFrom(i).where(i.WORK.`in`(from, into)).fetch()
        marks.maxByOrNull { it.markedAt!! }?.let { newest -> putInterest(tx, into, newest.interesting!!, newest.markedAt!!) }
        tx.deleteFrom(NORMALIZED_WORK).where(NORMALIZED_WORK.ID.eq(from)).execute()
    }

    /**
     * The item taken out of its work of several items, to a work of its own; the new id. Nothing the
     * user said is lost: the grades given through the item go with it, the work's current grade and
     * mark stand on both after.
     */
    fun takeOut(key: ItemKey, now: Instant): Long = db.transactionResult { c ->
        val tx = DSL.using(c)
        val old = tx.select(SITE_WORK.NORMALIZED).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id)).fetchOne()!!.value1()!!
        val current = currentGrades(tx, old).firstOrNull()
        val mark = tx.selectFrom(WORK_INTEREST).where(WORK_INTEREST.WORK.eq(old)).fetchOne()
        val id = tx.insertInto(NORMALIZED_WORK).defaultValues().returningResult(NORMALIZED_WORK.ID).fetchOne()!!.value1()!!
        tx.update(SITE_WORK).set(SITE_WORK.NORMALIZED, id).set(SITE_WORK.BOUND_AT, now).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id)).execute()
        tx.update(WORK_RATING).set(WORK_RATING.WORK, id).where(WORK_RATING.WORK.eq(old), WORK_RATING.SOURCE.eq(key.source), WORK_RATING.ITEM.eq(key.id)).execute()
        if (current != null) {
            giveGrade(tx, id, key, current)
            members(tx, old).firstOrNull()?.let { giveGrade(tx, old, it, current) }
        }
        if (mark != null) putInterest(tx, id, mark.interesting!!, mark.markedAt!!)
        id
    }

    /** The user's word on the two works, in place of the one before. */
    fun say(a: Long, b: Long, kind: String, now: Instant) {
        val (first, second) = pair(a, b)
        val r = NORMALIZED_WORK_RELATION
        db.insertInto(r).set(r.FIRST_WORK, first).set(r.OTHER_WORK, second).set(r.KIND, kind).set(r.SAID_AT, now)
            .onDuplicateKeyUpdate().set(r.KIND, kind).set(r.SAID_AT, now).execute()
    }

    /** The word on the two works taken back. */
    fun takeBack(a: Long, b: Long) {
        val (first, second) = pair(a, b)
        val r = NORMALIZED_WORK_RELATION
        db.deleteFrom(r).where(r.FIRST_WORK.eq(first), r.OTHER_WORK.eq(second)).execute()
    }

    /** Every word on two works, the smaller id first. */
    fun words(): Map<Pair<Long, Long>, String> {
        val r = NORMALIZED_WORK_RELATION
        return db.select(r.FIRST_WORK, r.OTHER_WORK, r.KIND).from(r).fetchMap({ it.value1()!! to it.value2()!! }, { it.value3()!! })
    }

    /** The item gone from the library: its work with it, of no other item, with all the user said; else what was given through it stays, through another item. */
    fun forget(key: ItemKey) = db.transaction { c ->
        val tx = DSL.using(c)
        val id = tx.select(SITE_WORK.NORMALIZED).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id)).fetchOne()?.value1()
            ?: return@transaction
        val current = currentGrades(tx, id).firstOrNull()
        tx.deleteFrom(SITE_WORK).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id)).execute()
        tx.deleteFrom(WORK_RATING).where(WORK_RATING.WORK.eq(id), WORK_RATING.SOURCE.eq(key.source), WORK_RATING.ITEM.eq(key.id)).execute()
        val left = members(tx, id).firstOrNull()
        if (left == null) tx.deleteFrom(NORMALIZED_WORK).where(NORMALIZED_WORK.ID.eq(id)).execute()
        else if (current != null) giveGrade(tx, id, left, current)
    }

    /** A grade of a work: what is kept of it whatever item it stands through. */
    private class Given(val grade: Int, val ratedAt: Instant)

    private fun members(tx: DSLContext, work: Long): List<ItemKey> =
        tx.select(SITE_WORK.SOURCE, SITE_WORK.ITEM).from(SITE_WORK).where(SITE_WORK.NORMALIZED.eq(work)).orderBy(SITE_WORK.SOURCE, SITE_WORK.ITEM)
            .fetch { ItemKey(it.value1()!!, it.value2()!!) }

    /** The grades of the work at the version their item stands at now, the newest first. */
    private fun currentGrades(tx: DSLContext, work: Long): List<Given> {
        val r = WORK_RATING
        return tx.select(r.GRADE, r.RATED_AT).from(r)
            .join(SITE_WORK).on(SITE_WORK.SOURCE.eq(r.SOURCE), SITE_WORK.ITEM.eq(r.ITEM), SITE_WORK.VERSION.eq(r.VERSION))
            .where(r.WORK.eq(work)).orderBy(r.RATED_AT.desc())
            .fetch { Given(it.value1()!!, it.value2()!!) }
    }

    /** The work given the grade, as of when it was, through the item at the version it stands at, unless it has a current one. */
    private fun giveGrade(tx: DSLContext, work: Long, key: ItemKey, given: Given) {
        if (currentGrades(tx, work).isNotEmpty()) return
        val version = tx.select(SITE_WORK.VERSION).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id)).fetchOne()!!.value1()!!
        val r = WORK_RATING
        tx.insertInto(r).set(r.WORK, work).set(r.SOURCE, key.source).set(r.ITEM, key.id).set(r.VERSION, version).set(r.GRADE, given.grade).set(r.RATED_AT, given.ratedAt).execute()
    }

    /** Of the current grades of the work the newest left. */
    private fun keepNewestGrade(tx: DSLContext, work: Long) {
        val r = WORK_RATING
        val current = tx.select(r.SOURCE, r.ITEM, r.VERSION).from(r)
            .join(SITE_WORK).on(SITE_WORK.SOURCE.eq(r.SOURCE), SITE_WORK.ITEM.eq(r.ITEM), SITE_WORK.VERSION.eq(r.VERSION))
            .where(r.WORK.eq(work)).orderBy(r.RATED_AT.desc()).fetch()
        current.drop(1).forEach { tx.deleteFrom(r).where(r.WORK.eq(work), r.SOURCE.eq(it.value1()), r.ITEM.eq(it.value2()), r.VERSION.eq(it.value3())).execute() }
    }

    private fun putInterest(tx: DSLContext, work: Long, interesting: Boolean, at: Instant) {
        val i = WORK_INTEREST
        tx.insertInto(i).set(i.WORK, work).set(i.INTERESTING, interesting).set(i.MARKED_AT, at)
            .onDuplicateKeyUpdate().set(i.INTERESTING, interesting).set(i.MARKED_AT, at).execute()
    }

    /** Of the current grades of every work of several items the newest left: once the grades of before are moved, each item's own. */
    fun keepNewestGrades(sources: Collection<String>) = groups(sources).keys.forEach { keepNewestGrade(db, it) }

    companion object {
        private const val BATCH = 1000

        fun pair(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a
    }
}
