package io.github.vlsergey.recommend4me.database

import io.github.vlsergey.recommend4me.api.DatabaseApi
import io.github.vlsergey.recommend4me.api.model.DatabaseQuery
import io.github.vlsergey.recommend4me.api.model.DatabaseRows
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * A QUERY OF ANY DATABASE FILE WHILE THE APPLICATION RUNS (user, 2026-11-18: "a way to run queries against the
 * databases without stopping the application"): for whoever looks into the data, the owner or an agent, through the
 * pools the application holds — an H2 file is open in one process alone.
 *
 * Nothing is written by it: the statement runs in a transaction rolled back after, and H2 refuses one that is no query
 * (`executeQuery`) before it runs. A query reads what was committed and holds no row another transaction writes.
 */
@RestController
class DatabaseQueries(private val databases: Databases, private val app: AppDatabase) : DatabaseApi {

    private fun files(): Map<String, Database> = (databases.all() + app.database).associateBy { it.name }

    override fun listDatabases(): ResponseEntity<List<String>> = ResponseEntity.ok(files().keys.sorted())

    override fun queryDatabase(databaseQuery: DatabaseQuery): ResponseEntity<DatabaseRows> {
        val file = files()[databaseQuery.database] ?: return ResponseEntity.notFound().build()
        val limit = (databaseQuery.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val started = System.currentTimeMillis()
        val rows =
            file.dsl.connectionResult { c ->
                try {
                    read(c, databaseQuery.sql, limit)
                } catch (e: SQLException) {
                    DatabaseRows(emptyList(), emptyList(), false, error = e.message)
                }
            }
        return ResponseEntity.ok(rows.copy(millis = System.currentTimeMillis() - started))
    }

    /** The rows of [sql], at most [limit], in a transaction rolled back whatever it did. */
    private fun read(c: Connection, sql: String, limit: Int): DatabaseRows {
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            c.prepareStatement(sql).use { statement ->
                statement.queryTimeout = TIMEOUT_SECONDS
                // One more than asked: whether there are more
                statement.maxRows = limit + 1
                statement.executeQuery().use { result ->
                    val columns = (1..result.metaData.columnCount).map { result.metaData.getColumnLabel(it) }
                    val rows = mutableListOf<List<String?>>()
                    while (result.next() && rows.size <= limit) rows += columns.indices.map { cell(result, it + 1) }
                    val truncated = rows.size > limit
                    return DatabaseRows(columns, if (truncated) rows.subList(0, limit) else rows, truncated)
                }
            }
        } finally {
            c.rollback()
            c.autoCommit = autoCommit
        }
    }

    /** A cell as text: bytes by their count, an array by its items. */
    private fun cell(result: ResultSet, column: Int): String? =
        when (val value = result.getObject(column)) {
            null -> null
            is ByteArray -> "<${value.size} bytes>"
            is java.sql.Array -> (value.array as Array<*>).joinToString(prefix = "[", postfix = "]")
            else -> value.toString()
        }

    private companion object {
        /** The rows answered when the query names no limit. */
        const val DEFAULT_LIMIT = 1000

        /** The rows answered at most: the answer is held whole in the memory. */
        const val MAX_LIMIT = 100_000

        /** The time a query is given. */
        const val TIMEOUT_SECONDS = 60
    }
}
