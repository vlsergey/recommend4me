package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.database.SourceItems
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.worklist.WorkCursor
import io.github.vlsergey.recommend4me.worklist.WorkDatum
import java.time.Instant
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * WHEN THE USER'S WORD ON A NORMALIZED WORK LAST CHANGED, the work's own dates in the dictionaries (owner, 2026-11-21:
 * "a work has the date it was last changed"): of every kind of it ([WorkDatum]) — its answers on values and fields, its
 * grade and mark of interest, the items it is of (merged, taken out, in [WorkMembership]'s transaction), the authors
 * and series it is of and what they hold ([WorkGroupDates]) — and of any of them the date the index of the list writes
 * its items anew by ([NORMALIZED_WORK.CHANGED_AT]).
 */
class WorkChanges(private val db: DSLContext) {

    /** The [datum] of the works of the [items] changed [at]. */
    fun changed(items: Collection<ItemKey>, datum: WorkDatum, at: Instant) =
        items.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            ids.distinct().chunked(BATCH).forEach { chunk ->
                val works =
                    db.select(SITE_WORK.NORMALIZED)
                        .from(SITE_WORK)
                        .where(SourceItems.of(SITE_WORK.SOURCE, SITE_WORK.ITEM, source, chunk))
                        .fetch { it.value1()!! }
                changed(db, works, datum, at)
            }
        }

    /** The [datum] of the normalized [works] changed [at]. */
    fun worksChanged(works: Collection<Long>, datum: WorkDatum, at: Instant) =
        works.distinct().chunked(BATCH).forEach { changed(db, it, datum, at) }

    /**
     * The next [limit] normalized works of items of the [sources] the user's word changed, after the [cursor] — of
     * none, from the first — in the order of their changes: by the date, then by the id; each as the cursor after it.
     */
    fun after(sources: Collection<String>, cursor: WorkCursor?, limit: Int): List<WorkCursor> {
        val at = NORMALIZED_WORK.CHANGED_AT
        // A range of the index of the dates the reading begins at, not a condition it reads every work by (2026-11-23:
        // "is not null", "after or at the same date" took a pass of every work, nine of a hundred of a writing's time)
        val after =
            if (cursor == null) at.ge(Instant.EPOCH)
            else at.ge(cursor.at).and(at.gt(cursor.at).or(NORMALIZED_WORK.ID.gt(cursor.id)))
        return db.select(NORMALIZED_WORK.ID, at)
            .from(NORMALIZED_WORK)
            .where(
                after,
                DSL.exists(
                    DSL.selectOne()
                        .from(SITE_WORK)
                        .where(SITE_WORK.NORMALIZED.eq(NORMALIZED_WORK.ID), SITE_WORK.SOURCE.`in`(sources))
                ),
            )
            .orderBy(at.asc(), NORMALIZED_WORK.ID.asc())
            .limit(limit)
            .fetch { WorkCursor(it.value2()!!, it.value1()!!) }
    }

    /**
     * Of the items [ids] of the [source] — of every item of it when null — the date every kind of the user's word on
     * its work last changed, milliseconds; a kind never dated left out.
     */
    fun ofItems(source: String, ids: Collection<Long>?): Map<Long, Map<WorkDatum, Long>> {
        val out = HashMap<Long, Map<WorkDatum, Long>>()
        fun read(condition: Condition) =
            db.select(listOf(SITE_WORK.ITEM) + COLUMNS.values)
                .from(SITE_WORK)
                .join(NORMALIZED_WORK)
                .on(NORMALIZED_WORK.ID.eq(SITE_WORK.NORMALIZED))
                .where(condition, NORMALIZED_WORK.CHANGED_AT.isNotNull)
                .fetch { r ->
                    out[r.get(SITE_WORK.ITEM)!!] =
                        COLUMNS.entries
                            .mapNotNull { (datum, column) -> r.get(column)?.let { datum to it.toEpochMilli() } }
                            .toMap()
                }
        if (ids == null) read(SITE_WORK.SOURCE.eq(source))
        else
            ids.distinct().chunked(BATCH).forEach { read(SourceItems.of(SITE_WORK.SOURCE, SITE_WORK.ITEM, source, it)) }
        return out
    }

    companion object {
        /** The items asked of by one statement: its length, not a meaning. */
        private const val BATCH = 1000

        /** The column of the date of every kind of the user's word on a work. */
        private val COLUMNS: Map<WorkDatum, Field<Instant?>> =
            mapOf(
                WorkDatum.ANSWERS to NORMALIZED_WORK.ANSWERS_AT,
                WorkDatum.GRADES to NORMALIZED_WORK.GRADES_AT,
                WorkDatum.MEMBERS to NORMALIZED_WORK.MEMBERS_AT,
                WorkDatum.GROUPS to NORMALIZED_WORK.GROUPS_AT,
            )

        /** The [datum] of the [works] changed [at], within the transaction [tx]. */
        fun changed(tx: DSLContext, works: Collection<Long>, datum: WorkDatum, at: Instant) {
            if (works.isEmpty()) return
            val column = requireNotNull(COLUMNS[datum]) { "$datum is no kind of the user's word on a work" }
            tx.update(NORMALIZED_WORK)
                .set(NORMALIZED_WORK.CHANGED_AT, at)
                .set(column, at)
                .where(NORMALIZED_WORK.ID.`in`(works.distinct()))
                .execute()
        }
    }
}
