package io.github.vlsergey.recommend4me.rekey

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.exists
import org.duckdb.DuckDBConnection

/**
 * THE INDEX OF THE LIST OF A CONTENT TYPE (`list.duckdb`, DuckDB: `ListIndex`) KEYED BY THE WORKS' NUMBERS: a new file
 * made by the index's own script (`worklist/store.sql`), every row of every table of the old one copied into it, a
 * work's key ("source/site": `item_key`, and the work of an item, `item.work`) of its number; put in the place of the
 * old file, the old one kept beside ("list-by-site.duckdb"). A row of a work no longer kept is not carried over.
 */
class WorkListByNumber(private val folder: Path, private val numbers: Numbers) {

    fun rekey(name: String, dryRun: Boolean) {
        val old = folder.resolve(FILE)
        if (!old.exists()) return
        checkpointed(old)
        if (dryRun) {
            duck(old).use { c -> log("$name list: ${count(c, "SELECT COUNT(*) FROM item")} items") }
            return
        }
        val new = folder.resolve("list-by-number.duckdb")
        Files.deleteIfExists(new)
        duck(new).use { c ->
            c.createStatement().use { s -> script().forEach(s::execute) }
            keys(c)
            c.createStatement().use { it.execute("ATTACH '${path(old)}' AS before (READ_ONLY)") }
            tables(c).forEach { (table, columns) -> copy(name, c, table, columns) }
            c.createStatement().use {
                it.execute("DETACH before")
                it.execute("DROP TABLE rekey_key")
                it.execute("CHECKPOINT")
            }
        }
        Files.move(old, folder.resolve("list-by-site.duckdb"))
        Files.move(new, old)
    }

    /** The statements of the index's script: those its comments and blank lines leave. */
    private fun script(): List<String> {
        val text = javaClass.getResource("/worklist/store.sql")!!.readText()
        return text
            .lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** Every work's key with the key of its number, in a table of the new file. */
    private fun keys(c: Connection) {
        c.createStatement().use { it.execute("CREATE TABLE rekey_key(old VARCHAR, new VARCHAR)") }
        (c as DuckDBConnection).createAppender(DuckDBConnection.DEFAULT_SCHEMA, "rekey_key").use { a ->
            numbers.sources.forEach { source ->
                numbers.ofSource(source).forEach { (site, number) ->
                    a.beginRow()
                    a.append("$source/$site")
                    a.append("$source/$number")
                    a.endRow()
                }
            }
        }
    }

    /** The tables of the new file and their columns, in their order. */
    private fun tables(c: Connection): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        c.createStatement().use { s ->
            s.executeQuery(
                    "SELECT table_name, column_name FROM information_schema.columns " +
                        "WHERE table_catalog = current_database() AND table_name <> 'rekey_key' " +
                        "ORDER BY table_name, ordinal_position"
                )
                .use { r -> while (r.next()) out.getOrPut(r.getString(1)) { ArrayList() } += r.getString(2) }
        }
        return out
    }

    /** The rows of the [table] of the old file in the new one, every key of a work of its number. */
    private fun copy(name: String, c: Connection, table: String, columns: List<String>) {
        val keyed = columns.filter { it == "item_key" || (table == "item" && it == "work") }
        val joins = keyed.joinToString(" ") { "JOIN rekey_key k_$it ON k_$it.old = b.$it" }
        val values = columns.joinToString(", ") { if (it in keyed) "k_$it.new" else "b.$it" }
        val sql = "INSERT INTO main.$table (${columns.joinToString(", ")}) SELECT $values FROM before.$table b $joins"
        val copied = c.createStatement().use { it.executeUpdate(sql) }
        val before = count(c, "SELECT COUNT(*) FROM before.$table")
        log("$name list $table: $copied rows of $before")
    }

    private fun count(c: Connection, sql: String): Long =
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> if (r.next()) r.getLong(1) else 0 } }

    /** The file [file] with nothing left in its log: read alone by another connection after. */
    private fun checkpointed(file: Path) {
        if (!file.resolveSibling("${file.fileName}.wal").exists()) return
        duck(file).use { c -> c.createStatement().use { it.execute("CHECKPOINT") } }
    }

    private companion object {
        const val FILE = "list.duckdb"

        fun path(file: Path) = file.toAbsolutePath().toString().replace('\\', '/')

        fun duck(file: Path): Connection = DriverManager.getConnection("jdbc:duckdb:${path(file)}")
    }
}
