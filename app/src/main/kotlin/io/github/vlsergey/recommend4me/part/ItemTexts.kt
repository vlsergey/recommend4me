package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE TEXTS OF THE PARTS OF EVERY ITEM OF ONE SOURCE, read whole, an item at a time: what a pass over every text of a
 * source reads — the words the manners are measured by ([io.github.vlsergey.recommend4me.manner.MannerWords]) —, no
 * more than one item's texts held at once. The parts with a text listed by the database, their texts read of the
 * [store].
 */
class ItemTexts(private val db: DSLContext, private val store: PartStore) {

    /**
     * The texts of the parts of every item that has any, in the order of its parts: the parts listed in the order of
     * the key — the table's own, so nothing is sorted —, a [portion] of them a query, each going on after the last part
     * of the one before; the parts of one item put in order and read as they come. Not one query over the table: a pass
     * over ficbook's 4.6 million parts reads for half an hour, and a query held so long lost the chunks of the file the
     * writes of the meantime freed ("Chunk 572645 not found", 2026-11-25: every pass of the manners failed so since
     * 04:14, planned again and again, the works' stages waiting behind it).
     */
    fun forEach(portion: Int = PORTION, action: (itemId: Long, texts: List<String>) -> Unit) {
        var item: Long? = null
        val parts = ArrayList<Pair<Int, String>>()
        fun flush() {
            item?.let { id ->
                val ordered = parts.sortedBy { it.first }.map { it.second }
                val texts = store.texts(id.toString(), ordered)
                ordered.mapNotNull { texts[it] }.takeIf { it.isNotEmpty() }?.let { action(id, it) }
            }
            parts.clear()
        }
        var last: Listed? = null
        do {
            val listed = after(last, portion)
            listed.forEach { p ->
                if (p.itemId != item) {
                    flush()
                    item = p.itemId
                }
                parts += p.position to p.partId
            }
            last = listed.lastOrNull()
        } while (listed.size == portion)
        flush()
    }

    /** A part with a text as the database lists it. */
    private class Listed(val itemId: Long, val position: Int, val partId: String)

    /** The [portion] parts with a text after the part [last] — of none, the first — in the order of the key. */
    private fun after(last: Listed?, portion: Int): List<Listed> =
        db.select(PART.ITEM_ID, PART.POSITION, PART.PART_ID)
            .from(PART)
            .where(PART.CONTENT_HASH.isNotNull)
            .and(
                last?.let {
                    // The beginning of the range of the key, then past the part within the item
                    PART.ITEM_ID.ge(it.itemId).and(PART.ITEM_ID.gt(it.itemId).or(PART.PART_ID.gt(it.partId)))
                } ?: DSL.noCondition()
            )
            .orderBy(PART.ITEM_ID, PART.PART_ID)
            .limit(portion)
            .fetch { Listed(it.value1()!!, it.value2()!!, it.value3()!!) }

    private companion object {
        /** The parts a query lists: seconds of reading, not the whole table's half hour. */
        const val PORTION = 10_000
    }
}
