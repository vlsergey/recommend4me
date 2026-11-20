package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.Database
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway

/**
 * A source's file of the time the texts were not counted as written, migrated: a part's text counted of the store, and
 * every item's parts summed into the item ([V26__item_text_size_filled]).
 */
class ItemTextSizeFilledTest {

    @Test
    fun `the parts kept before counted and every item summed`() {
        val folder = Files.createTempDirectory("text-size")
        val file = folder.resolve("source")
        val url = "jdbc:h2:file:${file.toAbsolutePath().toString().replace('\\', '/')}"
        // The file as it was: migrated to V25, a part with a text not counted, another counted, an item of none
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/source").target("25").load().migrate()
        PartStore(folder).use { store ->
            store.putText("w1", "p1", "Раз два три")
            DriverManager.getConnection(url, "sa", "").use { c ->
                c.createStatement().use { s ->
                    listOf("w1", "w2").forEach {
                        s.execute(
                            "INSERT INTO item (item_id, url, title, version, updated_at, first_seen_at) " +
                                "VALUES ('$it', 'u', 't', '', NOW(), NOW())"
                        )
                    }
                    s.execute(
                        "INSERT INTO part (item_id, part_id, position, content_hash) VALUES ('w1', 'p1', 0, 'h1')"
                    )
                    s.execute(
                        "INSERT INTO part (item_id, part_id, position, content_hash, chars, words) " +
                            "VALUES ('w1', 'p2', 1, 'h2', 100, 20)"
                    )
                }
            }
            Database("test/source", file, "source", code = listOf(V26__item_text_size_filled(store))).use { db ->
                val counts = PartCounts(db.dsl, store)
                assertEquals(mapOf("w1" to (23.0 to 111.0)), counts.counted())
            }
        }
    }
}
