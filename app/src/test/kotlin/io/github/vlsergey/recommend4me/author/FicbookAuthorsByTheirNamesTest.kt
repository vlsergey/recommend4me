package io.github.vlsergey.recommend4me.author

import db.dictionaries.V32__ficbook_authors_by_their_names
import io.github.vlsergey.recommend4me.BaseTest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.api.configuration.Configuration
import org.flywaydb.core.api.migration.Context

/**
 * An author of ficbook a place on another site gave by the account's name is the author the dump gave by that name: so
 * keyed, merged with the dump's into the place's normalized author unless the user told them apart, a name misread read
 * as written.
 */
class FicbookAuthorsByTheirNamesTest : BaseTest() {

    /** «Солоха и Эйзенхайм» as a page of fanfics.me gave it: UTF-8 read as Windows-1251. */
    private val misread = String("Солоха и Эйзенхайм".toByteArray(Charsets.UTF_8), charset("windows-1251"))

    private fun Connection.run(sql: String) = createStatement().use { it.executeUpdate(sql) }

    private fun Connection.rows(sql: String): List<List<String?>> =
        createStatement().use { s ->
            s.executeQuery(sql).use { r ->
                buildList { while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) }) }
            }
        }

    /** The tables of the authors in the dictionaries, as the migrations before left them. */
    private fun Connection.tables() {
        run("CREATE TABLE normalized_author (id BIGINT PRIMARY KEY)")
        run(
            "CREATE TABLE site_author (source VARCHAR, author VARCHAR, normalized BIGINT, bound_at TIMESTAMP, " +
                "merged_by VARCHAR, merged_by_source VARCHAR, merged_by_author VARCHAR, PRIMARY KEY (source, author))"
        )
        run(
            "CREATE TABLE site_author_place (source VARCHAR, author VARCHAR, url VARCHAR, place_source VARCHAR, " +
                "place_author VARCHAR, said_at TIMESTAMP, PRIMARY KEY (source, author, url))"
        )
        run(
            "CREATE TABLE normalized_author_entry (author BIGINT, catalogue VARCHAR, entry VARCHAR, " +
                "bound_at TIMESTAMP, PRIMARY KEY (author, catalogue, entry))"
        )
        run(
            "CREATE TABLE normalized_author_distinct (first_author BIGINT, other_author BIGINT, said_at TIMESTAMP, " +
                "partly BOOLEAN, PRIMARY KEY (first_author, other_author))"
        )
    }

    /**
     * The authors before: Мышилла of a place of fanfics.me's 100 and of the dump, Солоха of a place misread and of the
     * dump — told apart — Свежий ветер of a place alone, the site's own keys.
     */
    private fun Connection.authors() {
        run("INSERT INTO normalized_author VALUES (1), (2), (3), (4), (5), (6), (9)")
        val at = "TIMESTAMP '2026-11-16 00:00:00'"
        run(
            "INSERT INTO site_author VALUES ('fanfics.me', '100', 1, $at, NULL, NULL, NULL), " +
                "('ficbook', 'Мышилла', 1, $at, 'PLACE', 'fanfics.me', '100'), " +
                "('ficbook', 'name:Мышилла', 2, $at, NULL, NULL, NULL), " +
                "('fanfics.me', '200', 3, $at, NULL, NULL, NULL), " +
                "('ficbook', '$misread', 3, $at, 'PLACE', 'fanfics.me', '200'), " +
                "('ficbook', 'name:Солоха и Эйзенхайм', 4, $at, NULL, NULL, NULL), " +
                "('fanfics.me', '300', 5, $at, NULL, NULL, NULL), " +
                "('ficbook', 'Свежий ветер', 5, $at, 'PLACE', 'fanfics.me', '300'), " +
                "('ficbook', '12345', 6, $at, NULL, NULL, NULL), " +
                "('ficbook', '0189dad5-3b41-7037-905e-a4d06d778b07', 6, $at, NULL, NULL, NULL), " +
                "('ficbook', '777', 9, $at, NULL, NULL, NULL)"
        )
        run(
            "INSERT INTO site_author_place VALUES " +
                "('fanfics.me', '100', 'https://ficbook.net/authors/Мышилла', 'ficbook', 'Мышилла', $at), " +
                "('fanfics.me', '200', 'https://ficbook.net/authors/$misread', 'ficbook', '$misread', $at), " +
                "('fanfics.me', '300', 'https://ficbook.net/authors/Свежий ветер', 'ficbook', 'Свежий ветер', $at)"
        )
        run("INSERT INTO normalized_author_entry VALUES (2, 'wikidata', 'Q1', $at)")
        // The dump's Мышилла told apart from another; the dump's Солоха told apart from the place's
        run("INSERT INTO normalized_author_distinct VALUES (2, 9, $at, FALSE), (3, 4, $at, FALSE)")
    }

    @Test
    fun `an author of ficbook given by the account's name is the dump's author of that name`() {
        DriverManager.getConnection("jdbc:h2:mem:ficbook-authors-by-names;DB_CLOSE_DELAY=-1").use { c ->
            c.tables()
            c.authors()
            V32__ficbook_authors_by_their_names()
                .migrate(
                    object : Context {
                        override fun getConfiguration(): Configuration = error("not read")

                        override fun getConnection() = c
                    }
                )
            assertEquals(
                listOf(
                    listOf("0189dad5-3b41-7037-905e-a4d06d778b07", "6", null, null),
                    listOf("12345", "6", null, null),
                    listOf("777", "9", null, null),
                    listOf("name:Мышилла", "1", "PLACE", "100"),
                    listOf("name:Свежий ветер", "5", "PLACE", "300"),
                    listOf("name:Солоха и Эйзенхайм", "4", null, null),
                ),
                c.rows(
                    "SELECT author, normalized, merged_by, merged_by_author FROM site_author " +
                        "WHERE source = 'ficbook' ORDER BY author"
                ),
            )
            assertEquals(
                listOf(
                    listOf("100", "name:Мышилла"),
                    listOf("200", "name:Солоха и Эйзенхайм"),
                    listOf("300", "name:Свежий ветер"),
                ),
                c.rows("SELECT author, place_author FROM site_author_place ORDER BY author"),
            )
            assertEquals(
                listOf("1", "3", "4", "5", "6", "9"),
                c.rows("SELECT id FROM normalized_author ORDER BY id").map { it[0] },
            )
            assertEquals(
                listOf(listOf("1", "Q1")),
                c.rows("SELECT author, entry FROM normalized_author_entry"),
            )
            assertEquals(
                listOf(listOf("1", "9"), listOf("3", "4")),
                c.rows("SELECT first_author, other_author FROM normalized_author_distinct ORDER BY 1"),
            )
        }
    }
}
