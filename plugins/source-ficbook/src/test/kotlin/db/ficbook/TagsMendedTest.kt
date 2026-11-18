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
 * A work's tag keyed by the id of a synonym — the synonyms' ids count the synonyms, not the tags — is keyed by its tag,
 * by the name its page wrote; every tag named as the site names it, the synonyms and the numbers of no work gone.
 */
class TagsMendedTest {

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

    /** The tables of a source's file the migration rewrites, as V4 left them: the works' tags with their names. */
    private fun Connection.tables() {
        val key = "facet VARCHAR, value_key VARCHAR"
        run(
            "CREATE TABLE item_facet (item_id VARCHAR, $key, position INT, name VARCHAR, " +
                "PRIMARY KEY (item_id, facet, value_key))"
        )
        run("CREATE TABLE facet_value ($key, name VARCHAR, PRIMARY KEY (facet, value_key))")
        run("CREATE TABLE facet_value_description ($key, description VARCHAR, PRIMARY KEY (facet, value_key))")
        run("CREATE TABLE facet_value_vector ($key, vec VARBINARY, PRIMARY KEY (facet, value_key))")
        run("CREATE TABLE facet_value_synonym (facet VARCHAR, synonym_key VARCHAR, value_key VARCHAR)")
        run("CREATE TABLE captured_page (url VARCHAR PRIMARY KEY, html BLOB)")
        run("INSERT INTO captured_page VALUES (?, ?)", "https://ficbook.net/readfic/1", gzip(page("readfic.html")))
        run("INSERT INTO captured_page VALUES (?, ?)", Ficbook.tagSearch("харассмент"), gzip(page("tags-search.json")))
    }

    @Test
    fun `a work's tag keyed by a synonym's id is keyed by its tag, named as the site names it`() {
        DriverManager.getConnection("jdbc:h2:mem:tags-mended;DB_CLOSE_DELAY=-1").use { c ->
            c.tables()
            // V4 keyed Домогательство by its synonym's id; Ангст by its own, named by a synonym of that number
            c.run("INSERT INTO item_facet VALUES ('w1', 'tag', '574', 0, 'Домогательство')")
            c.run("INSERT INTO item_facet VALUES ('w1', 'tag', '477', 1, 'Харассмент')")
            c.run("INSERT INTO item_facet VALUES ('w2', 'tag', '574', 0, 'Домогательство')")
            c.run("INSERT INTO item_facet VALUES ('w2', 'tag', '1665', 1, 'Ангст')")
            c.run("INSERT INTO facet_value VALUES ('tag', '574', 'Домогательство'), ('tag', '1665', 'Titfuck')")
            c.run("INSERT INTO facet_value_description VALUES ('tag', '574', 'Синоним')")
            c.run("INSERT INTO facet_value_synonym VALUES ('tag', '574', '477')")
            V5__tags_mended()
                .migrate(
                    object : Context {
                        override fun getConfiguration(): Configuration = error("not read")

                        override fun getConnection() = c
                    }
                )
            assertEquals(
                listOf(listOf("w1", "477"), listOf("w2", "1665"), listOf("w2", "477")),
                c.rows("SELECT item_id, value_key FROM item_facet ORDER BY item_id, value_key"),
            )
            assertEquals(
                listOf(listOf("1665", "Ангст"), listOf("477", "Харассмент")),
                c.rows("SELECT value_key, name FROM facet_value ORDER BY value_key"),
            )
            assertEquals(emptyList(), c.rows("SELECT * FROM facet_value_description"))
            assertEquals(emptyList(), c.rows("SELECT * FROM facet_value_synonym"))
        }
    }
}
