package db.source

import java.sql.Connection
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V41__item_data_moved_by_number::class.java)

/**
 * The rows of every table of a work's data moved into its table bound to the work by the application's own number
 * (V40): the site's id of a row given the work's number of the item of that id — read of the unique index of the site's
 * ids, which holds the number the item's row is kept by — a row of an id no item has left behind, and told. A portion
 * of the rows at a time, in their order in the file, each kept as it is done, the log told how far; a move broken off
 * goes on after the last portion kept ([MOVED]). V42 puts the tables in the place of those of before.
 *
 * Every other store the works are named in by their ids — the corrections, the grades, the dictionaries, the queue, the
 * models, the indexes of the list and of the search, the store of the parts — is rewritten by the numbers once by a
 * tool, the application stopped, before this runs: a file of works it has not marked ([REKEYED]) is not moved.
 */
@Suppress("ClassName")
class V41__item_data_moved_by_number : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val works = c.number("SELECT COUNT(*) FROM item")
        val marked = c.number("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = '$REKEYED'") > 0
        check(works == 0L || marked) {
            "the other stores of the works of this file are not named by the works' numbers yet: " +
                "the tool of the move (tools/rekey) is run first, the application stopped"
        }
        c.createStatement().use {
            it.execute("CREATE TABLE IF NOT EXISTS $MOVED(table_name CHARACTER VARYING(100) PRIMARY KEY, upto BIGINT)")
        }
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            TABLES.forEach { move(c, it) }
        } finally {
            c.autoCommit = autoCommit
        }
    }

    /** The rows of the [table] moved into its table by the works' numbers, from where a move broken off stopped. */
    private fun move(c: Connection, table: String) {
        val columns =
            c.strings(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '${table.uppercase()}' " +
                    "ORDER BY ORDINAL_POSITION"
            )
        val selected = columns.joinToString(", ") { if (it == "ITEM_ID") "i.internal_id" else "x.$it" }
        // A page kept of no work keeps none; a row of any other table of an id no item has is left behind
        val join = if (table == "captured_page") "LEFT JOIN" else "JOIN"
        val insert =
            "INSERT INTO ${table}_by_number(${columns.joinToString(", ")}) SELECT $selected FROM $table x " +
                "$join item i ON i.site_id = x.item_id WHERE x._ROWID_ BETWEEN ? AND ?"
        val last = c.number("SELECT COALESCE(MAX(_ROWID_), 0) FROM $table")
        val rows = c.number("SELECT COUNT(*) FROM $table")
        var from = c.number("SELECT COALESCE(MAX(upto), 0) FROM $MOVED WHERE table_name = '$table'") + 1
        val started = System.nanoTime()
        var portion = 0
        c.prepareStatement(insert).use { s ->
            c.prepareStatement("MERGE INTO $MOVED(table_name, upto) KEY(table_name) VALUES ('$table', ?)").use { kept ->
                while (from <= last) {
                    s.setLong(1, from)
                    s.setLong(2, from + PORTION - 1)
                    s.executeUpdate()
                    kept.setLong(1, from + PORTION - 1)
                    kept.executeUpdate()
                    c.commit()
                    from += PORTION
                    // A long start followed in the log: the file is locked by the migration, nothing else tells
                    if (++portion % TOLD_EVERY == 0)
                        log.info("{}: rows up to {} of {} moved in {} s", table, from - 1, last, seconds(started))
                }
            }
        }
        val moved = c.number("SELECT COUNT(*) FROM ${table}_by_number")
        log.info(
            "{}: {} rows of {} moved in {} s, {} of no work left behind",
            table,
            moved,
            rows,
            seconds(started),
            rows - moved,
        )
    }

    private fun seconds(started: Long) = (System.nanoTime() - started) / NANOS

    private fun Connection.strings(sql: String): List<String> =
        createStatement().use { s ->
            s.executeQuery(sql).use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        }

    private fun Connection.number(sql: String): Long =
        createStatement().use { s ->
            s.executeQuery(sql).use { r ->
                r.next()
                r.getLong(1)
            }
        }

    companion object {
        /** The table the tool of the move makes in a file whose works it named by their numbers everywhere else. */
        const val REKEYED = "WORKS_NUMBERED_ELSEWHERE"

        /** Where the move of every table got to: kept with the rows, the move broken off going on from there. */
        private const val MOVED = "item_data_moved"

        /** The tables of a work's data, in the order of V40. */
        private val TABLES =
            listOf(
                "item_version",
                "item_picture_vector",
                "set_vector",
                "review",
                "picture",
                "item_facet",
                "item_number",
                "part",
                "item_manner",
                "text_window",
                "series_place",
                "text_vector",
                "captured_page",
            )

        /** The rows' numbers in the file moved at a time: of ficbook's ten million values a few seconds each. */
        private const val PORTION = 100_000L

        /** How many portions go between two lines of the log. */
        private const val TOLD_EVERY = 10

        private const val NANOS = 1_000_000_000L
    }
}
