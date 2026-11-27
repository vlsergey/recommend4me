package db.source

import java.sql.Connection
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V44__texts_read_whole::class.java)

/**
 * The vectors of the texts read whole (owner, 2026-11-27: "the windows go; the table keeps the vector of the whole
 * description, no windows"): every row of `text_vector` copied into its table of no windows (`text_vector_whole`), the
 * columns of the windows left behind (`windows`, `windows_by`). A text of more than one window — or of windows never
 * counted — had a vector of the mean of its pages and pieces and full-text vectors of its windows: its hash blanked,
 * its pieces and full-text vector told not made, so that it is encoded again, whole; a text of one window was read
 * whole already, and is kept as it is. A portion of the rows at a time, in their order in the file, each kept as it is
 * done, the log told how far; a copy broken off goes on after the last portion kept ([COPIED]). V45 puts the table in
 * the place of the one before, the table of the windows gone with it.
 */
@Suppress("ClassName")
class V44__texts_read_whole : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            copy(c)
        } finally {
            c.autoCommit = autoCommit
        }
    }

    private fun copy(c: Connection) {
        val insert =
            "INSERT INTO text_vector_whole(item_id, text_key, encoder, text_hash, vec, pieces, full_text) " +
                "SELECT item_id, text_key, encoder, CASE WHEN windows = 1 THEN text_hash ELSE '' END, vec, " +
                "CASE WHEN windows = 1 THEN pieces END, CASE WHEN windows = 1 THEN full_text END " +
                "FROM text_vector WHERE _ROWID_ BETWEEN ? AND ?"
        val last = c.number("SELECT COALESCE(MAX(_ROWID_), 0) FROM text_vector")
        val rows = c.number("SELECT COUNT(*) FROM text_vector")
        val again = c.number("SELECT COUNT(*) FROM text_vector WHERE windows IS NULL OR windows <> 1")
        var from = c.number("SELECT COALESCE(MAX(upto), 0) FROM $COPIED") + 1
        val started = System.nanoTime()
        var portion = 0
        c.prepareStatement(insert).use { s ->
            c.prepareStatement("MERGE INTO $COPIED(id, upto) KEY(id) VALUES (1, ?)").use { kept ->
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
                        log.info("text_vector: rows up to {} of {} copied in {} s", from - 1, last, seconds(started))
                }
            }
        }
        log.info(
            "text_vector: {} rows copied in {} s, {} of them of more than one window to be encoded again, whole",
            rows,
            seconds(started),
            again,
        )
    }

    private fun seconds(started: Long) = (System.nanoTime() - started) / NANOS

    private fun Connection.number(sql: String): Long =
        createStatement().use { s ->
            s.executeQuery(sql).use { r ->
                r.next()
                r.getLong(1)
            }
        }

    private companion object {
        /** Where the copy got to: kept with the rows, the copy broken off going on from there. */
        const val COPIED = "text_vector_copied"

        /** The rows' numbers in the file copied at a time: of ficbook's two million, a few seconds each. */
        const val PORTION = 100_000L

        /** How many portions go between two lines of the log. */
        const val TOLD_EVERY = 5

        const val NANOS = 1_000_000_000L
    }
}
