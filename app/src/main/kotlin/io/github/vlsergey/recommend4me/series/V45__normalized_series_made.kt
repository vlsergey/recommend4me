package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V45__normalized_series_made::class.java)

/** A source a migration of the dictionaries reads: its id, its content type, its file without its `.mv.db`. */
class SourceFile(val id: String, val type: String, val file: Path)

/**
 * THE NORMALIZED SERIES OF THE DATA KEPT MADE (owner, 2026-11-27: the series an entity of the same order as the
 * authors, the works, the fandoms, the tags; "the move of the current series is to be a migration"): of every source's
 * file [sources] — opened by a connection of its own, read and closed before the application opens it —, every item's
 * series of its site, the first its facet of the series names, the names and the pages of its series; of the
 * dictionaries every such item's normalized work and the user's groups of works said one series — joined as the
 * application joins them ([SeriesJoin]) and written as [SeriesBinding] writes them: every one numbered, its works, its
 * series of the sites, and the columns its list is filtered and ordered by ([SeriesColumns]). A thousand series at a
 * time, each portion kept as it is done and told in the log; broken off, it goes on with the series none of whose works
 * was written. A migration of the application's own code, given the files it reads.
 */
@Suppress("ClassName")
class V45__normalized_series_made(private val sources: List<SourceFile>) : BaseJavaMigration() {

    override fun canExecuteInTransaction() = false

    override fun migrate(context: Context) {
        val c = context.connection
        val started = System.currentTimeMillis()
        val read = SeriesKept()
        sources
            .filter { Files.exists(Path.of("${it.file}.mv.db")) }
            .forEach { s -> fileOf(s.file).use { read.source(s, it) } }
        read.works(c)
        val series = SeriesJoin(read.siteOf, read.workOf, read.groups).series()
        val written = HashSet<Long>()
        streamed(c, "SELECT work FROM normalized_series_work") { written += it.getLong(1) }
        val todo = series.filter { s -> s.works.none(written::contains) }
        log.info("{} normalized series to be made of {} items of a series of a site", todo.size, read.siteOf.size)
        val autoCommit = c.autoCommit
        c.autoCommit = false
        try {
            write(c, read, todo)
        } finally {
            c.autoCommit = autoCommit
        }
        log.info("{} normalized series made in {} ms", todo.size, System.currentTimeMillis() - started)
    }

    /** The [series] written, a portion at a time, each kept as it is done. */
    private fun write(c: Connection, read: SeriesKept, series: List<NormalizedSeries>) {
        val now = Timestamp.from(Instant.now())
        val insert =
            c.prepareStatement(
                "INSERT INTO normalized_series " +
                    "(content_type, works_at, title, titles, works, sites, by_hand, to_visit) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf("ID"),
            )
        val work = c.prepareStatement("INSERT INTO normalized_series_work (work, series) VALUES (?, ?)")
        val site =
            c.prepareStatement("INSERT INTO site_series (source, series_key, normalized, bound_at) VALUES (?, ?, ?, ?)")
        val titles = read.titles(series)
        series.chunked(PORTION).forEachIndexed { k, portion ->
            portion.forEach { s ->
                val sites = s.sites.keys.sortedBy { it.toString() }
                val own = sites.mapNotNull { read.titleOf(it) }
                val title = own.firstOrNull() ?: titles[s.works.minOrNull()] ?: sites.firstOrNull()?.toString() ?: ""
                val all = (listOf(title) + own).distinct().joinToString("\n")
                val toVisit = sites.any(read::toVisit)
                val source =
                    sites.firstOrNull()?.source
                        ?: s.works.firstNotNullOfOrNull { read.itemsOf[it]?.firstOrNull()?.source }
                insert.bound(
                    read.typeOf(source),
                    now,
                    title.take(TITLE),
                    all.take(TITLES),
                    s.works.size,
                    sites.map { it.source }.distinct().size,
                    s.byHand,
                    toVisit,
                )
                insert.executeUpdate()
                val id =
                    insert.generatedKeys.use {
                        it.next()
                        it.getLong(1)
                    }
                s.works.forEach { work.bound(it, id).addBatch() }
                sites.forEach { site.bound(it.source, it.key, id, now).addBatch() }
            }
            work.executeBatch()
            site.executeBatch()
            c.commit()
            if ((k + 1) % TOLD_EVERY == 0) log.info("{} of {} normalized series made", (k + 1) * PORTION, series.size)
        }
    }

    private companion object {
        /** The series of one portion: kept together, a commit each. */
        const val PORTION = 1000

        /** How many portions go between two lines of the log. */
        const val TOLD_EVERY = 20

        /** The widths of the columns of the titles. */
        const val TITLE = 1000
        const val TITLES = 4000
    }
}

