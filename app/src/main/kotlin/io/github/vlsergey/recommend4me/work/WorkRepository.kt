package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK_RELATION
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/**
 * The normalized works, in the dictionaries: every item of every site bound to one, at first of its
 * own, merged when the user says two are one work; and the user's word on two works that stay two.
 */
class WorkRepository(private val db: DSLContext) {

    /** Those of the [items] of the [source] bound to a normalized work. */
    fun boundAmong(source: String, items: Collection<String>): Set<String> =
        items.chunked(BATCH).flatMapTo(HashSet()) { chunk ->
            db.select(SITE_WORK.ITEM).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(source), SITE_WORK.ITEM.`in`(chunk)).fetch { it.value1()!! }
        }

    /** The [items] of the [source], none bound yet, bound to a normalized work each of its own, at once. */
    fun bindNew(source: String, items: Collection<String>, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        items.forEach { item ->
            val id = tx.insertInto(NORMALIZED_WORK).defaultValues().returningResult(NORMALIZED_WORK.ID).fetchOne()!!.value1()!!
            tx.insertInto(SITE_WORK).set(SITE_WORK.SOURCE, source).set(SITE_WORK.ITEM, item).set(SITE_WORK.NORMALIZED, id).set(SITE_WORK.BOUND_AT, now).execute()
        }
    }

    /** The normalized work of the item; one of its own bound now when it has none. */
    fun of(key: ItemKey, now: Instant): Long {
        boundTo(key)?.let { return it }
        bindNew(key.source, listOf(key.id), now)
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
     * it moved to [into] — a word [into] has on the same work kept, none of [into] with itself — and
     * [from] gone.
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
        tx.deleteFrom(NORMALIZED_WORK).where(NORMALIZED_WORK.ID.eq(from)).execute()
    }

    /** The item taken out of its work of several items, to a work of its own; the new id. */
    fun takeOut(key: ItemKey, now: Instant): Long = db.transactionResult { c ->
        val tx = DSL.using(c)
        val id = tx.insertInto(NORMALIZED_WORK).defaultValues().returningResult(NORMALIZED_WORK.ID).fetchOne()!!.value1()!!
        tx.update(SITE_WORK).set(SITE_WORK.NORMALIZED, id).set(SITE_WORK.BOUND_AT, now).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id)).execute()
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

    /** The item gone from the library; its work with it, of no other item. */
    fun forget(key: ItemKey) = db.transaction { c ->
        val tx = DSL.using(c)
        val id = tx.select(SITE_WORK.NORMALIZED).from(SITE_WORK).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id)).fetchOne()?.value1()
            ?: return@transaction
        tx.deleteFrom(SITE_WORK).where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id)).execute()
        if (!tx.fetchExists(SITE_WORK, SITE_WORK.NORMALIZED.eq(id))) tx.deleteFrom(NORMALIZED_WORK).where(NORMALIZED_WORK.ID.eq(id)).execute()
    }

    companion object {
        private const val BATCH = 1000

        fun pair(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a
    }
}
