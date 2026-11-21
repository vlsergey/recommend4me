package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.database.SourceItems
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.worklist.WorkDatum
import java.time.Instant
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field

/**
 * WHEN THE USER'S WORD ON A NORMALIZED WORK LAST CHANGED, the work's own dates in the dictionaries (owner, 2026-11-21:
 * "a work has the date it was last changed"): of every kind of it ([WorkDatum]) — its answers on values and fields, its
 * grade and mark of interest, the items it is of (merged, taken out, in [WorkMembership]'s transaction) — and of any of
 * them the date the index of the list writes its items anew by ([NORMALIZED_WORK.CHANGED_AT]).
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

    /** The items of the [sources] whose works changed at [since] or after, by the index of the dates. */
    fun itemsSince(sources: Collection<String>, since: Instant): List<ItemKey> =
        db.select(SITE_WORK.SOURCE, SITE_WORK.ITEM)
            .from(NORMALIZED_WORK)
            .join(SITE_WORK)
            .on(SITE_WORK.NORMALIZED.eq(NORMALIZED_WORK.ID))
            .where(NORMALIZED_WORK.CHANGED_AT.ge(since), SITE_WORK.SOURCE.`in`(sources))
            .fetch { ItemKey(it.value1()!!, it.value2()!!) }

    /**
     * Of the items [ids] of the [source] — of every item of it when null — the date every kind of the user's word on
     * its work last changed, milliseconds; a kind never dated left out.
     */
    fun ofItems(source: String, ids: Collection<String>?): Map<String, Map<WorkDatum, Long>> {
        val out = HashMap<String, Map<WorkDatum, Long>>()
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

    /** Whether a work of an item of the [sources] changed at [since] or after. */
    fun anySince(sources: Collection<String>, since: Instant): Boolean =
        db.fetchExists(
            db.selectOne()
                .from(NORMALIZED_WORK)
                .join(SITE_WORK)
                .on(SITE_WORK.NORMALIZED.eq(NORMALIZED_WORK.ID))
                .where(NORMALIZED_WORK.CHANGED_AT.ge(since), SITE_WORK.SOURCE.`in`(sources))
        )

    companion object {
        /** The items asked of by one statement: its length, not a meaning. */
        private const val BATCH = 1000

        /** The column of the date of every kind of the user's word on a work. */
        private val COLUMNS: Map<WorkDatum, Field<Instant?>> =
            mapOf(
                WorkDatum.ANSWERS to NORMALIZED_WORK.ANSWERS_AT,
                WorkDatum.GRADES to NORMALIZED_WORK.GRADES_AT,
                WorkDatum.MEMBERS to NORMALIZED_WORK.MEMBERS_AT,
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
