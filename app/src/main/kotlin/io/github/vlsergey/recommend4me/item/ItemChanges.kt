package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.EVERY_ITEM_CHANGE
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import java.time.Instant
import org.jooq.DSLContext

/**
 * WHEN WHAT THE INDEX OF THE LIST HOLDS OF A SITE'S ITEM LAST CHANGED, in the source's database (owner, 2026-11-21:
 * "why name them and keep the names at all? the index has the date it was last built, a work the date it was last
 * changed"): the item's own date ([ITEM.CHANGED_AT]) — its head, values and texts as their writes change them, the
 * counts of its chapters' texts by the write of them, the vectors of its chapters, its cast, what is drafted of it —
 * and the source's, of every item at once (a dictionary its values are drafted by). The index of the list writes anew
 * the documents of what changed since its last writing ([io.github.vlsergey.recommend4me.worklist.ListIndexing]):
 * nothing is named in the queue, nothing is lost by a stop.
 */
class ItemChanges(private val db: DSLContext) {
    private val every = EVERY_ITEM_CHANGE

    /** The items [ids] changed [at]. */
    fun changed(ids: Collection<String>, at: Instant) =
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.update(ITEM).set(ITEM.CHANGED_AT, at).where(ITEM.ITEM_ID.`in`(chunk)).execute()
        }

    /** Every item of the source changed [at]. */
    fun everyChanged(at: Instant) {
        db.mergeInto(every).columns(every.ID, every.CHANGED_AT).key(every.ID).values(ONE, at).execute()
    }

    /** Whether every item of the source changed at [since] or after. */
    fun everySince(since: Instant): Boolean = db.fetchExists(every, every.CHANGED_AT.ge(since))

    /** Whether an item changed at [since] or after. */
    fun anySince(since: Instant): Boolean = db.fetchExists(ITEM, ITEM.CHANGED_AT.ge(since))

    /** Of the items [ids] — of every item when null — the date each last changed; an item never dated left out. */
    fun of(ids: Collection<String>?): Map<String, Instant> =
        if (ids == null)
            db.select(ITEM.ITEM_ID, ITEM.CHANGED_AT)
                .from(ITEM)
                .where(ITEM.CHANGED_AT.isNotNull)
                .fetchMap(
                    { it.value1()!! },
                    { it.value2()!! },
                )
        else
            ids.distinct().chunked(BATCH).fold(HashMap()) { out, chunk ->
                db.select(ITEM.ITEM_ID, ITEM.CHANGED_AT)
                    .from(ITEM)
                    .where(ITEM.ITEM_ID.`in`(chunk), ITEM.CHANGED_AT.isNotNull)
                    .fetch { out[it.value1()!!] = it.value2()!! }
                out
            }

    /** The items changed at [since] or after, by the index of the dates: the few since a writing. */
    fun since(since: Instant): List<String> =
        db.select(ITEM.ITEM_ID).from(ITEM).where(ITEM.CHANGED_AT.ge(since)).fetch { it.value1()!! }

    private companion object {
        /** The items written by one statement: its length, not a meaning. */
        const val BATCH = 1000

        /** The key of the one row of [EVERY_ITEM_CHANGE]. */
        const val ONE = 1
    }
}
