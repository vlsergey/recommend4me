package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_FACET
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test

/**
 * A source's file of the time the texts of the works were rows of item_text, migrated: every work kept by a number of
 * the application's own, the one H2 kept its row by, its texts in columns of its row (V37-V39) — of every key the file
 * held, and of those the source's own migrations name; the tables of a work's data bound to it by that number
 * (V40-V42).
 */
class ItemsByInternalIdTest {

    @Test
    fun `the works of a file of before keyed by a number of their own, their texts in their rows`() {
        val folder = Files.createTempDirectory("items-by-internal-id")
        val file = folder.resolve("source")
        val url = "jdbc:h2:file:${file.toAbsolutePath().toString().replace('\\', '/')}"
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/source").target("36").load().migrate()
        DriverManager.getConnection(url, "sa", "").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "INSERT INTO item (item_id, url, title, version, updated_at, first_seen_at) " +
                        "VALUES ('w1', 'u1', 't1', '', NOW(), NOW()), ('w2', 'u2', 't2', '', NOW(), NOW())"
                )
                s.execute(
                    "INSERT INTO item_text (item_id, text_key, content) " +
                        "VALUES ('w1', 'annotation', 'A dragon'), ('w1', 'pairingsLine', 'A/B'), " +
                        "('w2', 'annotation', 'A knight')"
                )
                s.execute("INSERT INTO item_facet (item_id, facet, value_key, position) VALUES ('w1', 'tag', 'x', 0)")
                // The other stores named by the works' numbers, as the tool of the move marks it
                s.execute("CREATE TABLE works_numbered_elsewhere(at TIMESTAMP WITH TIME ZONE)")
            }
        }

        // The source of the tests names "annotation" and "pairings": the second a column no work had a text of
        Database("test/source", file, "source", listOf("classpath:db/test-sites")).use { db ->
            val items = ItemRepository(db.dsl, listOf("annotation", "pairingsLine", "pairings")) {}
            val w1 = items.idOf("w1")!!
            val w2 = items.idOf("w2")!!
            assertEquals(mapOf("annotation" to "A dragon", "pairingsLine" to "A/B"), items.texts(w1))
            assertEquals(mapOf("annotation" to "A knight"), items.texts(w2))
            items.setTexts(w2, mapOf("pairings" to "C/D"))
            assertEquals(mapOf("annotation" to "A knight", "pairings" to "C/D"), items.texts(w2))
            // A work's values bound to it by its number
            assertEquals(listOf("x"), items.facets(w1)["tag"])

            // A new work numbered after those moved
            db.dsl
                .insertInto(ITEM)
                .set(ITEM.SITE_ID, "w3")
                .set(ITEM.URL, "u3")
                .set(ITEM.TITLE, "t3")
                .set(ITEM.VERSION, "")
                .set(ITEM.UPDATED_AT, java.time.Instant.now())
                .set(ITEM.FIRST_SEEN_AT, java.time.Instant.now())
                .execute()
            val numbers =
                db.dsl.select(ITEM.SITE_ID, ITEM.INTERNAL_ID).from(ITEM).fetchMap(ITEM.SITE_ID, ITEM.INTERNAL_ID)
            assertEquals(3, numbers.values.toSet().size, "numbers $numbers")
            assertTrue(numbers.getValue("w3")!! > maxOf(numbers.getValue("w1")!!, numbers.getValue("w2")!!))

            // A work's values go with it
            items.remove(w1)
            assertEquals(0, db.dsl.fetchCount(ITEM_FACET, ITEM_FACET.ITEM_ID.eq(w1.toLong())))
            assertEquals(false, db.dsl.meta().tables.any { it.name.equals("item_text", true) })
        }
    }
}
