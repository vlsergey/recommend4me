package io.github.vlsergey.recommend4me.database

import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jooq.Query
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test

/**
 * A key asked of H2 by parameters, as jOOQ binds them: of its first column and a list of the next, H2 walks every row
 * of the first one's value, its key used for that column alone; of the key whole — the pairs of [WholeKeys], the
 * triples of a key of three —, it reads the rows asked alone (2026-11-27: the bindings of 500 authors of ficbook 1.8 s
 * against 14 ms).
 */
class KeysAskedWholeTest {

    private val asked = listOf("a7", "a700", "a1500")

    @Test
    fun `the pairs of a key asked whole are read by the key, the first column and a list walk every row of it`() {
        DriverManager.getConnection("jdbc:h2:mem:keys-asked-whole", "sa", "").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "CREATE TABLE site_author(source VARCHAR(100) NOT NULL, author VARCHAR(500) NOT NULL, " +
                        "normalized BIGINT NOT NULL, PRIMARY KEY(source, author))"
                )
                s.execute("INSERT INTO site_author SELECT 'ficbook', 'a' || X, X FROM SYSTEM_RANGE(1, 2000)")
            }
            val dsl = DSL.using(c, SQLDialect.H2)
            val t = DSL.table("site_author")
            val source = DSL.field("source", String::class.java)
            val author = DSL.field("author", String::class.java)
            val byPairs = dsl.select(author).from(t).where(WholeKeys.of(source, author, "ficbook", asked))
            val byList = dsl.select(author).from(t).where(source.eq("ficbook"), author.`in`(asked))
            assertEquals(asked.toSet(), byPairs.fetch(author).toSet())
            assertTrue(scanned(c, byPairs) <= asked.size + 1, "the pairs: the rows asked alone")
            assertEquals(2001, scanned(c, byList), "the source and a list: every row of the source")
        }
    }

    @Test
    fun `the triples of a key of three asked whole are read by the key`() {
        DriverManager.getConnection("jdbc:h2:mem:keys-of-three-asked-whole", "sa", "").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "CREATE TABLE strength(kind VARCHAR(10) NOT NULL, source VARCHAR(100) NOT NULL, " +
                        "item_id VARCHAR(20) NOT NULL, PRIMARY KEY(kind, source, item_id))"
                )
                s.execute("INSERT INTO strength SELECT 'GRADE', 'ficbook', 'a' || X FROM SYSTEM_RANGE(1, 2000)")
            }
            val dsl = DSL.using(c, SQLDialect.H2)
            val kind = DSL.field("kind", String::class.java)
            val source = DSL.field("source", String::class.java)
            val item = DSL.field("item_id", String::class.java)
            val byTriples =
                dsl.select(item)
                    .from(DSL.table("strength"))
                    .where(DSL.row(kind, source, item).`in`(asked.map { DSL.row("GRADE", "ficbook", it) }))
            assertEquals(asked.toSet(), byTriples.fetch(item).toSet())
            assertTrue(scanned(c, byTriples) <= asked.size + 1, "the triples: the rows asked alone")
        }
    }

    /** The rows H2 walked of the table for the [query] run with its parameters bound. */
    private fun scanned(c: Connection, query: Query): Int =
        c.prepareStatement("EXPLAIN ANALYZE " + query.sql).use { p ->
            query.bindValues.forEachIndexed { i, v -> p.setObject(i + 1, v) }
            p.executeQuery().use { r ->
                r.next()
                Regex("scanCount: (\\d+)").find(r.getString(1))!!.groupValues[1].toInt()
            }
        }
}
