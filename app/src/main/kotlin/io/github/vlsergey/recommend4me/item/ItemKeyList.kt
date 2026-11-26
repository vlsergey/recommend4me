package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.Record4
import org.jooq.SelectJoinStep
import org.jooq.impl.DSL

/** What the list sorts and filters by. */
data class ItemKeys(val id: String, val version: String, val updatedAt: Instant, val firstSeenAt: Instant)

/**
 * THE KEYS OF THE ITEMS OF A SOURCE — what the list of a content type sorts and filters by: id, version, update, when
 * the application first saw it — of every item, a portion of them in the order of their ids, or of some by their ids.
 */
interface ItemKeyList {
    /** The keys of every item. */
    fun keys(): List<ItemKeys>

    /**
     * The keys of the items after the item [after] — from the first when null — in the order of their ids, [count] at
     * most: every item read a portion at a time, each a query by the table's key, no cursor held between them.
     */
    fun keysAfter(after: String?, count: Int): List<ItemKeys>

    /** The keys of the items [ids], a batch of them a query. */
    fun keysOf(ids: Collection<String>): List<ItemKeys>
}

/** The [ItemKeyList] of the source database [db]. */
internal class ItemKeyRepository(private val db: DSLContext) : ItemKeyList {

    override fun keys(): List<ItemKeys> = select().fetchSize(STREAMED_ROWS).fetch(::keysOf)

    override fun keysAfter(after: String?, count: Int): List<ItemKeys> =
        select()
            .where(after?.let { ITEM.SITE_ID.gt(it) } ?: DSL.noCondition())
            .orderBy(ITEM.SITE_ID)
            .limit(count)
            .fetch(::keysOf)

    override fun keysOf(ids: Collection<String>): List<ItemKeys> =
        ids.distinct().chunked(ITEM_BATCH).flatMap { chunk ->
            select().where(ITEM.SITE_ID.`in`(chunk)).fetch(::keysOf)
        }

    private fun select(): SelectJoinStep<Record4<String?, String?, Instant?, Instant?>> =
        db.select(ITEM.SITE_ID, ITEM.VERSION, ITEM.UPDATED_AT, ITEM.FIRST_SEEN_AT).from(ITEM)

    private fun keysOf(r: Record4<String?, String?, Instant?, Instant?>) =
        ItemKeys(r.value1()!!, r.value2()!!, r.value3()!!, r.value4()!!)
}
