package io.github.vlsergey.recommend4me.duplicate

import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.Random
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * HOW FAST THE PAIRS OF A SEARCH OF DUPLICATES ARE WRITTEN, by every way tried, into a file of H2 made anew with the
 * application's settings (its pages compressed): as the application writes them — a statement bound a row, a thousand
 * rows a batch, into the table with its key —; the same into a table of no key, the key and the index made after and
 * the table put in place of the last by its name; the rows written to a CSV file and the table made of it by H2 at once
 * (`CREATE TABLE … AS SELECT … FROM CSVREAD`), the same after. Of synthetic pairs as many as `RECOMMEND4ME_WRITE_BENCH`
 * says (2.7 million a search of 2026-11-20); skipped without it.
 */
class CandidateWriteBench {

    private val count: Int? = System.getenv("RECOMMEND4ME_WRITE_BENCH")?.toInt()

    private val columns =
        listOf(
            "SOURCE VARCHAR(100) NOT NULL",
            "ITEM_ID VARCHAR(200) NOT NULL",
            "OTHER_SOURCE VARCHAR(100) NOT NULL",
            "OTHER_ITEM_ID VARCHAR(200) NOT NULL",
            "TITLE REAL NOT NULL",
            "DESCRIPTION REAL",
            "FACETS VARCHAR(4000)",
            "SIZE REAL",
            "FOUND_FOR TINYINT DEFAULT 0 NOT NULL",
            "SAME_AUTHOR BOOLEAN DEFAULT FALSE NOT NULL",
            "TITLE_WITHIN REAL",
            "TITLE_HOLDING REAL",
            "AUTHORS_ALIKE REAL",
            "AUTHORS_WITHIN REAL",
            "AUTHORS_HOLDING REAL",
            "TITLE_VECTOR REAL",
            "DESCRIPTION_LETTERS REAL",
            "FANDOM_LETTERS REAL",
            "CHARACTER_LETTERS REAL",
            "PARTLY_SAME_AUTHOR BOOLEAN DEFAULT FALSE NOT NULL",
            "WORDS REAL",
            "OTHER_WORDS REAL",
        )
    private val names = columns.map { it.substringBefore(' ') }
    private val key = "SOURCE, ITEM_ID, OTHER_SOURCE, OTHER_ITEM_ID"

    /** A pair: its row's values in the order of [columns]. */
    private class Row(val values: Array<Any?>)

    /** [n] pairs as a search makes them, in the order of the table's key. */
    private fun rows(n: Int): List<Row> {
        val random = Random(1)
        val sources = listOf("ao3", "aff", "author.today", "fanfics.me", "ffn", "ficbook", "molwe")
        fun real() = random.nextFloat()
        fun maybe() = if (random.nextBoolean()) random.nextFloat() else null
        return List(n) {
                val a = random.nextInt(sources.size)
                val b = (a + 1 + random.nextInt(sources.size - 1)) % sources.size
                Row(
                    arrayOf(
                        sources[a],
                        (random.nextInt(BIG)).toString(),
                        sources[b],
                        (random.nextInt(BIG)).toString(),
                        real(),
                        maybe(),
                        "tags=${real()}\ncharacters=${real()}\nlanguages=${real()}",
                        maybe(),
                        random.nextInt(4).toByte(),
                        random.nextInt(20) == 0,
                        real(),
                        real(),
                        maybe(),
                        maybe(),
                        maybe(),
                        real(),
                        maybe(),
                        maybe(),
                        maybe(),
                        random.nextInt(50) == 0,
                        maybe(),
                        maybe(),
                    )
                )
            }
            .distinctBy { listOf(it.values[0], it.values[1], it.values[2], it.values[3]) }
            .sortedWith(
                compareBy<Row>(
                    { it.values[0] as String },
                    { it.values[1] as String },
                    { it.values[2] as String },
                    { it.values[3] as String },
                )
            )
    }

    private fun open(dir: File): Connection =
        DriverManager.getConnection(
            "jdbc:h2:file:${File(dir, "bench").absolutePath.replace('\\', '/')};COMPRESS=TRUE;DB_CLOSE_ON_EXIT=FALSE",
            "sa",
            "",
        )

    /** The time of [what] told, by its [name]. */
    private fun <T> lap(laps: MutableList<String>, name: String, what: () -> T): T {
        val started = System.nanoTime()
        return what().also { laps += "$name ${(System.nanoTime() - started) / 1_000_000}" }
    }

