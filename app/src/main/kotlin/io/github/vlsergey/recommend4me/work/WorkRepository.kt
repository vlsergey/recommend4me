package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.database.SourceItems
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_WORK
import io.github.vlsergey.recommend4me.item.ItemKey
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * The normalized works, in the dictionaries: every item of every site bound to one, at first of its own, merged when
 * the user says two are one work ([membership], with what goes with a work as it is merged, split or left by an item:
 * its grades, its mark of interest, the user's word); the user's word on two works that stay two ([relations]).
 */
class WorkRepository(private val db: DSLContext) {

    /** The items a work is made of, changed, and what goes with the work as it is. */
    val membership = WorkMembership(db)

    /** The user's word on two works that stay two. */
    val relations = WorkRelations(db)

    /** Those of the [items] of the [source] bound to a normalized work, with the version kept of each. */
    fun boundAmong(source: String, items: Collection<String>): Map<String, String> =
        items
            .chunked(BATCH)
            .flatMap { chunk ->
                db.select(SITE_WORK.ITEM, SITE_WORK.VERSION)
                    .from(SITE_WORK)
                    .where(SourceItems.of(SITE_WORK.SOURCE, SITE_WORK.ITEM, source, chunk))
                    .fetch { it.value1()!! to it.value2()!! }
            }
            .toMap()

    /** Every item of the [source] bound to a normalized work: item to work. */
    fun allOf(source: String): Map<String, Long> =
        db.select(SITE_WORK.ITEM, SITE_WORK.NORMALIZED)
            .from(SITE_WORK)
            .where(SITE_WORK.SOURCE.eq(source))
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    /** The normalized works of the [items] of the [source]: item to work. */
    fun ofItems(source: String, items: Collection<String>): Map<String, Long> =
        items
            .distinct()
            .chunked(BATCH)
            .flatMap { chunk ->
                db.select(SITE_WORK.ITEM, SITE_WORK.NORMALIZED)
                    .from(SITE_WORK)
                    .where(SourceItems.of(SITE_WORK.SOURCE, SITE_WORK.ITEM, source, chunk))
                    .fetch { it.value1()!! to it.value2()!! }
            }
            .toMap()

    /**
     * The [items] of the [source], none bound yet, with their versions, bound to a normalized work each of its own, at
     * once.
     */
    fun bindNew(source: String, items: Map<String, String>, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        items.forEach { (item, version) ->
            val id =
                tx.insertInto(NORMALIZED_WORK)
                    .defaultValues()
                    .returningResult(NORMALIZED_WORK.ID)
                    .fetchOne()!!
                    .value1()!!
            tx.insertInto(SITE_WORK)
                .set(SITE_WORK.SOURCE, source)
                .set(SITE_WORK.ITEM, item)
                .set(SITE_WORK.VERSION, version)
                .set(SITE_WORK.NORMALIZED, id)
                .set(SITE_WORK.BOUND_AT, now)
                .execute()
        }
    }

    /** The version the item stands at now. */
    fun setVersion(key: ItemKey, version: String) {
        db.update(SITE_WORK)
            .set(SITE_WORK.VERSION, version)
            .where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id), SITE_WORK.VERSION.ne(version))
            .execute()
    }

    /** The normalized work of the item; one of its own bound now when it has none. */
    fun of(key: ItemKey, now: Instant): Long {
        boundTo(key)?.let {
            return it
        }
        bindNew(key.source, mapOf(key.id to ""), now)
        return boundTo(key)!!
    }

    private fun boundTo(key: ItemKey): Long? =
        db.select(SITE_WORK.NORMALIZED)
            .from(SITE_WORK)
            .where(SITE_WORK.SOURCE.eq(key.source), SITE_WORK.ITEM.eq(key.id))
            .fetchOne()
            ?.value1()

    /** The normalized works of [sources] of several items, with their items. */
    fun groups(sources: Collection<String>): Map<Long, List<ItemKey>> {
        val several =
            DSL.select(SITE_WORK.NORMALIZED).from(SITE_WORK).groupBy(SITE_WORK.NORMALIZED).having(DSL.count().gt(1))
        return db.select(SITE_WORK.NORMALIZED, SITE_WORK.SOURCE, SITE_WORK.ITEM)
            .from(SITE_WORK)
            .where(SITE_WORK.NORMALIZED.`in`(several), SITE_WORK.SOURCE.`in`(sources))
            .fetch()
            .groupBy({ it.value1()!! }, { ItemKey(it.value2()!!, it.value3()!!) })
    }

    /** The items of [works] among the [sources]. */
    fun itemsOf(works: Collection<Long>, sources: Collection<String>): Map<Long, List<ItemKey>> =
        works
            .distinct()
            .chunked(BATCH)
            .flatMap { chunk ->
                db.select(SITE_WORK.NORMALIZED, SITE_WORK.SOURCE, SITE_WORK.ITEM)
                    .from(SITE_WORK)
                    .where(SITE_WORK.NORMALIZED.`in`(chunk), SITE_WORK.SOURCE.`in`(sources))
                    .fetch { it.value1()!! to ItemKey(it.value2()!!, it.value3()!!) }
            }
            .groupBy({ it.first }, { it.second })

    private companion object {
        /** The items read by one query at most: a list of keys in a query, not a meaning. */
        const val BATCH = 1000
    }
}
