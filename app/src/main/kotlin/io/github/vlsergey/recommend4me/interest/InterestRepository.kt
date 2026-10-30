package io.github.vlsergey.recommend4me.interest

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_INTEREST
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkRepository
import org.jooq.DSLContext
import java.time.Instant

/** The user's word on whether a work is interesting to read at all, and when it was said. */
data class InterestMark(val itemId: String, val interesting: Boolean, val markedAt: Instant)

/** The interest marks of the normalized works (in the dictionaries), one a work, as the items of one source see them. */
class InterestRepository(private val db: DSLContext, private val source: String, private val works: WorkRepository) {

    private fun workOf(itemId: String, now: Instant) = works.of(ItemKey(source, itemId), now)

    fun mark(itemId: String, interesting: Boolean, now: Instant) {
        val i = WORK_INTEREST
        db.insertInto(i).set(i.WORK, workOf(itemId, now)).set(i.INTERESTING, interesting).set(i.MARKED_AT, now)
            .onDuplicateKeyUpdate().set(i.INTERESTING, interesting).set(i.MARKED_AT, now)
            .execute()
    }

    fun ofItem(itemId: String): InterestMark? = seen(itemId).firstOrNull()

    /** Every mark the source's items have: item to mark. */
    fun all(): Map<String, InterestMark> = seen(null).associateBy { it.itemId }

    private fun seen(itemId: String?): List<InterestMark> {
        val i = WORK_INTEREST
        val own = SITE_WORK.SOURCE.eq(source)
        return db.select(SITE_WORK.ITEM, i.INTERESTING, i.MARKED_AT).from(SITE_WORK).join(i).on(i.WORK.eq(SITE_WORK.NORMALIZED))
            .where(if (itemId == null) own else own.and(SITE_WORK.ITEM.eq(itemId)))
            .fetch { InterestMark(it.value1()!!, it.value2()!!, it.value3()!!) }
    }

    /** The mark of the work of the item taken back. */
    fun forget(itemId: String) {
        db.deleteFrom(WORK_INTEREST).where(WORK_INTEREST.WORK.eq(workOf(itemId, Instant.now()))).execute()
    }
}
