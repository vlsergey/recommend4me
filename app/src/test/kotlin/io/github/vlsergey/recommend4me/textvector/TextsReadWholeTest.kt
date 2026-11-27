package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.database.Database
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test

/**
 * A source's file of the time the texts were read in windows, migrated (V43-V45): a text of one window kept as it was,
 * read whole already; a text of more windows, or of windows never counted, told to be encoded again, whole — its hash
 * blanked, its pieces and full-text vector not made —; the windows gone; what is held of a vector to know it made read
 * of the index of those alone.
 */
class TextsReadWholeTest {

    @Test
    fun `the texts of more than one window are encoded again, whole, the windows gone`() {
        val file = fileOfWindows()

        Database("test/source", file, "source", listOf("classpath:db/test-sites")).use { db ->
            checkRead(db)
        }
    }

    /**
     * A source's file of the last version of the windows (V42): a work's title of one window, its annotation of three,
     * its notes of windows never counted, a window kept.
     */
    private fun fileOfWindows(): Path {
        val file = Files.createTempDirectory("texts-read-whole").resolve("source")
        val url = "jdbc:h2:file:${file.toAbsolutePath().toString().replace('\\', '/')}"
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/source").target("42").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO item (site_id, url, title, version, updated_at, first_seen_at) " +
                        "VALUES ('w1', 'u1', 't1', '', NOW(), NOW())"
                )
                val id =
                    s.executeQuery("SELECT internal_id FROM item").use {
                        it.next()
                        it.getLong(1)
                    }
                s.execute(
                    "INSERT INTO text_vector (item_id, text_key, encoder, text_hash, vec, windows, windows_by, " +
                        "pieces, full_text) VALUES " +
                        "($id, 'title', 'e', 'h-title', X'0102', 1, 'e/w', 'p1', 'f1'), " +
                        "($id, 'annotation', 'e', 'h-annotation', X'0304', 3, 'e/w', 'p1', 'f1'), " +
                        "($id, 'notes', 'e', 'h-notes', X'0506', NULL, NULL, NULL, NULL)"
                )
                s.execute(
                    "INSERT INTO text_window (item_id, text_key, window_no, vec) VALUES ($id, 'annotation', 0, X'07')"
                )
            }
        }
        return file
    }

    /**
     * The file migrated: the text of one window as it was, the others to be encoded again; no windows; the marks read
     * of their index.
     */
    private fun checkRead(db: Database) {
        val rows =
            db.dsl
                .fetch("SELECT text_key, text_hash, vec, pieces, full_text FROM text_vector ORDER BY text_key")
                .associate { r ->
                    r.get(0, String::class.java) to
                        listOf(
                            r.get(1, String::class.java),
                            r.get(2, ByteArray::class.java).toList().toString(),
                            r.get(3, String::class.java),
                            r.get(4, String::class.java),
                        )
                }
        assertEquals(listOf("h-title", "[1, 2]", "p1", "f1"), rows["title"], "of one window, as it was")
        assertEquals(listOf("", "[3, 4]", null, null), rows["annotation"], "of three windows, to be made again")
        assertEquals(listOf("", "[5, 6]", null, null), rows["notes"], "of windows never counted, to be made again")
        val tables = db.dsl.meta().tables.map { it.name.lowercase() }
        assertFalse("text_window" in tables, "$tables")
        assertFalse("text_vector_whole" in tables || "text_vector_copied" in tables, "$tables")
        val columns =
            db.dsl
                .meta()
                .tables
                .single { it.name.equals("text_vector", true) }
                .fields()
                .map {
                    it.name.lowercase()
                }
        assertFalse("windows" in columns || "windows_by" in columns, "$columns")
        val plan =
            db.dsl
                .fetch(
                    "EXPLAIN SELECT item_id, text_key, encoder, text_hash, pieces, full_text FROM text_vector " +
                        "WHERE item_id IN (1, 2)"
                )
                .single()
                .get(0, String::class.java)
        assertTrue("TEXT_VECTOR_KEPT" in plan, plan)
    }
}
