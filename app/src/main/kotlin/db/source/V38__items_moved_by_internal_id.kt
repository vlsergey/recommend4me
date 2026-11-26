package db.source

import java.sql.Connection
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V38__items_moved_by_internal_id::class.java)

/**
 * Every item moved into the table keyed by a number of the application's own (V37), its texts of item_text into columns
 * of its row: a column `SITE_TEXT_<KEY>` of every key item_text holds, the key's words in capitals parted by `_`
 * (`pairingsLine` → `SITE_TEXT_PAIRINGS_LINE`), as the source's own migrations name the texts its works have
 * (`item/ItemTexts.kt`, `SiteTexts.columnOf`). An item's number is the number H2 kept its row by: the rows moved in
 * their order in the file, a portion of the numbers at a time, each kept as it is done — of ficbook's 1.4 million works
 * no transaction holds them all; a migration broken off goes on after the last number moved. V39 puts the table in the
 * place of item.
 */
@Suppress("ClassName")
class V38__items_moved_by_internal_id : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val keys = c.strings("SELECT DISTINCT text_key FROM item_text")
        keys.forEach { key ->
            c.createStatement().use {
                it.execute("ALTER TABLE item_new ADD COLUMN IF NOT EXISTS ${columnOf(key)} CHARACTER VARYING")
            }
        }
        val (first, last) = c.range("SELECT MIN(_ROWID_), MAX(_ROWID_) FROM item")
        val done = c.range("SELECT MAX(internal_id), COUNT(*) FROM item_new")
        val count = c.range("SELECT COUNT(*), 0 FROM item").first
        val texts =
            keys.joinToString("") { key ->
                ", (SELECT CAST(t.content AS CHARACTER VARYING) FROM item_text t " +
                    "WHERE t.item_id = i.item_id AND t.text_key = '${key.replace("'", "''")}')"
            }
        val move =
            "INSERT INTO item_new(internal_id, site_id, $HEAD${keys.joinToString("") { ", " + columnOf(it) }}) " +
                "SELECT i._ROWID_, i.item_id, ${HEAD.split(", ").joinToString(", ") { "i.$it" }}$texts " +
                "FROM item i WHERE i._ROWID_ BETWEEN ? AND ?"
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            val started = System.nanoTime()
            var moved = done.second
            var portion = 0
            c.prepareStatement(move).use { s ->
                var from = maxOf(first, done.first + 1)
                while (from <= last) {
                    s.setLong(1, from)
                    s.setLong(2, from + PORTION - 1)
                    moved += s.executeUpdate()
                    c.commit()
                    from += PORTION
                    // A long start followed in the log: the file is locked by the migration, nothing else tells
                    if (++portion % TOLD_EVERY == 0)
                        log.info("{} of {} items moved in {} s", moved, count, (System.nanoTime() - started) / NANOS)
                }
            }
            val next = c.range("SELECT COALESCE(MAX(internal_id), 0) + 1, 0 FROM item_new").first
            c.createStatement().use { it.execute("ALTER TABLE item_new ALTER COLUMN internal_id RESTART WITH $next") }
            c.commit()
            log.info("{} items moved in {} s", moved, (System.nanoTime() - started) / NANOS)
        } finally {
            c.autoCommit = autoCommit
        }
    }

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { s ->
            s.executeQuery(sql).use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        }

    /** The two numbers the one row of [sql] gives; nought of a null. */
    private fun Connection.range(sql: String): Pair<Long, Long> =
        createStatement().use { s ->
            s.executeQuery(sql).use { r ->
                r.next()
                r.getLong(1) to r.getLong(2)
            }
        }

    private companion object {
        /** The numbers of the rows moved at a time: of ficbook's file a few seconds of the disk. */
        const val PORTION = 20_000L

        /** How many portions go between two lines of the log. */
        const val TOLD_EVERY = 10

        const val NANOS = 1_000_000_000L

        /** The columns of an item's head moved as they are. */
        const val HEAD =
            "url, title, version, updated_at, first_seen_at, text_words, text_chars, text_letters_1, text_letters_2, " +
                "text_letters_3, text_punctuation_1, text_punctuation_2, text_spaces, text_breaks, text_other, " +
                "changed_at, data_at, chapters_at, reviews_at, pictures_at"

        /** The column of the site's text [key], as `SiteTexts.columnOf` names it: kept here as it was. */
        fun columnOf(key: String): String =
            "SITE_TEXT_" + key.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase().replace(Regex("[^A-Z0-9]"), "_")
    }
}
