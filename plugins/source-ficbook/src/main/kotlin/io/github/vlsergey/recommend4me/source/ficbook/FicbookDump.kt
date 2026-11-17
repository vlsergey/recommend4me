package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.Job
import io.github.vlsergey.recommend4me.source.SourceContext
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Properties
import kotlin.io.path.name
import org.duckdb.DuckDBDriver
import org.duckdb.DuckDBStruct
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(FicbookDump::class.java)

/**
 * A DUMP OF THE SITE SOMEONE ELSE MADE, imported instead of the site's own pages (user, 2026-11-17): the dataset
 * "IlyaGusev/ficbook" of Hugging Face — 1.39 million works with the text of every part, collected up to 2024 — its
 * Parquet files put in the source's folder, [FOLDER]. Read by DuckDB file by file in the order of their names, a
 * [BATCH] of works written at a time ([io.github.vlsergey.recommend4me.source.ItemStore.batch]), the place reached kept
 * ([Job.state]): a stop or a restart goes on from it. Nothing is asked of the site. What a work of the dump is written
 * as — and what of a work known already — [DumpWrites] says.
 */
internal class FicbookDump(private val context: SourceContext, private val job: Job, itemIdOf: (String) -> String?) {
    private val writes = DumpWrites(context.items, itemIdOf)

    /** Every file of the dump read and its works written, from the place a stop left; a dump of no files says so. */
    fun import() {
        val files = files()
        if (files.isEmpty()) {
            job.message(NO_DUMP)
            return
        }
        DuckDBDriver().connect("jdbc:duckdb:", Properties()).use { db ->
            val sizes = files.associateWith { rows(db, it) }
            val reached = job.state(FILE)
            val from = reached?.let { r -> files.indexOfFirst { it.name == r } }?.takeIf { it >= 0 } ?: 0
            var done = sizes.filterKeys { files.indexOf(it) < from }.values.sum()
            job.phase(PHASE, sizes.values.sum().toInt(), done.toInt())
            for (file in files.drop(from)) {
                val skip = if (file.name == reached) job.state(ROW)?.toLongOrNull() ?: 0 else 0
                job.state(FILE, file.name)
                done += skip
                read(db, file, skip) { batch, rowsAfter ->
                    job.checkCancelled()
                    val fresh = context.items.batch { batch.count(writes::write) }
                    job.found(fresh)
                    job.state(ROW, rowsAfter.toString())
                    done += batch.size
                    job.progress(done.toInt())
                }
                log.info("ficbook: the dump's file {} imported", file.name)
            }
            job.state(FILE, null)
            job.state(ROW, null)
        }
    }

    /** The Parquet files of the dump in the source's folder, by their names. */
    private fun files(): List<Path> {
        val folder = context.folder.resolve(FOLDER)
        if (!Files.isDirectory(folder)) return emptyList()
        return Files.list(folder).use { all ->
            all.filter { it.name.endsWith(".parquet") }.sorted(compareBy { it.name }).toList()
        }
    }

    private fun rows(db: Connection, file: Path): Long =
        db.createStatement().use { s ->
            s.executeQuery("SELECT count(*) FROM read_parquet(${literal(file)})").use { r ->
                r.next()
                r.getLong(1)
            }
        }

    /**
     * The works of the [file] past its first [skip] rows, handed a [BATCH] at a time to [batch] with the rows of the
     * file read by the end of it.
     */
    private fun read(db: Connection, file: Path, skip: Long, batch: (List<DumpWork>, Long) -> Unit) {
        val sql = "SELECT $COLUMNS FROM read_parquet(${literal(file)}) OFFSET $skip"
        db.createStatement().use { s ->
            s.fetchSize = BATCH
            s.executeQuery(sql).use { r ->
                val works = ArrayList<DumpWork>(BATCH)
                var rows = skip
                while (r.next()) {
                    works += work(r)
                    rows++
                    if (works.size == BATCH) {
                        batch(works.toList(), rows)
                        works.clear()
                    }
                }
                if (works.isNotEmpty()) batch(works, rows)
            }
        }
    }

    private fun work(r: ResultSet) =
        DumpWork(
            url = r.getString("url").orEmpty(),
            title = r.getString("title").orEmpty(),
            authors = strings(r, "authors"),
            tags = strings(r, "tags"),
            description = r.getString("description"),
            likes = r.getLong("likes").takeUnless { r.wasNull() },
            partCount = r.getLong("part_count").takeUnless { r.wasNull() },
            rating = r.getString("rating"),
            status = r.getString("status"),
            direction = r.getString("direction"),
            fandom = r.getString("category"),
            pairing = r.getString("pairing"),
            parts = parts(r),
        )

    private fun strings(r: ResultSet, column: String): List<String> =
        (r.getArray(column)?.array as? Array<*>)
            ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()

    private fun parts(r: ResultSet): List<DumpPart> =
        (r.getArray("parts")?.array as? Array<*>)
            ?.mapNotNull { it as? DuckDBStruct }
            ?.map { s ->
                val p = s.map
                DumpPart(
                    url = p["url"] as? String ?: "",
                    title = p["title"] as? String,
                    text = p["text"] as? String,
                    publishedAt = (p["date"] as? String)?.let(::dateOf),
                )
            }
            .orEmpty()

    private companion object {
        /** The folder of the dump in the source's own folder. */
        const val FOLDER = "dump"

        /** The works written at once: one batch of the application's binding of tags, fandoms and authors. */
        const val BATCH = 1000

        const val PHASE = "import"

        /** The job's word that the folder of the dump has no file. */
        const val NO_DUMP = "noDump"

        /** The file being read, and the rows of it written: where a stop or a restart goes on from. */
        const val FILE = "dump.file"
        const val ROW = "dump.row"

        /** The columns read: the parts with their texts; of them the cleaned text, a second copy, is not used. */
        const val COLUMNS =
            "url, title, authors, tags, description, likes, part_count, rating, status, direction, category, " +
                "pairing, parts"

        fun literal(file: Path): String = "'" + file.toAbsolutePath().toString().replace("'", "''") + "'"

        /** A part's date as the dump writes it, "2011-09-11", the start of that day; another form none. */
        fun dateOf(written: String) =
            try {
                LocalDate.parse(written.trim()).atStartOfDay().toInstant(ZoneOffset.UTC)
            } catch (_: DateTimeParseException) {
                null
            }
    }
}
