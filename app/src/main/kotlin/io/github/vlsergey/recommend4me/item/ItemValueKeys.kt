package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_FACET
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** THE KEY OF A VALUE OF THE ITEMS GIVEN ANOTHER: one value of the site known by two keys made one ([NamedValues]). */
interface ItemValueKeys {
    /**
     * The value [from] of the facet made [to] on every item that has it, in its place and with the name the item gave
     * it; an item that has [to] already loses [from]. The items changed, told as written.
     */
    fun rekey(facet: String, from: String, to: String): List<String>

    /**
     * The [keys] of the facet some item has: one lookup a key by the index of the values — H2 matches a list bound to
     * the query against every row of the facet, 5 s of ficbook's authors (2026-11-20).
     */
    fun given(facet: String, keys: Collection<String>): List<String>
}

/** The [ItemValueKeys] of the source database [db]: [changed] told of every item whose values were keyed anew. */
internal class ItemValueKeyRepository(
    private val db: DSLContext,
    private val changed: (Collection<String>) -> Unit,
) : ItemValueKeys {

    override fun rekey(facet: String, from: String, to: String): List<String> {
        val f = ITEM_FACET
        val items =
            db.select(f.ITEM_ID).from(f).where(f.FACET.eq(facet), f.VALUE_KEY.eq(from)).fetch(f.ITEM_ID).filterNotNull()
        if (items.isEmpty()) return items
        val key = to.take(VALUE_KEY_LENGTH)
        db.transaction { c ->
            val tx = DSL.using(c)
            val other = f.`as`("other")
            tx.deleteFrom(f)
                .where(f.FACET.eq(facet), f.VALUE_KEY.eq(from))
                .andExists(
                    DSL.selectOne()
                        .from(other)
                        .where(other.ITEM_ID.eq(f.ITEM_ID), other.FACET.eq(facet), other.VALUE_KEY.eq(key))
                )
                .execute()
            tx.update(f).set(f.VALUE_KEY, key).where(f.FACET.eq(facet), f.VALUE_KEY.eq(from)).execute()
        }
        changed(items)
        return items
    }

    override fun given(facet: String, keys: Collection<String>): List<String> =
        keys.distinct().filter { db.fetchExists(ITEM_FACET, ITEM_FACET.FACET.eq(facet), ITEM_FACET.VALUE_KEY.eq(it)) }
}
