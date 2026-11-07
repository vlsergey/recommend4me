package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.WORK_FACET_COMPLETE
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.time.Instant
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE FACETS WHOSE VALUES ARE ALL NAMED: the user's word that a work has no value of a facet but those it has, as the
 * items of one source see it — every item of a work has it alike.
 */
interface CompleteFacets {
    /** The user's word that the work's values of the facet are all named ([complete]), or its taking back. */
    fun setComplete(itemId: String, facet: String, complete: Boolean, now: Instant)

    /**
     * The facets whose values the user said are all named, of each of the items [ids] that has any: by their ids up to
     * a batch of them; of more, every word of the source read once and picked from.
     */
    fun completeOf(ids: Collection<String>): Map<String, Set<String>>

    /** The facets whose values the user said are all named, of the item [itemId]. */
    fun completeOf(itemId: String): Set<String>

    /** Every item with a facet whose values are all named: they are few, the user says it by hand. */
    fun allComplete(): Map<String, Set<String>>
}

/** The [CompleteFacets] of the normalized works, in the dictionaries, as the items of the [source] see them. */
internal class WorkCompleteFacets(
    private val db: DSLContext,
    private val source: String,
    private val works: WorkRepository,
) : CompleteFacets {

    override fun setComplete(itemId: String, facet: String, complete: Boolean, now: Instant) {
        val c = WORK_FACET_COMPLETE
        val work = works.of(ItemKey(source, itemId), now)
        if (complete)
            db.insertInto(c)
                .set(c.WORK, work)
                .set(c.FACET, facet)
                .set(c.COMPLETED_AT, now)
                .onDuplicateKeyIgnore()
                .execute()
        else db.deleteFrom(c).where(c.WORK.eq(work), c.FACET.eq(facet)).execute()
    }

    /** The facets whose values are all named, of the source's items that meet the [condition]: item to facets. */
    private fun seenComplete(condition: Condition): Map<String, Set<String>> {
        val c = WORK_FACET_COMPLETE
        return db.select(SITE_WORK.ITEM, c.FACET)
            .from(SITE_WORK)
            .join(c)
            .on(c.WORK.eq(SITE_WORK.NORMALIZED))
            .where(SITE_WORK.SOURCE.eq(source), condition)
            .fetch()
            .groupBy({ it.value1()!! }, { it.value2()!! })
            .mapValues { it.value.toSet() }
    }

    override fun completeOf(ids: Collection<String>): Map<String, Set<String>> {
        val asked = ids.toHashSet()
        return if (asked.size > BATCH) allComplete().filterKeys { it in asked }
        else seenComplete(SITE_WORK.ITEM.`in`(asked))
    }

    override fun completeOf(itemId: String): Set<String> = completeOf(listOf(itemId))[itemId].orEmpty()

    override fun allComplete(): Map<String, Set<String>> = seenComplete(DSL.noCondition())

    private companion object {
        /** The ids asked for by their ids at most; of more, every word of the source is read once and picked from. */
        const val BATCH = 1000
    }
}
