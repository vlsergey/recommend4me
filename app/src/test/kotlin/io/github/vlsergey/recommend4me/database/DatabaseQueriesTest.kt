package io.github.vlsergey.recommend4me.database

import io.github.vlsergey.recommend4me.BaseTest
import io.github.vlsergey.recommend4me.api.model.DatabaseQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus

class DatabaseQueriesTest : BaseTest() {

    @Autowired lateinit var queries: DatabaseQueries

    @Autowired lateinit var databases: Databases

    private fun query(sql: String, limit: Int? = null, database: String = "dictionaries") =
        queries.queryDatabase(DatabaseQuery(database, sql, limit))

    @Test
    fun `every file is listed, the application's own too`() {
        val names = queries.listDatabases().body!!
        assertTrue("dictionaries" in names && "app" in names && "preferences" in names, names.toString())
        assertTrue(names.any { it.endsWith("/source") } && names.any { it.endsWith("/model") }, names.toString())
    }

    @Test
    fun `a query answers its columns and its rows as text, at most the limit`() {
        val one = query("SELECT 1 AS one, NULL AS nothing").body!!
        assertNull(one.error)
        assertEquals(listOf("ONE", "NOTHING"), one.columns)
        assertEquals(listOf(listOf("1", null)), one.rows)

        val cut = query("SELECT x FROM SYSTEM_RANGE(1, 5)", limit = 3).body!!
        assertEquals(listOf("1", "2", "3"), cut.rows.map { it.single() })
        assertTrue(cut.truncated)
        assertTrue(!query("SELECT x FROM SYSTEM_RANGE(1, 3)", limit = 3).body!!.truncated)
    }

    @Test
    fun `nothing is written by it, a statement that is no query refused`() {
        assertNotNull(query("CREATE TABLE query_written(x INT)").body!!.error)
        assertNotNull(query("SELECT * FROM query_written").body!!.error)
    }

    /** H2 keeps a statement's time on its session: the pool's next one asking gets it with none. */
    @Test
    fun `a query leaves its session with no time limit`() {
        query("SELECT 1")
        val left = databases.dictionaries.dsl.connectionResult { c -> c.createStatement().use { it.queryTimeout } }
        assertEquals(0, left)
    }

    @Test
    fun `a file of no such name is not found`() {
        assertEquals(HttpStatus.NOT_FOUND, query("SELECT 1", database = "nowhere").statusCode)
    }
}
