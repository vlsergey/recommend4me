package db.ficbook

import io.github.vlsergey.recommend4me.source.ficbook.Ficbook
import java.io.ByteArrayOutputStream
import java.sql.Connection
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.api.configuration.Configuration
import org.flywaydb.core.api.migration.Context

/**
 * The tags of every work keyed by their names are given the ids the kept pages say they have — the links of a work's
 * tags, the tags the site's search answered and their synonyms — a name of no id found keyed by the name alone.
 */
class TagsByTheirIdsTest {

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private fun gzip(text: String): ByteArray =
        ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(text.toByteArray()) } }.toByteArray()

    private fun Connection.run(sql: String, vararg values: Any) =
        prepareStatement(sql).use { s ->
            values.forEachIndexed { i, v -> s.setObject(i + 1, v) }
            s.executeUpdate()
        }

    private fun Connection.rows(sql: String): List<List<String?>> =
        prepareStatement(sql).use { s ->
            s.executeQuery().use { r ->
                buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) }
            }
        }

    /** The tables of a source's file the migration rewrites, as far as it reads them. */
    private fun Connection.tables() {
        val key = "facet VARCHAR, value_key VARCHAR"
        run("CREATE TABLE item_facet (item_id VARCHAR, $key, position INT, PRIMARY KEY (item_id, facet, value_key))")
        run("CREATE TABLE facet_value ($key, name VARCHAR, PRIMARY KEY (facet, value_key))")
        run("CREATE TABLE facet_value_description ($key, description VARCHAR, PRIMARY KEY (facet, value_key))")
        run("CREATE TABLE facet_value_vector ($key, vec VARBINARY, PRIMARY KEY (facet, value_key))")
        run("CREATE TABLE facet_value_synonym (facet VARCHAR, synonym_key VARCHAR, value_key VARCHAR)")
        run("CREATE TABLE captured_page (url VARCHAR PRIMARY KEY, html BLOB)")
    }

    /**
     * A kept work's page and an answer of the search of tags; the works as the dump and the pages wrote them, the tags
     * by their names in lower case.
     */
    private fun Connection.kept() {
        run("INSERT INTO captured_page VALUES (?, ?)", "https://ficbook.net/readfic/1", gzip(page("readfic.html")))
        run("INSERT INTO captured_page VALUES (?, ?)", Ficbook.tagSearch("харассмент"), gzip(page("tags-search.json")))
        mapOf(
                "w1" to listOf("ангст", "hurt/comfort", "харассмент", "домогательство", "неведомое"),
                "w2" to listOf("ангст", "слоуберн"),
                // Keyed by the id already by a migration broken off, and by the name still: one tag
                "w3" to listOf("1665", "ангст"),
            )
            .forEach { (work, tags) ->
                tags.forEachIndexed { i, t -> run("INSERT INTO item_facet VALUES (?, 'tag', ?, ?)", work, t, i) }
            }
        run("INSERT INTO item_facet VALUES ('w1', 'author', 'ангст', 0)")
        mapOf("ангст" to "Ангст", "неведомое" to "Неведомое", "хёрт" to "Хёрт").forEach { (k, n) ->
            run("INSERT INTO facet_value VALUES ('tag', ?, ?)", k, n)
        }
        run("INSERT INTO facet_value_description VALUES ('tag', 'ангст', 'Страдания')")
        run("INSERT INTO facet_value_synonym VALUES ('tag', 'хёрт', 'hurt/comfort')")
    }

    @Test
    fun `the tags of every work keyed by their ids, a name of none found by the name`() {
        DriverManager.getConnection("jdbc:h2:mem:tags-by-ids;DB_CLOSE_DELAY=-1").use { c ->
            c.tables()
            c.kept()
            V4__tags_by_their_ids()
                .migrate(
                    object : Context {
                        override fun getConfiguration(): Configuration = error("not read")

                        override fun getConnection() = c
                    }
                )

            assertEquals(
                listOf(
                    listOf("w1", "1665"),
                    listOf("w1", "1682"),
                    // Харассмент and Домогательство, its synonym: one tag
                    listOf("w1", "477"),
                    listOf("w1", "name:Неведомое"),
                    listOf("w2", "1665"),
                    listOf("w2", "19"),
                    listOf("w3", "1665"),
                ),
                c.rows("SELECT item_id, value_key FROM item_facet WHERE facet = 'tag' ORDER BY item_id, value_key"),
            )
            // Another facet's value of the same key stays
            assertEquals(listOf(listOf("ангст")), c.rows("SELECT value_key FROM item_facet WHERE facet = 'author'"))
            assertEquals(
                listOf(listOf("1665", "Страдания")),
                c.rows("SELECT value_key, description FROM facet_value_description"),
            )
            // The names of the tags of the works by their ids; the names of no work and the synonyms gone
            assertEquals(
                listOf(listOf("1665", "Ангст"), listOf("name:Неведомое", "Неведомое")),
                c.rows(
                    "SELECT value_key, name FROM facet_value WHERE value_key IN ('1665', 'name:Неведомое') ORDER BY 1"
                ),
            )
            assertEquals(emptyList(), c.rows("SELECT * FROM facet_value WHERE value_key = 'хёрт'"))
            assertEquals(emptyList(), c.rows("SELECT * FROM facet_value_synonym"))
        }
    }
}
