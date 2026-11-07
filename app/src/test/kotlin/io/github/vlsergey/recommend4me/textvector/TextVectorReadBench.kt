package io.github.vlsergey.recommend4me.textvector

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.Executors
import kotlin.test.Test
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * HOW FAST THE VECTORS OF THE TEXTS ARE READ OUT OF A SOURCE'S FILE, by every way tried: jOOQ as the application reads
 * them, plain JDBC, H2's lazy execution (no result laid out whole first), a query a text key on threads of their own,
 * the table read whole. Of a copy of a real file named by `RECOMMEND4ME_H2_BENCH` (the path without `.mv.db`), opened
 * read-only; skipped without it.
 *
 * EVERY READING OF A FILE OPENED ANEW, its opening not counted: H2 keeps the result of a query asked again of data
 * unchanged, and its pages read — a second asking on the same connection measures that.
 */
class TextVectorReadBench {

    private val file: String? = System.getenv("RECOMMEND4ME_H2_BENCH")

    private fun open(settings: String = ""): Connection =
        DriverManager.getConnection("jdbc:h2:file:${file!!.replace('\\', '/')};ACCESS_MODE_DATA=r$settings", "sa", "")

    private val keys = listOf("title", "annotation", "pairingsLine", "notes")

    private val sql =
        "SELECT item_id, text_key, vec FROM text_vector WHERE encoder = ? AND text_key IN " +
            keys.joinToString(",", "(", ")") { "'$it'" }

    /** [read] of a file opened anew with [settings], [times] times; the file opened before the clock, closed after. */
    private fun timed(
        what: String,
        settings: String = "",
        times: Int = 3,
        read: (List<Connection>) -> Pair<Int, Long>,
        connections: Int = 1,
    ) {
        val millis =
            (1..times).map {
                val open = List(connections) { open(settings) }
                try {
                    val started = System.nanoTime()
                    val (rows, bytes) = read(open)
                    val ms = (System.nanoTime() - started) / 1_000_000
                    if (it == times) println("$what: $rows rows, ${bytes / (1 shl 20)} MB")
                    ms
                } finally {
                    open.forEach { c -> c.close() }
                }
            }
        println("$what: ${millis.joinToString(" / ")} ms")
    }

    /** Every row of the query on [c]: their number and their vectors' bytes. */
    private fun plain(c: Connection, sql: String, encoder: String?, fetch: Int = 2000): Pair<Int, Long> =
        c.prepareStatement(sql).use { s ->
            if (encoder != null) s.setString(1, encoder)
            s.fetchSize = fetch
            s.executeQuery().use { r ->
                var rows = 0
                var bytes = 0L
                while (r.next()) {
                    r.getString(1)
                    r.getString(2)
                    bytes += r.getBytes(3).size
                    rows++
                }
                rows to bytes
            }
        }

    @Test
    fun `the vectors of the texts read every way`() {
        assumeTrue(file != null, "no file to read: RECOMMEND4ME_H2_BENCH")
        val encoder = "bge-m3"
        timed(
            "jOOQ fetchLazy, as the application",
            read = { (c) ->
                var rows = 0
                var bytes = 0L
                DSL.using(c, SQLDialect.H2).resultQuery(sql, encoder).fetchSize(2000).fetchLazy().use { cursor ->
                    cursor.forEach { rec ->
                        rec.get(0, String::class.java)
                        rec.get(1, String::class.java)
                        bytes += rec.get(2, ByteArray::class.java).size
                        rows++
                    }
                }
                rows to bytes
            },
        )
        timed("JDBC", read = { (c) -> plain(c, sql, encoder) })
        timed(
            "JDBC, the result kept in memory whole",
            ";MAX_MEMORY_ROWS=1000000",
            read = { (c) -> plain(c, sql, encoder) },
        )
        timed("JDBC, lazy", ";LAZY_QUERY_EXECUTION=TRUE", read = { (c) -> plain(c, sql, encoder) })
        timed(
            "JDBC, lazy, the table whole, no condition",
            ";LAZY_QUERY_EXECUTION=TRUE",
            read = { (c) ->
                plain(c, "SELECT item_id, text_key, vec FROM text_vector", null)
            },
        )
        val pool = Executors.newFixedThreadPool(keys.size)
        try {
            timed(
                "JDBC, lazy, a text key a thread",
                ";LAZY_QUERY_EXECUTION=TRUE",
                connections = keys.size,
                read = { cs ->
                    val parts =
                        keys
                            .mapIndexed { k, key ->
                                pool.submit<Pair<Int, Long>> {
                                    plain(
                                        cs[k],
                                        "SELECT item_id, text_key, vec FROM text_vector WHERE encoder = ? AND text_key = '$key'",
                                        encoder,
                                    )
                                }
                            }
                            .map { it.get() }
                    parts.sumOf { it.first } to parts.sumOf { it.second }
                },
            )
        } finally {
            pool.shutdown()
        }
        // The same rows in a file of their own, not compressed: what the unpacking of the pages costs
        val plainFile = file + "-plain"
        java.io.File("$plainFile.mv.db").delete()
        DriverManager.getConnection("jdbc:h2:file:${plainFile.replace('\\', '/')};COMPRESS=FALSE", "sa", "").use { to ->
            to.createStatement().use {
                it.execute(
                    "CREATE TABLE text_vector (item_id VARCHAR(200) NOT NULL, text_key VARCHAR(100) NOT NULL, encoder VARCHAR(100) NOT NULL, vec BINARY VARYING NOT NULL, PRIMARY KEY (item_id, text_key))"
                )
            }
            to.autoCommit = false
            open(";LAZY_QUERY_EXECUTION=TRUE").use { from ->
                from.prepareStatement("SELECT item_id, text_key, encoder, vec FROM text_vector").use { s ->
                    s.fetchSize = 2000
                    to.prepareStatement("INSERT INTO text_vector VALUES (?, ?, ?, ?)").use { insert ->
                        s.executeQuery().use { r ->
                            var k = 0
                            while (r.next()) {
                                for (c in 1..3) insert.setString(c, r.getString(c))
                                insert.setBytes(4, r.getBytes(4))
                                insert.addBatch()
                                if (++k % 1000 == 0) insert.executeBatch()
                            }
                            insert.executeBatch()
                        }
                    }
                }
            }
            to.commit()
            to.createStatement().use { it.execute("SHUTDOWN COMPACT") }
        }
        println("not compressed: ${java.io.File("$plainFile.mv.db").length() / (1 shl 20)} MB")
        val plainOpen = { settings: String ->
            DriverManager.getConnection(
                "jdbc:h2:file:${plainFile.replace('\\', '/')};ACCESS_MODE_DATA=r$settings",
                "sa",
                "",
            )
        }
        val millis =
            (1..3).map {
                plainOpen(";LAZY_QUERY_EXECUTION=TRUE").use { c ->
                    val started = System.nanoTime()
                    plain(c, sql, encoder)
                    (System.nanoTime() - started) / 1_000_000
                }
            }
        println("JDBC, lazy, a file not compressed: ${millis.joinToString(" / ")} ms")

        // The pages kept by H2 after a first reading: the second and third of one connection, of other text
        open(";LAZY_QUERY_EXECUTION=TRUE;CACHE_SIZE=1048576").use { c ->
            val millis =
                (1..3).map { k ->
                    val started = System.nanoTime()
                    plain(c, "$sql AND $k = $k", encoder)
                    (System.nanoTime() - started) / 1_000_000
                }
            println("JDBC, lazy, a page cache of 1 GB, read again on one connection: ${millis.joinToString(" / ")} ms")
        }
    }
}
