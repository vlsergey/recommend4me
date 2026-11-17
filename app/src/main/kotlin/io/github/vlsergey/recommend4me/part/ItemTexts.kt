package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.streamed
import org.jooq.DSLContext

/**
 * THE TEXTS OF THE PARTS OF EVERY ITEM OF ONE SOURCE, read whole, an item at a time: what a pass over every text of a
 * source reads — the words the manners are measured by ([io.github.vlsergey.recommend4me.manner.MannerWords]) —, no
 * more than one item's texts held at once. The parts with a text listed by the database, their texts read of the
 * [store].
 */
class ItemTexts(private val db: DSLContext, private val store: PartStore) {

    /**
     * The texts of the parts of every item that has any, in the order of its parts: the parts streamed in the order of
     * the key — the table's own, so nothing is sorted —, the parts of one item put in order and read as they come.
     */
    fun forEach(action: (itemId: String, texts: List<String>) -> Unit) {
        var item: String? = null
        val parts = ArrayList<Pair<Int, String>>()
        fun flush() {
            item?.let { id ->
                val ordered = parts.sortedBy { it.first }.map { it.second }
                val texts = store.texts(id, ordered)
                ordered.mapNotNull { texts[it] }.takeIf { it.isNotEmpty() }?.let { action(id, it) }
            }
            parts.clear()
        }
        db.streamed(
            "SELECT item_id, position, part_id FROM part WHERE content_hash IS NOT NULL ORDER BY item_id",
            emptyList(),
        ) { r ->
            val id = r.getString(ITEM_COLUMN)
            if (id != item) {
                flush()
                item = id
            }
            parts += r.getInt(POSITION_COLUMN) to r.getString(PART_COLUMN)
        }
        flush()
    }

    private companion object {
        /** The columns of the streamed query, by their place in it. */
        const val ITEM_COLUMN = 1
        const val POSITION_COLUMN = 2
        const val PART_COLUMN = 3
    }
}
