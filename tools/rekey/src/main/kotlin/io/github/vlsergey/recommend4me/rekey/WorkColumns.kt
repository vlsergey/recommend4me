package io.github.vlsergey.recommend4me.rekey

import java.sql.Connection

/**
 * A column of an H2 database naming works by the sites' ids: [table].[column], the source of each row in
 * [sourceColumn], or — a database of one source — the database's own.
 */
data class WorkColumn(val table: String, val column: String, val sourceColumn: String? = null)

/**
 * THE COLUMNS OF WORKS OF AN H2 DATABASE NAMED BY THE APPLICATION'S NUMBERS: every value the site's id of a work, made
 * its number as text — the migration of the version that reads them makes the column a number (`BIGINT`). A row of a
 * work no longer kept goes: it named nothing the application can show. The numbers are written through a table of the
 * pairs, two statements a column: first marked ("#12"), then bare — a number may be some other work's site's id, and a
 * key met in between would refuse the row.
 */
class WorkColumns(private val c: Connection, private val numbers: Numbers, private val own: String?) {

    /** The [columns] rekeyed; of [dryRun], counted alone. */
    fun rekey(name: String, columns: List<WorkColumn>, dryRun: Boolean) {
        val existing = columns.filter { exists(it) }.associateWith(::valuesOf)
        val known =
            existing.values.flatten().toSet().mapNotNull { (source, site) ->
                numbers.numberOf(source, site)?.let { Triple(source, site, it) }
            }
        val knownKeys = known.mapTo(HashSet()) { it.first to it.second }
        existing.forEach { (col, values) ->
            val rows = count("SELECT COUNT(*) FROM ${col.table} WHERE ${col.column} IS NOT NULL")
            log("$name ${col.table}.${col.column}: $rows rows, ${values.count { it !in knownKeys }} works gone")
        }
        if (dryRun || existing.isEmpty()) return
        c.autoCommit = false
        c.createStatement().use { s ->
            s.execute(
                "CREATE LOCAL TEMPORARY TABLE rekey_pair(src VARCHAR, old VARCHAR, new BIGINT, PRIMARY KEY(src, old))"
            )
        }
        c.prepareStatement("INSERT INTO rekey_pair VALUES (?, ?, ?)").use { p ->
            known.forEachIndexed { i, (source, site, number) ->
                p.bound(source, site, number).addBatch()
                if (i % BATCH == BATCH - 1) p.executeBatch()
            }
            p.executeBatch()
        }
        existing.keys.forEach(::rekey)
        c.createStatement().use { it.execute("DROP TABLE rekey_pair") }
        c.commit()
        c.autoCommit = true
    }

    /** The sources' and the sites' ids of the works the column [col] names. */
    private fun valuesOf(col: WorkColumn): List<Pair<String, String>> {
        val source = col.sourceColumn ?: "'$own'"
        val out = ArrayList<Pair<String, String>>()
        c.createStatement().use { s ->
            s.executeQuery("SELECT DISTINCT $source, ${col.column} FROM ${col.table} WHERE ${col.column} IS NOT NULL")
                .use { r -> while (r.next()) out += r.getString(1) to r.getString(2) }
        }
        return out
    }

    private fun rekey(col: WorkColumn) {
        val source = col.sourceColumn?.let { "t.$it" } ?: "'$own'"
        val pair = "FROM rekey_pair p WHERE p.src = $source AND p.old = t.${col.column}"
        c.createStatement().use { s ->
            val gone =
                s.executeUpdate(
                    "DELETE FROM ${col.table} t WHERE t.${col.column} IS NOT NULL AND NOT EXISTS (SELECT 1 $pair)"
                )
            val marked =
                s.executeUpdate(
                    "UPDATE ${col.table} t SET ${col.column} = (SELECT '#' || CAST(p.new AS VARCHAR) $pair) " +
                        "WHERE t.${col.column} IS NOT NULL"
                )
            s.executeUpdate(
                "UPDATE ${col.table} SET ${col.column} = SUBSTRING(${col.column}, 2) WHERE ${col.column} LIKE '#%'"
            )
            log("  ${col.table}.${col.column}: $marked rows numbered, $gone gone")
        }
    }

    private fun exists(col: WorkColumn): Boolean =
        count(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'PUBLIC' " +
                "AND TABLE_NAME = '${col.table.uppercase()}' AND COLUMN_NAME = '${col.column.uppercase()}'"
        ) > 0

    private fun count(sql: String): Long =
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> if (r.next()) r.getLong(1) else 0 } }

    private companion object {
        /** The pairs inserted a statement: memory, not meaning. */
        const val BATCH = 5000
    }
}
