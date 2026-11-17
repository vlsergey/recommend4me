package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.vector.Vectors
import java.sql.Connection
import java.sql.ResultSet
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * A row of a part's vector as the database kept it: the vector's number among the part's, its bytes, its text's hash.
 */
private class KeptRow(val number: Int, val vector: ByteArray, val hash: String?)

/**
 * THE TEXTS OF THE PARTS AND THE VECTORS OF THEIR WINDOWS AND PARAGRAPHS MOVED OUT OF THE DATABASE into the source's
 * store of parts ([PartStore], user 2026-11-17), once, before the next migration drops them (V20): every text, its hash
 * written where it had none; the windows of every part with their count ([PartRepository]); the paragraphs of every
 * part in their order, a number of none a gap. A migration of the application's own code, given the store it writes to
 * — not found among the migrations of the source's files ([io.github.vlsergey.recommend4me.database.Database]).
 */
@Suppress("ClassName")
class V19__parts_out_of_database(private val store: PartStore) : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val c = context.connection
        streamed(c, "SELECT item_id, part_id, content, content_hash FROM part WHERE content IS NOT NULL") { r ->
            val text = r.getString(CONTENT)
            store.putText(r.getString(ITEM), r.getString(PART), text)
            if (r.getString(CONTENT_HASH) == null) update(c, HASH, Vectors.hash(text), r)
        }
        byPart(
            c,
            "SELECT item_id, part_id, vec, window_no, NULL FROM part_vector ORDER BY item_id, part_id, window_no",
        ) { item, part, rows ->
            store.putWindows(item, part, rows.map { Vectors.fromHalf(it.vector) })
            update(c, WINDOWS, rows.size, item, part)
        }
        byPart(
            c,
            "SELECT item_id, part_id, vec, paragraph_no, text_hash FROM part_paragraph_vector " +
                "ORDER BY item_id, part_id, paragraph_no",
        ) { item, part, rows ->
            val size = rows.maxOf { it.number } + 1
            val vectors = arrayOfNulls<FloatArray>(size)
            val hashes = Array(size) { "" }
            rows.forEach {
                vectors[it.number] = Vectors.fromHalf(it.vector)
                hashes[it.number] = it.hash.orEmpty()
            }
            store.putParagraphs(item, part, vectors.toList(), hashes.toList())
        }
    }

    /** The rows of [sql] streamed, each to [each]. */
    private fun streamed(c: Connection, sql: String, each: (ResultSet) -> Unit) =
        c.createStatement().use { s ->
            s.fetchSize = STREAMED
            s.executeQuery(sql).use { r -> while (r.next()) each(r) }
        }

    /**
     * The rows of [sql] — an item, a part, a vector, its number, its text's hash — handed to [each] a part at a time,
     * streamed in the order of the part.
     */
    private fun byPart(c: Connection, sql: String, each: (item: String, part: String, rows: List<KeptRow>) -> Unit) {
        var at: Pair<String, String>? = null
        val rows = ArrayList<KeptRow>()
        fun flush() = at?.let { (item, part) -> each(item, part, rows.toList()) }
        streamed(c, sql) { r ->
            val place = r.getString(ITEM) to r.getString(PART)
            if (place != at) {
                flush()
                rows.clear()
                at = place
            }
            rows += KeptRow(r.getInt(NUMBER), r.getBytes(VECTOR), r.getString(TEXT_HASH))
        }
        flush()
    }

    /** The part's [column] set to [value]: the part of the row [r]. */
    private fun update(c: Connection, column: String, value: Any, r: ResultSet) =
        update(c, column, value, r.getString(ITEM), r.getString(PART))

    private fun update(c: Connection, column: String, value: Any, item: String, part: String) =
        c.prepareStatement("UPDATE part SET $column = ? WHERE item_id = ? AND part_id = ?").use { u ->
            u.setObject(1, value)
            u.setString(2, item)
            u.setString(PART_PARAMETER, part)
            u.executeUpdate()
        }

    private companion object {
        /** The rows streamed from the database at a time: memory, not meaning. */
        const val STREAMED = 1000

        /** The columns of the queries, by their place: an item, a part, then a text and its hash, or a vector's. */
        const val ITEM = 1
        const val PART = 2
        const val CONTENT = 3
        const val CONTENT_HASH = 4
        const val VECTOR = 3
        const val NUMBER = 4
        const val TEXT_HASH = 5

        /** The place of the part among the update's parameters. */
        const val PART_PARAMETER = 3

        const val HASH = "content_hash"
        const val WINDOWS = "windows"
    }
}
