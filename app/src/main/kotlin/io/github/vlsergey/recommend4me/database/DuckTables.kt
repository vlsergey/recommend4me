package io.github.vlsergey.recommend4me.database

import java.sql.Connection
import java.sql.DriverManager
import org.duckdb.DuckDBConnection

/**
 * THE TABLES OF A DUCKDB FILE AS ITS SCRIPT MAKES THEM ([DuckDatabase]): what they are compared by — their columns,
 * their keys, their indexes —, the statements of the script, and what a database of nothing but them holds; a file
 * whose tables lack only columns the script adds is widened in place ([DuckWidening]).
 */
internal object DuckTables {

    /** The tables of the [connection]'s database, as they are compared ([TABLES]). */
    fun of(connection: Connection): List<String> =
        connection.createStatement().use { s ->
            TABLES.flatMap { query ->
                s.executeQuery(query).use { rs ->
                    val columns = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..columns).joinToString(" ") { rs.getString(it) }) }
                }
            }
        }

    /** The tables the [sql] makes, as a database of nothing else holds them. */
    fun ofScript(sql: String): List<String> = inMemory(sql, ::of)

    /** The statements of the [sql] run one after another: each ends its line with a semicolon. */
    fun run(connection: Connection, sql: String) =
        connection.createStatement().use { s -> statements(sql).forEach(s::execute) }

    /** A column of a table: its name, type and whether it may be null. */
    data class Column(val name: String, val type: String, val nullable: Boolean)

    /** Of every table of the [connection]'s database, its columns in their order. */
    fun columnsOf(connection: Connection): Map<String, List<Column>> =
        connection.createStatement().use { s ->
            s.executeQuery(TABLES.first()).use { rs ->
                val out = LinkedHashMap<String, MutableList<Column>>()
                while (rs.next()) out.getOrPut(rs.getString("table_name")) { ArrayList() } +=
                    Column(rs.getString("column_name"), rs.getString("data_type"), rs.getString("is_nullable") == "YES")
                out
            }
        }

    fun statements(sql: String): List<String> =
        sql.lines()
            .filter { !it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(Regex(";\\s*(\\n|$)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** What [read] reads of a database of nothing but what the [sql] makes. */
    fun <T> inMemory(sql: String, read: (Connection) -> T): T =
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { memory ->
            run(memory, sql)
            read(memory)
        }

    /** What the tables are compared by: their columns, their keys, their indexes. */
    private val TABLES =
        listOf(
            "SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns " +
                "WHERE table_schema = 'main' ORDER BY table_name, ordinal_position",
            "SELECT table_name, constraint_type, constraint_column_names::VARCHAR FROM duckdb_constraints() " +
                "WHERE schema_name = 'main' AND constraint_type <> 'NOT NULL' ORDER BY ALL",
            "SELECT table_name, index_name, is_unique, expressions FROM duckdb_indexes() " +
                "WHERE schema_name = 'main' ORDER BY ALL",
        )
}
