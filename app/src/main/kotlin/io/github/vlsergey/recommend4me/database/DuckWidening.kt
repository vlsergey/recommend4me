package io.github.vlsergey.recommend4me.database

import io.github.vlsergey.recommend4me.database.DuckTables.Column
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import org.duckdb.DuckDBConnection
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DuckWidening::class.java)

/**
 * A DUCKDB FILE WHOSE TABLES LACK ONLY COLUMNS ITS SCRIPT ADDS, of no NOT NULL, WIDENED IN PLACE ([DuckDatabase]): its
 * rows kept and the new columns null — a new kind of data of the rows is never a reason to make the file anew
 * (2026-11-27: the index of the list keeps the predictions of a million works and the marks of every stage of the tasks
 * over the works, made again only in hours).
 */
internal object DuckWidening {

    /**
     * The tables of the [connection]'s database widened to those of the [sql] where every column it has the script has
     * alike, and every column the script adds may be null — every key and index as the script makes them —: each made
     * anew by the script's own statements of it, its rows copied in, the new columns null. The tables widened, by their
     * names; none when the tables differ otherwise — the file is then made anew.
     */
    fun widened(connection: DuckDBConnection, sql: String): List<String>? {
        val own = DuckTables.columnsOf(connection)
        val narrow = narrowOf(own, DuckTables.inMemory(sql) { DuckTables.columnsOf(it) })
        return narrow?.takeIf { widenedAll(connection, it, own, sql) }?.keys?.toList()
    }

    /**
     * Of the tables [own] of the file, those the script makes [wanted] of more columns, every one of theirs alike and
     * every one added of no NOT NULL: the script's columns of each; none when they differ otherwise, or not at all.
     */
    private fun narrowOf(
        own: Map<String, List<Column>>,
        wanted: Map<String, List<Column>>,
    ): Map<String, List<Column>>? {
        val narrow = wanted.filter { (table, columns) -> own[table] != columns }
        val widens =
            own.keys == wanted.keys &&
                narrow.all { (table, columns) ->
                    val have = own.getValue(table)
                    columns.containsAll(have) && (columns - have.toSet()).all { it.nullable }
                }
        return narrow.takeIf { widens && it.isNotEmpty() }
    }

    /**
     * The [narrow] tables widened in one transaction — a widening failed leaves the file as it was, to be made anew —;
     * whether they are as the script makes them now.
     */
    private fun widenedAll(
        connection: DuckDBConnection,
        narrow: Map<String, List<Column>>,
        own: Map<String, List<Column>>,
        sql: String,
    ): Boolean {
        connection.autoCommit = false
        try {
            connection.createStatement().use { s -> narrow.keys.forEach { widen(connection, s, it, own, sql) } }
            val widened = narrow.all { (table, columns) -> DuckTables.columnsOf(connection)[table] == columns }
            if (widened) connection.commit() else connection.rollback()
            return widened
        } catch (failed: SQLException) {
            log.warn("The tables {} not widened: {}", narrow.keys, failed.message)
            connection.rollback()
            return false
        } finally {
            connection.autoCommit = true
        }
    }

    /** The [table] made anew by the statements of the [sql] of it, its rows of the columns it had ([own]) copied in. */
    private fun widen(
        connection: Connection,
        s: Statement,
        table: String,
        own: Map<String, List<Column>>,
        sql: String,
    ) {
        val kept = own.getValue(table).joinToString(", ") { it.name }
        indexesOf(connection, table).forEach { s.execute("DROP INDEX $it") }
        s.execute("ALTER TABLE $table RENAME TO ${table}$NARROW")
        DuckTables.statements(sql).filter { tableOf(it) == table }.forEach(s::execute)
        s.execute("INSERT INTO $table ($kept) SELECT $kept FROM ${table}$NARROW")
        s.execute("DROP TABLE ${table}$NARROW")
    }

    /** The names of the indexes of the [table]. */
    private fun indexesOf(connection: Connection, table: String): List<String> =
        connection.prepareStatement(INDEXES).use { s ->
            s.setString(1, table)
            s.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    /** The table a statement makes — itself, or an index of it —; none of another statement. */
    private fun tableOf(statement: String): String? = MAKES.find(statement)?.groupValues?.get(1)

    private const val INDEXES = "SELECT index_name FROM duckdb_indexes() WHERE schema_name = 'main' AND table_name = ?"

    /** A statement making a table, or an index of one: the table's name. */
    private val MAKES =
        Regex("^CREATE\\s+(?:TABLE|(?:UNIQUE\\s+)?INDEX\\s+\\w+\\s+ON)\\s+(\\w+)", RegexOption.IGNORE_CASE)

    /** What a table being widened is named while its rows are copied. */
    private const val NARROW = "_narrow"
}
