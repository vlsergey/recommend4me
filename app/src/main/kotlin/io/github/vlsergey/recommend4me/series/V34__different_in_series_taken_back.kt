package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_FACET
import io.github.vlsergey.recommend4me.item.ItemKey
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(V34__different_in_series_taken_back::class.java)

/** A source's file opened and migrated, and the key of its facet of the series: what the migration reads of it. */
class SourceSeriesFile(val source: String, val facet: String, val db: DSLContext)

/**
 * THE WORDS "DIFFERENT" SAID BEFORE ON TWO WORKS OF ONE SERIES TAKEN BACK, at once (owner, 2026-11-20: "the existing
 * contradictions — a migration will do; migrations are always synchronous"), as [SeriesDifferences] does as the series
 * change: the normalized series made as the application makes them ([SeriesJoin]) of every item's series of its site —
 * read of the files of the [sources], opened before the dictionaries for it —, every item's normalized work and the
 * user's groups; of every two works said to be different and of one series, the word taken back, in the journal so, of
 * an item of each. A migration of the application's own code, given the files it reads — not found among the migrations
 * of the dictionaries ([io.github.vlsergey.recommend4me.database.Database]).
 */
@Suppress("ClassName")
class V34__different_in_series_taken_back(private val sources: List<SourceSeriesFile>) : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val started = System.currentTimeMillis()
        val c = context.connection
        val different = differentWorks(c)
        if (different.isEmpty()) return
        val siteOf = HashMap<ItemKey, String>()
        sources.forEach { s ->
            s.db
                .select(ITEM_FACET.ITEM_ID, ITEM_FACET.VALUE_KEY)
                .from(ITEM_FACET)
                .where(ITEM_FACET.FACET.eq(s.facet))
                .fetch { siteOf.putIfAbsent(ItemKey(s.source, it.value1()!!), it.value2()!!) }
        }
        val groups = groups(c)
        val named = different.flatMap { listOf(it.first, it.second) }.toHashSet()
        // Every item of a series of a site with its work, and an item of every work of a word: one pass of the bindings
        val workOf = HashMap<ItemKey, Long>()
        val itemOf = HashMap<Long, ItemKey>()
        query(c, "SELECT source, item, normalized FROM site_work") { r ->
            val key = ItemKey(r.getString(1), r.getString(2))
            val work = r.getLong(3)
            if (key in siteOf) workOf[key] = work
            if (work in named) itemOf.putIfAbsent(work, key)
        }
        val seriesOf = HashMap<Long, String>()
        SeriesJoin(siteOf, workOf, groups).series().forEach { s -> s.works.forEach { seriesOf[it] = s.key } }
        val within = different.filter { (x, y) -> seriesOf[x] != null && seriesOf[x] == seriesOf[y] }
        val now = Timestamp.from(Instant.now())
        within.forEach { (x, y) -> takeBack(c, x, y, itemOf[x], itemOf[y], now) }
        log.info(
            "of {} words \"different\", {} on two works of one series taken back, of {} items of the series of {} " +
                "sites, in {} ms",
            different.size,
            within.size,
            siteOf.size,
            sources.size,
            System.currentTimeMillis() - started,
        )
    }

    /** Every two works said to be different, the smaller id first. */
    private fun differentWorks(c: Connection): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        query(c, "SELECT first_work, other_work FROM normalized_work_relation WHERE kind = 'DIFFERENT'") { r ->
            out += r.getLong(1) to r.getLong(2)
        }
        return out
    }

    /** Every group of the user's: its works. */
    private fun groups(c: Connection): Map<Long, Set<Long>> {
        val out = HashMap<Long, MutableSet<Long>>()
        query(c, "SELECT series, work FROM series_group_work") { r ->
            out.getOrPut(r.getLong(1)) { HashSet() } += r.getLong(2)
        }
        return out
    }

    /** The word on the works [x] and [y] taken back, in the journal of their items [a] and [b] when both have one. */
    private fun takeBack(c: Connection, x: Long, y: Long, a: ItemKey?, b: ItemKey?, now: Timestamp) {
        c.prepareStatement("DELETE FROM normalized_work_relation WHERE first_work = ? AND other_work = ?").use {
            it.setLong(1, x)
            it.setLong(2, y)
            it.executeUpdate()
        }
        if (a == null || b == null) return
        c.prepareStatement(
                "INSERT INTO work_pair_journal (said_at, source, item, other_source, other_item, answer, " +
                    "answer_before, of_series) VALUES (?, ?, ?, ?, ?, NULL, 'DIFFERENT', TRUE)"
            )
            .use { s ->
                listOf(now, a.source, a.id, b.source, b.id).forEachIndexed { k, v -> s.setObject(k + 1, v) }
                s.executeUpdate()
            }
    }

    private fun query(c: Connection, sql: String, row: (java.sql.ResultSet) -> Unit) =
        c.prepareStatement(sql).use { s ->
            s.fetchSize = FETCH
            s.executeQuery().use { r -> while (r.next()) row(r) }
        }

    private companion object {
        const val FETCH = 10_000
    }
}
