package io.github.vlsergey.recommend4me.database

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import org.duckdb.DuckDBConnection
import org.jooq.ConnectionProvider
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DuckDatabase::class.java)

/**
 * A DUCKDB FILE OF THE APPLICATION (owner, 2026-11-22: "let us move to DuckDB"): a store of columns, for a table
 * written whole and read by the columns of a filter and a sort, no index kept — of what is made again of the rest,
 * never the only copy of the user's word. Made by its one [script] (a resource) as it is new, and made anew, empty, as
 * its tables are other than the script makes: what it holds is made again by the work that wrote it. Opened once and
 * held open till the end; every use a connection of its own made of the one it opened ([DuckDBConnection.duplicate]),
 * and none on a computation that reads nothing of a database ([DatabaseRefused]). Of the memory, at most [memory].
 */
class DuckDatabase(private val name: String, private val file: Path, private val script: String, memory: String) :
    AutoCloseable {

    private val config = Properties().apply { setProperty("memory_limit", memory) }

    private val base: DuckDBConnection = opened()

    /** The DSL of the file: of the dialect of DuckDB, no schema named — the file has its own. */
    val dsl: DSLContext =
        DSL.using(
            object : ConnectionProvider {
                override fun acquire(): Connection {
                    DatabaseRefused.check()
                    return base.duplicate()
                }

                override fun release(connection: Connection) = connection.close()
            },
            SQLDialect.DUCKDB,
            Settings().withRenderSchema(false),
        )

    /**
     * The [block] in one transaction of a connection of its own — with the DSL of it, and the connection for an
     * appender of many rows ([DuckDBConnection.createAppender]) —, committed as it ends, taken back as it fails.
     */
    fun <T> transaction(block: (DuckDBConnection, DSLContext) -> T): T {
        DatabaseRefused.check()
        val connection = base.duplicate() as DuckDBConnection
        connection.use { c ->
            c.autoCommit = false
            var committed = false
            try {
                val out = block(c, DSL.using(c, SQLDialect.DUCKDB, Settings().withRenderSchema(false)))
                c.commit()
                committed = true
                return out
            } finally {
                if (!committed) c.rollback()
            }
        }
    }

    override fun close() = base.close()

    /** The file opened — made by the script when new or of other tables than it makes. */
    private fun opened(): DuckDBConnection {
        Files.createDirectories(file.toAbsolutePath().parent)
        val sql = javaClass.classLoader.getResource(script)?.readText() ?: error("No script $script")
        var empty = !Files.exists(file)
        var connection = connect()
        if (!empty && tablesOf(connection) != tablesOf(sql)) {
            log.info("{}: the tables of {} are other than {} makes: the file made anew, empty", name, file, script)
            connection.close()
            Files.delete(file)
            Files.deleteIfExists(Path.of("$file.wal"))
            connection = connect()
            empty = true
        }
        if (empty) run(connection, sql)
        return connection
    }

    private fun connect(): DuckDBConnection =
        DriverManager.getConnection("jdbc:duckdb:${file.toAbsolutePath()}", config) as DuckDBConnection

    /** The tables the [sql] makes, as a database of nothing else holds them. */
    private fun tablesOf(sql: String): List<String> =
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).use { memory ->
            run(memory, sql)
            tablesOf(memory)
        }

    /** The tables of the [connection]'s database: of every column its table, name, type and whether it may be null. */
    private fun tablesOf(connection: Connection): List<String> =
        connection.createStatement().use { s ->
            s.executeQuery(
                    "SELECT table_name, column_name, data_type, is_nullable FROM information_schema.columns " +
                        "WHERE table_schema = 'main' ORDER BY table_name, ordinal_position"
                )
                .use { rs ->
                    buildList {
                        while (rs.next()) add((1..COLUMNS).joinToString(" ") { rs.getString(it) })
                    }
                }
        }

    /** The statements of the [sql] run one after another: each ends its line with a semicolon. */
    private fun run(connection: Connection, sql: String) =
        connection.createStatement().use { s ->
            sql.lines()
                .filter { !it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(Regex(";\\s*(\\n|$)"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach(s::execute)
        }

    private companion object {
        /** The columns of the information of a column compared: its table, name, type, nullability. */
        const val COLUMNS = 4
    }
}
