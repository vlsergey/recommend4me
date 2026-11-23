package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import io.github.vlsergey.recommend4me.worklist.ItemCursor
import io.github.vlsergey.recommend4me.worklist.WorkDatum
import java.time.Instant
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * WHEN WHAT AN ITEM IS MADE OF LAST CHANGED, in the source's database (owner, 2026-11-21: "why name them and keep the
 * names at all? the index has the date it was last built, a work the date it was last changed"; "a self-evident
 * decomposition of dependencies"): of every outer kind of its data ([WorkDatum]) — what the site says of it, its
 * chapters' texts, its reviews, its pictures analysed — the date its write gave it, and of any of them the date the
 * index of the list writes it anew by ([ITEM.CHANGED_AT]). The index of the list brings the dates into the documents,
 * and the stages of the tasks over the works take the works by the kinds they read
 * ([io.github.vlsergey.recommend4me.worklist.StageSweeps]).
 */
class ItemChanges(private val db: DSLContext) {
    /** The [datum] of the items [ids] changed [at]. */
    fun changed(ids: Collection<String>, datum: WorkDatum, at: Instant) {
        val column = columnOf(datum)
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.update(ITEM).set(ITEM.CHANGED_AT, at).set(column, at).where(ITEM.ITEM_ID.`in`(chunk)).execute()
        }
    }

    /**
     * Of the items [ids] — of every item when null — the date every outer kind of their data last changed,
     * milliseconds; a kind never dated left out.
     */
    fun of(ids: Collection<String>?): Map<String, Map<WorkDatum, Long>> {
        val out = HashMap<String, Map<WorkDatum, Long>>()
        fun read(condition: Condition) =
            db.select(listOf(ITEM.ITEM_ID) + COLUMNS.values)
                .from(ITEM)
                .where(condition, ITEM.CHANGED_AT.isNotNull)
                .fetch { r ->
                    out[r.get(ITEM.ITEM_ID)!!] =
                        COLUMNS.entries
                            .mapNotNull { (datum, column) -> r.get(column)?.let { datum to it.toEpochMilli() } }
                            .toMap()
                }
        if (ids == null) read(DSL.noCondition())
        else ids.distinct().chunked(BATCH).forEach { read(ITEM.ITEM_ID.`in`(it)) }
        return out
    }

    /**
     * The next [limit] items after the [cursor] — of none, from the first — in the order of their changes: by the date,
     * the items of none first, then by the key; each as the cursor after it. By the index of the dates and the keys.
     */
    fun after(cursor: ItemCursor?, limit: Int): List<ItemCursor> {
        val at = ITEM.CHANGED_AT
        val after =
            when {
                cursor == null -> DSL.noCondition()
                cursor.at == null -> at.isNull.and(ITEM.ITEM_ID.gt(cursor.id)).or(at.isNotNull)
                else -> at.gt(cursor.at).or(at.eq(cursor.at).and(ITEM.ITEM_ID.gt(cursor.id)))
            }
        return db.select(ITEM.ITEM_ID, at)
            .from(ITEM)
            .where(after)
            .orderBy(at.asc().nullsFirst(), ITEM.ITEM_ID.asc())
            .limit(limit)
            .fetch { ItemCursor(it.value2(), it.value1()!!) }
    }

    private companion object {
        /** The items written by one statement: its length, not a meaning. */
        const val BATCH = 1000

        /** The column of the date of every outer kind of an item's own data. */
        val COLUMNS: Map<WorkDatum, Field<Instant?>> =
            mapOf(
                WorkDatum.DATA to ITEM.DATA_AT,
                WorkDatum.CHAPTERS to ITEM.CHAPTERS_AT,
                WorkDatum.REVIEWS to ITEM.REVIEWS_AT,
                WorkDatum.PICTURES to ITEM.PICTURES_AT,
            )

        fun columnOf(datum: WorkDatum): Field<Instant?> =
            requireNotNull(COLUMNS[datum]) { "$datum is no kind of an item's own data" }
    }
}
