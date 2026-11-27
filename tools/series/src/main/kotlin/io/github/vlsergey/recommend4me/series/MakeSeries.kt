package io.github.vlsergey.recommend4me.series

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalTime
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * THE ONE-TIME MAKING OF THE NORMALIZED SERIES OF THE DATA KEPT (2026-11-27; owner: "the series are an entity of the
 * same order as the authors, the works, the fandoms, the tags"): of every source, every item's series of its site — the
 * first its facet of the series names —, and of the dictionaries every item's normalized work and the user's groups of
 * works said one series, joined as the application joins them (series.SeriesJoin: every series of a site and every
 * group sharing a work one series), each written as a normalized series with its works, its series of the sites and the
 * columns its list is filtered and ordered by (series.SeriesColumns). Run once, the application stopped, after the
 * version that makes the tables (dictionaries V44) has started once; whatever series that version made the while are
 * made anew with the rest.
 *
 * `make-series <data folder> [--dry-run]`: of `--dry-run`, what would be made counted, nothing written.
 */
fun main(args: Array<String>) {
    val data = Path.of(args.firstOrNull() ?: error("make-series <data folder> [--dry-run]"))
    val dryRun = "--dry-run" in args
    val read = Read()
    sourcesOf(data).forEach { dir -> connect(dir.resolve("source")).use { read.source(dir.name, it) } }
    connect(data.resolve("dictionaries")).use { dictionaries ->
        // What the version that made the tables made of them while it ran, made anew with the rest
        if (!dryRun) dictionaries.createStatement().use { s -> s.execute("DELETE FROM normalized_series") }
        read.works(dictionaries)
        val series = Joined(read).series()
        val works = series.sumOf { it.works.size }
        log("${series.size} series of $works works, ${series.sumOf { it.sites.size }} series of the sites")
        val titles = Titles(read, sourcesOf(data).associateBy { it.name }, series)
        if (!dryRun) write(dictionaries, read, series, titles)
    }
    log("done")
}

/** What the sources and the dictionaries say of the series: the items' series, their works, the user's groups. */
internal class Read {
    /** Every item's series of its site: (source, item) to its key. */
    val siteOf = HashMap<Pair<String, Long>, String>()

    /** Of every source, the names of its series' keys and what their pages said. */
    val names = HashMap<String, Map<String, String>>()
    val pages = HashMap<String, Map<String, Page>>()

    /** Every item's normalized work, and every work's items. */
    val workOf = HashMap<Pair<String, Long>, Long>()
    val itemsOf = HashMap<Long, MutableList<Pair<String, Long>>>()

    /** The user's groups: group to its works. */
    val groups = HashMap<Long, MutableSet<Long>>()

    /** A page of a series of a site as kept: its title, whether it is to be visited. */
    class Page(val title: String?, val stale: Boolean)

    fun source(source: String, c: Connection) {
        var n = 0
        c.createStatement().use { s ->
            s.executeQuery(
                    "SELECT item_id, value_key FROM item_facet WHERE facet = 'series' ORDER BY item_id, position"
                )
                .use { rs ->
                    while (rs.next()) {
                        if (siteOf.putIfAbsent(source to rs.getLong(1), rs.getString(2)) == null) n++
                    }
                }
            names[source] =
                s.executeQuery("SELECT value_key, name FROM facet_value WHERE facet = 'series'").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) }
                }
            pages[source] =
                s.executeQuery("SELECT series_key, title, seen_at, work_seen_at FROM series").use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val seen = rs.getTimestamp(3)
                            val workSeen = rs.getTimestamp(4)
                            put(rs.getString(1), Page(rs.getString(2), workSeen != null && workSeen.after(seen)))
                        }
                    }
                }
        }
        log("$source: $n items of a series")
    }

    fun works(c: Connection) {
        c.createStatement().use { s ->
            s.executeQuery("SELECT source, item, normalized FROM site_work ORDER BY source, item").use { rs ->
                while (rs.next()) {
                    val item = rs.getString(1) to rs.getLong(2)
                    val work = rs.getLong(3)
                    workOf[item] = work
                    itemsOf.getOrPut(work) { ArrayList() } += item
                }
            }
            s.executeQuery("SELECT series, work FROM series_group_work").use { rs ->
                while (rs.next()) groups.getOrPut(rs.getLong(1)) { HashSet() } += rs.getLong(2)
            }
        }
    }
}

/**
 * A normalized series as joined: its works, its series of the sites ("source/key"), whether the user's word holds it.
 */
internal class JoinedSeries(val works: Set<Long>, val sites: List<String>, val byHand: Boolean)

/** The join of the series of the sites and the user's groups, as the application's series.SeriesJoin makes it. */
internal class Joined(private val read: Read) {
    private class Roots {
        val parent = HashMap<String, String>()

        fun root(n: String): String {
            var r = n
            while (parent[r] != null && parent[r] != r) r = parent[r]!!
            parent[n] = r
            return r
        }

        fun joinAll(nodes: Collection<String>) {
            nodes.forEach { parent.putIfAbsent(it, it) }
            nodes.reduce { a, b ->
                val x = root(a)
                val y = root(b)
                if (x != y) parent[maxOf(x, y)] = minOf(x, y)
                a
            }
        }
    }

