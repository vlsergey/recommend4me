package io.github.vlsergey.recommend4me.part

import java.sql.Connection
import java.sql.ResultSet
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V26__item_text_size_filled::class.java)

/**
 * THE WORDS AND THE CHARACTERS OF THE TEXTS KEPT BEFORE THEY WERE COUNTED AS WRITTEN (V25): every part whose text is
 * kept and not counted counted of the [store], and every item's every part's summed into item.text_words,
 * item.text_chars — an item written only when its sum is not what it holds, so a file filled before is left as it is. A
 * portion at a time, each kept as it is done, how far told in the log (on the owner's data, 2026-11-20: 34 parts and
 * 1.38 million items of ficbook in 60 s). A migration of the application's own code, given the store it reads — not
 * found among the migrations of the source's files ([io.github.vlsergey.recommend4me.database.Database]).
 */
@Suppress("ClassName")
class V26__item_text_size_filled(private val store: PartStore) : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            val started = System.currentTimeMillis()
            val counted = countParts(c)
            val summed = sumItems(c)
            log.info(
                "{} parts counted and {} items summed anew in {} ms",
                counted,
                summed,
                System.currentTimeMillis() - started,
            )
        } finally {
            c.autoCommit = autoCommit
        }
    }

    /** Every part whose text is kept and not counted, counted; how many. */
    private fun countParts(c: Connection): Int {
        val todo = ArrayList<Pair<String, String>>()
        streamed(c, "SELECT item_id, part_id FROM part WHERE content_hash IS NOT NULL AND chars IS NULL") { r ->
            todo += r.getString(1) to r.getString(2)
        }
        var done = 0
        c.prepareStatement("UPDATE part SET chars = ?, words = ? WHERE item_id = ? AND part_id = ?").use { u ->
            todo.chunked(PORTION).forEach { portion ->
                portion.forEach { (item, part) ->
                    val text = store.text(item, part) ?: return@forEach
                    listOf(text.length, WordCount.of(text), item, part).forEachIndexed { k, v -> u.setObject(k + 1, v) }
                    u.addBatch()
                    done++
                }
                u.executeBatch()
                c.commit()
            }
        }
        return done
    }

    /** Every item's parts summed, written where the item holds another sum; how many were written. */
    private fun sumItems(c: Connection): Int {
        val sums = HashMap<String, LongArray>()
        streamed(c, "SELECT item_id, words, chars FROM part WHERE chars IS NOT NULL") { r ->
            val sum = sums.getOrPut(r.getString(1)) { LongArray(2) }
            sum[0] += r.getLong(2)
            sum[1] += r.getLong(INDEX_CHARS)
        }
        val stale = ArrayList<Pair<String, LongArray>>()
        streamed(c, "SELECT item_id, text_words, text_chars FROM item") { r ->
            val sum = sums[r.getString(1)]
            val words = r.getObject(2) as Number?
            val chars = r.getObject(INDEX_CHARS) as Number?
            if (sum != null && (words?.toLong() != sum[0] || chars?.toLong() != sum[1])) stale += r.getString(1) to sum
        }
        val started = System.currentTimeMillis()
        c.prepareStatement("UPDATE item SET text_words = ?, text_chars = ? WHERE item_id = ?").use { u ->
            stale.chunked(PORTION).forEachIndexed { i, portion ->
                // A long start followed in the log: the file locked by the migration, nothing else tells
                if (i % TOLD_EVERY == 0 && i > 0)
                    log.info(
                        "the texts' sizes of {} of {} items written in {} s",
                        i * PORTION,
                        stale.size,
                        (System.currentTimeMillis() - started) / MILLIS,
                    )
                portion.forEach { (item, sum) ->
                    listOf(sum[0].toInt(), sum[1].toInt(), item).forEachIndexed { k, v -> u.setObject(k + 1, v) }
                    u.addBatch()
                }
                u.executeBatch()
                c.commit()
            }
        }
        return stale.size
    }

    /** The rows of [sql] streamed, each to [each]. */
    private fun streamed(c: Connection, sql: String, each: (ResultSet) -> Unit) =
        c.createStatement().use { s ->
            s.fetchSize = STREAMED
            s.executeQuery(sql).use { r -> while (r.next()) each(r) }
        }

    private companion object {
        /** The rows written and committed at a time. */
        const val PORTION = 5000

        /** The portions between two lines of the log of how far the migration is. */
        const val TOLD_EVERY = 20

        /** The rows streamed from the database at a time: memory, not meaning. */
        const val STREAMED = 10_000

        /** The place of the characters among the columns of the queries. */
        const val INDEX_CHARS = 3

        const val MILLIS = 1000
    }
}
