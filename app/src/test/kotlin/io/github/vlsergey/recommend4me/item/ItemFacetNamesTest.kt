package io.github.vlsergey.recommend4me.item

import db.source.V22__item_facet_names
import io.github.vlsergey.recommend4me.TwoSitesTestBase
import io.github.vlsergey.recommend4me.api.model.DatabaseQuery
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.database.DatabaseQueries
import io.github.vlsergey.recommend4me.source.FacetValue
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.api.configuration.Configuration
import org.flywaydb.core.api.migration.Context
import org.springframework.beans.factory.annotation.Autowired

/**
 * A work keeps every value's key and its name as the site's page wrote it (user, 2026-11-18: "ideally a work keeps both
 * — the tags and the names"): a name written anew replaces it, a value given with no name keeps the one it had.
 */
class ItemFacetNamesTest : TwoSitesTestBase() {

    @Autowired lateinit var queries: DatabaseQueries

    private fun names(item: String) =
        queries
            .queryDatabase(
                DatabaseQuery(
                    "alpha/source",
                    "SELECT value_key, name FROM item_facet WHERE item_id = '$item' AND facet = 'tag' " +
                        "ORDER BY position",
                )
            )
            .body!!
            .rows
            .map { it[0] to it[1] }

    @Test
    fun `a work's values keep the names the page wrote`() {
        alpha("fn1|Lorem|hay|Lorem ipsum dolor||")
        val alpha = stores.source("alpha")!!
        alpha.items.setFacet("fn1", Books.TAG.key, listOf(FacetValue("t1", "One"), FacetValue("t2", "Two")))
        assertEquals(listOf("t1" to "One", "t2" to "Two"), names("fn1"))
        // The site writes a tag's name anew; another page gives the keys alone
        alpha.items.setFacet("fn1", Books.TAG.key, listOf(FacetValue("t1", "Uno"), FacetValue("t2", "Two")))
        alpha.items.setFacet("fn1", Books.TAG.key, listOf(FacetValue("t1", null), FacetValue("t2", null)))
        assertEquals(listOf("t1" to "Uno", "t2" to "Two"), names("fn1"))
    }

    /** The works written before the names were kept are given the names the source has of their keys now. */
    @Test
    fun `the values written before are given the names of their keys`() {
        DriverManager.getConnection("jdbc:h2:mem:item-facet-names;DB_CLOSE_DELAY=-1").use { c ->
            fun run(sql: String) = c.createStatement().use { it.executeUpdate(sql) }
            run(
                "CREATE TABLE item_facet (item_id VARCHAR, facet VARCHAR, value_key VARCHAR, position INT, " +
                    "name VARCHAR, PRIMARY KEY (item_id, facet, value_key))"
            )
            run("CREATE TABLE facet_value (facet VARCHAR, value_key VARCHAR, name VARCHAR)")
            run("INSERT INTO facet_value VALUES ('tag', 't1', 'One'), ('author', 't1', 'Ann')")
            run("INSERT INTO item_facet VALUES ('w1', 'tag', 't1', 0, NULL), ('w1', 'author', 't1', 0, NULL)")
            run("INSERT INTO item_facet VALUES ('w2', 'tag', 't1', 0, 'Uno'), ('w2', 'tag', 'none', 1, NULL)")
            V22__item_facet_names()
                .migrate(
                    object : Context {
                        override fun getConfiguration(): Configuration = error("not read")

                        override fun getConnection() = c
                    }
                )
            val rows =
                c.createStatement().use { s ->
                    s.executeQuery("SELECT item_id, facet, value_key, name FROM item_facet ORDER BY 1, 2, 3").use { r ->
                        buildList { while (r.next()) add((1..4).map { r.getString(it) }) }
                    }
                }
            assertEquals(
                listOf(
                    listOf("w1", "author", "t1", "Ann"),
                    listOf("w1", "tag", "t1", "One"),
                    listOf("w2", "tag", "none", null),
                    listOf("w2", "tag", "t1", "Uno"),
                ),
                rows,
            )
        }
    }
}
