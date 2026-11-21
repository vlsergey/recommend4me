package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.database.SourceItems
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.item.ItemKey
import java.time.Instant
import org.jooq.DSLContext

/**
 * WHEN THE USER'S WORD ON A NORMALIZED WORK LAST CHANGED, the work's own date in the dictionaries
 * ([NORMALIZED_WORK.CHANGED_AT]; owner, 2026-11-21: "a work has the date it was last changed"): a value or a field
 * answered or taken back, a grade, a mark of interest — or its items, merged or taken out ([WorkMembership], in its
 * transaction). The index of the list writes anew the documents of the items of the works changed since its last
 * writing ([io.github.vlsergey.recommend4me.worklist.ListIndexing]).
 */
class WorkChanges(private val db: DSLContext) {

    /** The works of the [items] changed [at]. */
    fun changed(items: Collection<ItemKey>, at: Instant) =
        items.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            ids.distinct().chunked(BATCH).forEach { chunk ->
                val works =
                    db.select(SITE_WORK.NORMALIZED)
                        .from(SITE_WORK)
                        .where(SourceItems.of(SITE_WORK.SOURCE, SITE_WORK.ITEM, source, chunk))
                        .fetch { it.value1()!! }
                changed(db, works, at)
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
     * Of the items [ids] of the [source] — of every item of it when null — the date the user's word on its work, or its
     * items, last changed; an item of a work never dated left out.
     */
    fun ofItems(source: String, ids: Collection<String>?): Map<String, Instant> {
        fun read(condition: org.jooq.Condition): Map<String, Instant> =
            db.select(SITE_WORK.ITEM, NORMALIZED_WORK.CHANGED_AT)
                .from(SITE_WORK)
                .join(NORMALIZED_WORK)
                .on(NORMALIZED_WORK.ID.eq(SITE_WORK.NORMALIZED))
                .where(condition, NORMALIZED_WORK.CHANGED_AT.isNotNull)
                .fetchMap({ it.value1()!! }, { it.value2()!! })
        return if (ids == null) read(SITE_WORK.SOURCE.eq(source))
        else
            ids.distinct().chunked(BATCH).fold(HashMap()) { out, chunk ->
                out.apply { putAll(read(SourceItems.of(SITE_WORK.SOURCE, SITE_WORK.ITEM, source, chunk))) }
            }
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

        /** The [works] changed [at], within the transaction [tx]. */
        fun changed(tx: DSLContext, works: Collection<Long>, at: Instant) {
            if (works.isEmpty()) return
            tx.update(NORMALIZED_WORK)
                .set(NORMALIZED_WORK.CHANGED_AT, at)
                .where(NORMALIZED_WORK.ID.`in`(works.distinct()))
                .execute()
        }
    }
}