    fun series(): List<JoinedSeries> {
        val nodesOf = HashMap<Long, MutableSet<String>>()
        read.siteOf.forEach { (item, key) ->
            read.workOf[item]?.let { nodesOf.getOrPut(it) { HashSet() } += "${item.first}/$key" }
        }
        read.groups.forEach { (group, works) -> works.forEach { nodesOf.getOrPut(it) { HashSet() } += "$SAID$group" } }
        val joined = Roots().apply { nodesOf.values.forEach(::joinAll) }
        val bySites = Roots()
        nodesOf.values.forEach { nodes ->
            nodes.filter { !it.startsWith(SAID) }.takeIf { it.isNotEmpty() }?.let(bySites::joinAll)
        }
        val worksOf = HashMap<String, MutableSet<Long>>()
        nodesOf.forEach { (work, nodes) -> worksOf.getOrPut(joined.root(nodes.first())) { HashSet() } += work }
        return joined.parent.keys.groupBy(joined::root).map { (root, nodes) ->
            val works = worksOf[root].orEmpty()
            val said = nodes.any { it.startsWith(SAID) }
            val held =
                works
                    .map { w -> nodesOf[w].orEmpty().firstOrNull { !it.startsWith(SAID) }?.let(bySites::root) }
                    .let { roots -> null !in roots && roots.toSet().size == 1 }
            JoinedSeries(works, nodes.filter { !it.startsWith(SAID) }.sorted(), said && !held)
        }
    }

    private companion object {
        const val SAID = "said-"
    }
}

/** The titles of the series and whether a page of one is to be visited, as series.SeriesColumns makes them. */
internal class Titles(private val read: Read, private val sources: Map<String, Path>, series: List<JoinedSeries>) {
    /** Of a series of no series of a site, the title of the first item of its first work. */
    private val firstTitles = HashMap<Pair<String, Long>, String>()

    init {
        val wanted =
            series
                .filter { it.sites.isEmpty() }
                .mapNotNull { s -> s.works.minOrNull()?.let { read.itemsOf[it]?.firstOrNull() } }
        wanted.groupBy({ it.first }, { it.second }).forEach { (source, ids) ->
            val dir = sources[source] ?: return@forEach
            connect(dir.resolve("source")).use { c ->
                ids.chunked(BATCH).forEach { chunk ->
                    c.createStatement().use { s ->
                        s.executeQuery(
                                "SELECT internal_id, title FROM item WHERE internal_id IN (${chunk.joinToString(",")})"
                            )
                            .use { rs ->
                                while (rs.next()) firstTitles[source to rs.getLong(1)] = rs.getString(2)
                            }
                    }
                }
            }
        }
    }

    /** Of the series [s]: its title, every title it is known by, whether a page of it is to be visited. */
    fun of(s: JoinedSeries): Triple<String, String, Boolean> {
        val own =
            s.sites.mapNotNull { ref ->
                val source = ref.substringBefore('/')
                val key = ref.substringAfter('/')
                read.pages[source]?.get(key)?.title ?: read.names[source]?.get(key)
            }
        val toVisit =
            s.sites.any { ref -> read.pages[ref.substringBefore('/')]?.get(ref.substringAfter('/'))?.stale != false }
        val first = s.works.minOrNull()?.let { read.itemsOf[it]?.firstOrNull() }?.let(firstTitles::get)
        val title = own.firstOrNull() ?: first ?: s.sites.firstOrNull() ?: ""
        return Triple(title, (listOf(title) + own).distinct().joinToString("\n"), toVisit)
    }
}

/** The [series] written: every one numbered, its works and its series of the sites bound to it. */
private fun write(c: Connection, read: Read, series: List<JoinedSeries>, titles: Titles) {
    c.autoCommit = false
    val now = Timestamp.from(Instant.now())
    val insert =
        c.prepareStatement(
            "INSERT INTO normalized_series (content_type, works_at, title, titles, works, sites, by_hand, to_visit) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf("ID"),
        )
    val work = c.prepareStatement("INSERT INTO normalized_series_work (work, series) VALUES (?, ?)")
    val site =
        c.prepareStatement("INSERT INTO site_series (source, series_key, normalized, bound_at) VALUES (?, ?, ?, ?)")
    series.forEachIndexed { k, s ->
        val (title, all, toVisit) = titles.of(s)
        val source =
            s.sites.firstOrNull()?.substringBefore('/')
                ?: s.works.firstNotNullOfOrNull { read.itemsOf[it]?.firstOrNull()?.first }
        val sites = s.sites.map { it.substringBefore('/') }.distinct().size
        insert.bound(typeOf(source), now, title.take(TITLE), all.take(TITLES), s.works.size, sites, s.byHand, toVisit)
        insert.executeUpdate()
        val id =
            insert.generatedKeys.use {
                it.next()
                it.getLong(1)
            }
        s.works.forEach { work.bound(it, id).addBatch() }
        s.sites.forEach { site.bound(it.substringBefore('/'), it.substringAfter('/'), id, now).addBatch() }
        if (k % BATCH == BATCH - 1) {
            work.executeBatch()
            site.executeBatch()
            c.commit()
            log("${k + 1} of ${series.size} series written")
        }
    }
    work.executeBatch()
    site.executeBatch()
    c.commit()
}

/** The content type of the source: f95zone's of the games, every other one's of the books. */
private fun typeOf(source: String?): String = if (source == "f95zone") "games" else "books"

private fun sourcesOf(data: Path): List<Path> =
    Files.list(data.resolve("sources")).use { dirs ->
        dirs.filter { it.resolve("source.mv.db").exists() }.sorted().toList()
    }

private fun connect(base: Path): Connection =
    DriverManager.getConnection(
        "jdbc:h2:file:${base.toAbsolutePath().toString().replace('\\', '/')};DB_CLOSE_ON_EXIT=FALSE;COMPRESS=TRUE",
        "sa",
        "",
    )

/** The statement given the [values] of its parameters, in their order. */
private fun PreparedStatement.bound(vararg values: Any?): PreparedStatement {
    values.forEachIndexed { k, v -> setObject(k + 1, v) }
    return this
}

private fun log(line: String) = println("${LocalTime.now().withNano(0)} $line")

private const val BATCH = 1000
private const val TITLE = 1000
private const val TITLES = 4000
