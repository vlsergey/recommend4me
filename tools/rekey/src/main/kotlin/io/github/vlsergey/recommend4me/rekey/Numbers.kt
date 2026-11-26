package io.github.vlsergey.recommend4me.rekey

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * THE APPLICATION'S NUMBER OF EVERY WORK BY THE SITE'S ID OF IT, of every source: the table `item` of each source's
 * database holds both since V37-V39 (`internal_id`, `site_id`). Read once, whole: a million works of a source are a map
 * of a million entries, the tool's alone.
 */
class Numbers(private val bySource: Map<String, Map<String, Long>>) {
    val sources: Set<String>
        get() = bySource.keys

    /** The number of the work the site [source] names [siteId]; none of a work no longer kept. */
    fun numberOf(source: String, siteId: String): Long? = bySource[source]?.get(siteId)

    /** Of the source [source], every site's id with its number. */
    fun ofSource(source: String): Map<String, Long> = bySource[source].orEmpty()

    /**
     * The key of a work "source/site" — as the stores of the types name a work ([ItemKey]'s text) — of its number:
     * "source/number"; none of a work no longer kept, or of a source not known.
     */
    fun keyOf(key: String): String? {
        // A source's id has no slash; a site's id may have
        val at = key.indexOf('/')
        if (at <= 0) return null
        val source = key.substring(0, at)
        return numberOf(source, key.substring(at + 1))?.let { "$source/$it" }
    }

    companion object {
        /** Every source's numbers, of the databases of the sources in [data]. */
        fun read(data: Path): Numbers {
            val sources = data.resolve("sources")
            val out = LinkedHashMap<String, Map<String, Long>>()
            Files.list(sources).use { dirs ->
                dirs
                    .filter { it.resolve("source.mv.db").exists() }
                    .sorted()
                    .forEach { dir ->
                        connect(dir.resolve("source")).use { c -> out[dir.name] = numbersOf(c) }
                        log("${dir.name}: ${out.getValue(dir.name).size} works")
                    }
            }
            return Numbers(out)
        }

        private fun numbersOf(c: Connection): Map<String, Long> {
            val out = HashMap<String, Long>()
            c.createStatement().use { s ->
                s.fetchSize = STREAMED
                s.executeQuery("SELECT site_id, internal_id FROM item").use { r ->
                    while (r.next()) out[r.getString(1)] = r.getLong(2)
                }
            }
            return out
        }
    }
}

/** The rows read from the database at a time: memory, not meaning. */
const val STREAMED = 10_000

/** An H2 database of the application, the file [base] without its ".mv.db", opened alone, as the application does. */
fun connect(base: Path): Connection =
    DriverManager.getConnection(
        "jdbc:h2:file:${base.toAbsolutePath().toString().replace('\\', '/')};DB_CLOSE_ON_EXIT=FALSE;COMPRESS=TRUE",
        "sa",
        "",
    )

/** The statement given the [values] of its parameters, in their order. */
fun PreparedStatement.bound(vararg values: Any?): PreparedStatement {
    values.forEachIndexed { i, v -> setObject(i + 1, v) }
    return this
}

/** What the tool does, as it does it. */
fun log(line: String) = println("${java.time.LocalTime.now().withNano(0)} $line")
