package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.streamed
import org.jooq.DSLContext

/**
 * THE TEXTS OF THE PARTS OF EVERY ITEM OF ONE SOURCE, read whole, an item at a time: what a pass over every text of a
 * source reads — the words the manners are measured by ([io.github.vlsergey.recommend4me.manner.MannerWords]) —, no
 * more than one item's texts held at once.
 */
class ItemTexts(private val db: DSLContext) {

    /**
     * The texts of the parts of every item that has any, in the order of its parts: streamed in the order of the key —
     * the table's own, so nothing is sorted —, the parts of one item put in order as they come.
     */
    fun forEach(action: (itemId: String, texts: List<String>) -> Unit) {
        var item: String? = null
        val parts = ArrayList<Pair<Int, String>>()
        fun flush() {
            item?.let { id -> action(id, parts.sortedBy { it.first }.map { it.second }) }
            parts.clear()
        }
        db.streamed(
            "SELECT item_id, position, content FROM part WHERE content IS NOT NULL ORDER BY item_id",
            emptyList(),
        ) { r ->
            val id = r.getString(ITEM_COLUMN)
            if (id != item) {
                flush()
                item = id
            }
            parts += r.getInt(POSITION_COLUMN) to r.getString(CONTENT_COLUMN)
        }
        flush()
    }

    private companion object {
        /** The columns of the streamed query, by their place in it. */
        const val ITEM_COLUMN = 1
        const val POSITION_COLUMN = 2
        const val CONTENT_COLUMN = 3
    }
}