/** What the sources and the dictionaries kept say of the series, as the migration reads it. */
private class SeriesKept {
    val siteOf = HashMap<ItemKey, String>()
    val workOf = HashMap<ItemKey, Long>()
    val itemsOf = HashMap<Long, MutableList<ItemKey>>()
    val groups = HashMap<Long, Set<Long>>()
    private val names = HashMap<SiteSeriesRef, String>()
    private val pages = HashMap<SiteSeriesRef, Pair<String?, Boolean>>()
    private val types = HashMap<String, String>()
    private val files = HashMap<String, Path>()

    fun source(s: SourceFile, c: Connection) {
        types[s.id] = s.type
        files[s.id] = s.file
        streamed(c, "SELECT item_id, value_key FROM item_facet WHERE facet = 'series' ORDER BY item_id, position") {
            siteOf.putIfAbsent(ItemKey(s.id, it.getLong(1)), it.getString(2))
        }
        streamed(c, "SELECT value_key, name FROM facet_value WHERE facet = 'series'") {
            names[SiteSeriesRef(s.id, it.getString(1))] = it.getString(2)
        }
        streamed(c, "SELECT series_key, title, seen_at, work_seen_at FROM series") {
            val seen = it.getTimestamp(3)
            val workSeen = it.getTimestamp(4)
            pages[SiteSeriesRef(s.id, it.getString(1))] = it.getString(2) to (workSeen != null && workSeen.after(seen))
        }
        log.info("{}: {} items of a series of the site", s.id, siteOf.keys.count { it.source == s.id })
    }

    /** Of the dictionaries: the works of the items of a series, the user's groups and the items of their works. */
    fun works(c: Connection) {
        streamed(c, "SELECT series, work FROM series_group_work") {
            groups[it.getLong(1)] = groups[it.getLong(1)].orEmpty() + it.getLong(2)
        }
        val grouped = groups.values.flatten().toHashSet()
        streamed(c, "SELECT source, item, normalized FROM site_work ORDER BY source, item") {
            val item = ItemKey(it.getString(1), it.getLong(2))
            val work = it.getLong(3)
            if (item in siteOf || work in grouped) {
                workOf[item] = work
                itemsOf.getOrPut(work) { ArrayList() } += item
            }
        }
    }

    fun titleOf(ref: SiteSeriesRef): String? = pages[ref]?.first ?: names[ref]

    /** Whether the page of the series of the site [ref] is to be visited: never read, or read before a work was met. */
    fun toVisit(ref: SiteSeriesRef): Boolean = pages[ref]?.second != false

    fun typeOf(source: String?): String = source?.let(types::get) ?: types.values.first()

    /** Of the series of no series of a site, the title of the first item of the first work: work to it. */
    fun titles(series: List<NormalizedSeries>): Map<Long, String> {
        val first =
            series
                .filter { it.sites.isEmpty() }
                .mapNotNull { s -> s.works.minOrNull()?.let { w -> itemsOf[w]?.firstOrNull()?.let { w to it } } }
        val out = HashMap<Long, String>()
        first.groupBy({ it.second.source }, { it }).forEach { (source, of) ->
            val file = files[source] ?: return@forEach
            fileOf(file).use { c ->
                of.chunked(BATCH).forEach { chunk ->
                    val workOfItem = chunk.associate { (w, item) -> item.id to w }
                    val ids = workOfItem.keys.joinToString(",")
                    streamed(c, "SELECT internal_id, title FROM item WHERE internal_id IN ($ids)") {
                        out[workOfItem.getValue(it.getLong(1))] = it.getString(2)
                    }
                }
            }
        }
        return out
    }

    private companion object {
        const val BATCH = 500
    }
}

/** A connection of its own to the H2 file [file] — without its `.mv.db` —, the caller's to close. */
private fun fileOf(file: Path): Connection =
    DriverManager.getConnection(
        "jdbc:h2:file:${file.toAbsolutePath().toString().replace('\\', '/')};DB_CLOSE_ON_EXIT=FALSE;COMPRESS=TRUE",
        "sa",
        "",
    )

/** Every row of the [sql] of the connection [c], one at a time. */
private fun streamed(c: Connection, sql: String, row: (java.sql.ResultSet) -> Unit) =
    c.createStatement().use { s -> s.executeQuery(sql).use { rs -> while (rs.next()) row(rs) } }

/** The statement given the [values] of its parameters, in their order. */
private fun PreparedStatement.bound(vararg values: Any?): PreparedStatement {
    values.forEachIndexed { k, v -> setObject(k + 1, v) }
    return this
}
