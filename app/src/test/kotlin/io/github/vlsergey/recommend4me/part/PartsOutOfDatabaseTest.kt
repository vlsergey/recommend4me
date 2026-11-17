package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.vector.Vectors
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test

/**
 * A source's file of the time the texts of the parts and their vectors were in it, migrated: the texts, the windows and
 * the paragraphs moved into the store of parts ([V19__parts_out_of_database]), the hash of a text kept without one
 * written, the windows counted — and the old columns and tables gone (V20).
 */
class PartsOutOfDatabaseTest {

    @Test
    fun `the texts and the vectors of a file of before moved into the store`() {
        val folder = Files.createTempDirectory("parts-moved")
        val file = folder.resolve("source")
        val url = "jdbc:h2:file:${file.toAbsolutePath().toString().replace('\\', '/')}"
        // The file as it was before: migrated to V17, a part with a text of no hash, its windows and paragraphs
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/source").target("17").load().migrate()
        java.sql.DriverManager.getConnection(url, "sa", "").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO item (item_id, url, title, version, updated_at, first_seen_at) " +
                        "VALUES ('w1', 'u', 't', '', NOW(), NOW())"
                )
                s.execute(
                    "INSERT INTO part (item_id, part_id, position, content, encoder) " +
                        "VALUES ('w1', 'p1', 0, 'Lorem ipsum', 'enc')"
                )
            }
            c.prepareStatement("INSERT INTO part_vector (item_id, part_id, window_no, vec) VALUES ('w1', 'p1', ?, ?)")
                .use { p ->
                    listOf(floatArrayOf(1f, 2f), floatArrayOf(3f, 4f)).forEachIndexed { i, v ->
                        p.setInt(1, i)
                        p.setBytes(2, Vectors.half(v))
                        p.executeUpdate()
                    }
                }
            c.prepareStatement(
                    "INSERT INTO part_paragraph_vector (item_id, part_id, paragraph_no, vec, text_hash) " +
                        "VALUES ('w1', 'p1', 1, ?, 'h1')"
                )
                .use { p ->
                    p.setBytes(1, Vectors.half(floatArrayOf(5f, 6f)))
                    p.executeUpdate()
                }
        }

        PartStore(folder).use { store ->
            Database("test/source", file, "source", code = listOf(V19__parts_out_of_database(store))).use { db ->
                assertEquals("Lorem ipsum", store.text("w1", "p1"))
                assertContentEquals(floatArrayOf(3f, 4f), store.windows("w1", listOf("p1")).getValue("p1")[1])
                val paragraphs = store.paragraphs("w1", listOf("p1")).getValue("p1")
                assertNull(paragraphs[0])
                assertEquals("h1", paragraphs[1]!!.hash)
                val repository = PartRepository(db.dsl, store)
                assertEquals(1, repository.countWithText())
                assertEquals("Lorem ipsum", repository.text("w1", "p1")!!.second)
                assertEquals(2, repository.windows.windowCountsOf(listOf("w1"), "enc")["w1"])
                // The columns and the tables of the texts and the vectors gone from the file
                val tables = db.dsl.meta().tables.map { it.name.lowercase() }
                assertEquals(false, "part_vector" in tables || "part_paragraph_vector" in tables)
                assertEquals(
                    false,
                    db.dsl.meta().getTables("PART").single().fields().any { it.name.equals("content", true) },
                )
            }
        }
    }
}