    /** The [rows] bound to a statement a row, a thousand a batch, into the table [table]. */
    private fun batched(c: Connection, table: String, rows: List<Row>) {
        c.prepareStatement("INSERT INTO $table (${names.joinToString()}) VALUES (${names.joinToString { "?" }})").use {
            s ->
            rows.chunked(BATCH).forEach { chunk ->
                chunk.forEach { r ->
                    r.values.forEachIndexed { k, v ->
                        if (v == null) s.setNull(k + 1, Types.REAL) else s.setObject(k + 1, v)
                    }
                    s.addBatch()
                }
                s.executeBatch()
            }
        }
    }

    /** The key and the index of the other work made of the table [table], and it put in place of the last. */
    private fun keyedAndSwapped(c: Connection, table: String, laps: MutableList<String>) {
        c.createStatement().use { s ->
            lap(laps, "key") { s.execute("ALTER TABLE $table ADD PRIMARY KEY ($key)") }
            lap(laps, "index") { s.execute("CREATE INDEX ${table}_OTHER ON $table(OTHER_SOURCE, OTHER_ITEM_ID)") }
            lap(laps, "swap") {
                s.execute("DROP TABLE IF EXISTS DUPLICATE_CANDIDATE")
                s.execute("ALTER TABLE $table RENAME TO DUPLICATE_CANDIDATE")
            }
        }
    }

    /** The way of [write] timed in a file of its own, a pass before of the same to fill the table it replaces. */
    private fun timed(way: String, rows: List<Row>, write: (Connection, MutableList<String>) -> Unit) {
        val dir = Files.createTempDirectory("candidate-write").toFile()
        try {
            open(dir).use { c ->
                c.createStatement().use { s ->
                    s.execute("CREATE TABLE DUPLICATE_CANDIDATE (${columns.joinToString()}, PRIMARY KEY ($key))")
                }
                c.autoCommit = false
                batched(c, "DUPLICATE_CANDIDATE", rows)
                c.commit()
                val laps = ArrayList<String>()
                val started = System.nanoTime()
                write(c, laps)
                lap(laps, "commit") { c.commit() }
                println("$way: ${(System.nanoTime() - started) / 1_000_000} ms — ${laps.joinToString(", ")}")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the pairs written every way`() {
        assumeTrue(count != null, "no number of pairs: RECOMMEND4ME_WRITE_BENCH")
        val rows = rows(count!!)
        println("${rows.size} pairs")
        timed("batched into the table emptied, its key kept, the index after", rows) { c, laps ->
            c.createStatement().use { s ->
                lap(laps, "emptied") {
                    s.execute("TRUNCATE TABLE DUPLICATE_CANDIDATE")
                    s.execute("DROP INDEX IF EXISTS DUPLICATE_CANDIDATE_OTHER")
                }
                lap(laps, "inserted") { batched(c, "DUPLICATE_CANDIDATE", rows) }
                lap(laps, "index") {
                    s.execute(
                        "CREATE INDEX DUPLICATE_CANDIDATE_OTHER ON DUPLICATE_CANDIDATE(OTHER_SOURCE, OTHER_ITEM_ID)"
                    )
                }
            }
        }
        timed("batched into a new table of no key, the key and the index after, swapped", rows) { c, laps ->
            c.createStatement().use { s -> s.execute("CREATE TABLE CANDIDATE_NEW (${columns.joinToString()})") }
            lap(laps, "inserted") { batched(c, "CANDIDATE_NEW", rows) }
            keyedAndSwapped(c, "CANDIDATE_NEW", laps)
        }
        timed("a CSV file read by H2 into a new table, the key and the index after, swapped", rows) { c, laps ->
            val csv = File.createTempFile("candidates", ".csv")
            try {
                lap(laps, "csv written") {
                    csv.bufferedWriter().use { w ->
                        rows.forEach { r ->
                            r.values.forEachIndexed { k, v ->
                                if (k > 0) w.write(",")
                                when (v) {
                                    null -> {}
                                    is String -> w.write("\"" + v.replace("\"", "\"\"") + "\"")
                                    else -> w.write(v.toString())
                                }
                            }
                            w.write("\n")
                        }
                    }
                }
                lap(laps, "read") {
                    c.createStatement().use { s ->
                        s.execute(
                            "CREATE TABLE CANDIDATE_NEW (${columns.joinToString()}) AS SELECT * FROM CSVREAD('" +
                                csv.absolutePath.replace('\\', '/') +
                                "', '${names.joinToString(",")}', 'charset=UTF-8 fieldSeparator=,')"
                        )
                    }
                }
                keyedAndSwapped(c, "CANDIDATE_NEW", laps)
            } finally {
                csv.delete()
            }
        }
    }

    private companion object {
        const val BATCH = 1000
        const val BIG = 2_000_000
    }
}
