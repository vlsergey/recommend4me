package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.database.WholeKeys
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_SERIES
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_SERIES_WORK
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_SERIES
import java.time.Instant
import org.jooq.DSLContext

/** A normalized series as its list shows it: its number and what the list filters and orders it by. */
data class SeriesEntry(
    val id: Long,
    val title: String,
    val works: Int,
    val sites: Int,
    val byHand: Boolean,
    val toVisit: Boolean,
)

/**
 * A normalized series as a join makes it ([SeriesBinding]): its number — none of one new —, its works, its series of
 * the sites, and what its list is filtered and ordered by; [worksChanged]: the list of its works other than it was.
 */
class MadeSeries(
    val id: Long?,
    val works: Set<Long>,
    val sites: Set<SiteSeriesRef>,
    val title: String,
    val titles: String,
    val byHand: Boolean,
    val toVisit: Boolean,
    val worksChanged: Boolean,
)

/**
 * THE NORMALIZED SERIES OF ONE CONTENT TYPE, in the dictionaries: every series by its number, the series of the sites
 * bound to it and the works of it — made by [SeriesBinding] of the join of the series of the sites and the user's
 * groups ([SeriesJoin]), read by their keys either way: the series of a work, the works and the sites of a series. A
 * page of them is chosen in the database ([SeriesList]); a computation over every one streams them ([forEachSeries]).
 */
class NormalizedSeriesRepository(private val db: DSLContext, private val type: String) {
    /** The series as their list shows them. */
    val list = SeriesList(db, type)

    /** The series of the [works] of one: work to its series. */
    fun ofWorks(works: Collection<Long>): Map<Long, Long> {
        val w = NORMALIZED_SERIES_WORK
        return works
            .distinct()
            .chunked(BATCH)
            .flatMap { chunk -> db.select(w.WORK, w.SERIES).from(w).where(w.WORK.`in`(chunk)).fetch() }
            .associate { it.value1()!! to it.value2()!! }
    }

    /** The works of the [series]: series to its works. */
    fun worksOf(series: Collection<Long>): Map<Long, Set<Long>> {
        val w = NORMALIZED_SERIES_WORK
        val out = HashMap<Long, MutableSet<Long>>()
        series.distinct().chunked(BATCH).forEach { chunk ->
            db.select(w.SERIES, w.WORK).from(w).where(w.SERIES.`in`(chunk)).fetch {
                out.getOrPut(it.value1()!!) { HashSet() } += it.value2()!!
            }
        }
        return out
    }

    /** The series of the sites of the [series]: series to them. */
    fun sitesOf(series: Collection<Long>): Map<Long, List<SiteSeriesRef>> {
        val s = SITE_SERIES
        val out = HashMap<Long, MutableList<SiteSeriesRef>>()
        series.distinct().chunked(BATCH).forEach { chunk ->
            db.select(s.NORMALIZED, s.SOURCE, s.SERIES_KEY).from(s).where(s.NORMALIZED.`in`(chunk)).fetch {
                out.getOrPut(it.value1()!!) { ArrayList() } += SiteSeriesRef(it.value2()!!, it.value3()!!)
            }
        }
        return out
    }

    /** The normalized series the series of the sites [refs] are bound to: ref to its series. */
    fun ofSites(refs: Collection<SiteSeriesRef>): Map<SiteSeriesRef, Long> {
        val s = SITE_SERIES
        val out = HashMap<SiteSeriesRef, Long>()
        refs.groupBy({ it.source }, { it.key }).forEach { (source, keys) ->
            keys.distinct().chunked(BATCH).forEach { chunk ->
                db.select(s.SERIES_KEY, s.NORMALIZED)
                    .from(s)
                    .where(WholeKeys.of(s.SOURCE, s.SERIES_KEY, source, chunk))
                    .fetch { out[SiteSeriesRef(source, it.value1()!!)] = it.value2()!! }
            }
        }
        return out
    }

    /** Every series of the type with its works, a series at a time, in the order of their numbers: streamed. */
    fun forEachSeries(action: (series: Long, works: List<Long>) -> Unit) {
        val w = NORMALIZED_SERIES_WORK
        var series: Long? = null
        val works = ArrayList<Long>()
        db.select(w.SERIES, w.WORK)
            .from(w)
            .join(NORMALIZED_SERIES)
            .on(NORMALIZED_SERIES.ID.eq(w.SERIES))
            .where(NORMALIZED_SERIES.CONTENT_TYPE.eq(type))
            .orderBy(w.SERIES, w.WORK)
            .fetchSize(BATCH)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach { r ->
                    if (r.value1() != series) {
                        series?.let { action(it, works.toList()) }
                        series = r.value1()
                        works.clear()
                    }
                    works += r.value2()!!
                }
            }
        series?.let { action(it, works.toList()) }
    }

    /**
     * The series [made] written — a new one numbered, every one bound its works and its series of the sites, those of
     * them bound to another before moved —, and the series [gone] taken away, [now]: the numbers of the [made], in
     * their order.
     */
    fun write(made: List<MadeSeries>, gone: Collection<Long>, now: Instant): List<Long> = db.transactionResult { c ->
        val tx = c.dsl()
        val ids = made.map { m -> written(tx, m, now) }
        gone.chunked(BATCH).forEach { chunk ->
            tx.deleteFrom(NORMALIZED_SERIES).where(NORMALIZED_SERIES.ID.`in`(chunk)).execute()
        }
        ids
    }

    /** The series [m] written within the transaction [tx]: its number. */
    private fun written(tx: DSLContext, m: MadeSeries, now: Instant): Long {
        val n = NORMALIZED_SERIES
        val id =
            m.id?.also { id ->
                tx.update(n)
                    .set(n.TITLE, m.title.take(TITLE))
                    .set(n.TITLES, m.titles.take(TITLES))
                    .set(n.WORKS, m.works.size)
                    .set(n.SITES, m.sites.map { it.source }.distinct().size)
                    .set(n.BY_HAND, m.byHand)
                    .set(n.TO_VISIT, m.toVisit)
                    .apply { if (m.worksChanged) set(n.WORKS_AT, now) }
                    .where(n.ID.eq(id))
                    .execute()
            }
                ?: tx.insertInto(n)
                    .set(n.CONTENT_TYPE, type)
                    .set(n.WORKS_AT, now)
                    .set(n.TITLE, m.title.take(TITLE))
                    .set(n.TITLES, m.titles.take(TITLES))
                    .set(n.WORKS, m.works.size)
                    .set(n.SITES, m.sites.map { it.source }.distinct().size)
                    .set(n.BY_HAND, m.byHand)
                    .set(n.TO_VISIT, m.toVisit)
                    .returningResult(n.ID)
                    .fetchOne()!!
                    .value1()!!
        boundWorks(tx, id, m.works)
        boundSites(tx, id, m.sites, now)
        return id
    }

    /** The series [id] of the [works] alone, within the transaction [tx]: every one of another before moved. */
    private fun boundWorks(tx: DSLContext, id: Long, works: Set<Long>) {
        val w = NORMALIZED_SERIES_WORK
        tx.deleteFrom(w).where(w.SERIES.eq(id)).execute()
        works.chunked(BATCH).forEach { chunk ->
            tx.deleteFrom(w).where(w.WORK.`in`(chunk)).execute()
            tx.batch(chunk.map { tx.insertInto(w).set(w.WORK, it).set(w.SERIES, id) }).execute()
        }
    }

    /**
     * The series of the sites [refs] bound to the series [id] alone, within the transaction [tx]: those bound to it
     * before as they were, those of another moved [now], those of it no more let go.
     */
    private fun boundSites(tx: DSLContext, id: Long, refs: Set<SiteSeriesRef>, now: Instant) {
        val s = SITE_SERIES
        val repository = NormalizedSeriesRepository(tx, type)
        val before = repository.sitesOf(listOf(id))[id].orEmpty().toSet()
        val bound = repository.ofSites(refs - before)
        (before - refs + bound.keys).groupBy({ it.source }, { it.key }).forEach { (source, keys) ->
            keys.chunked(BATCH).forEach {
                tx.deleteFrom(s).where(WholeKeys.of(s.SOURCE, s.SERIES_KEY, source, it)).execute()
            }
        }
        val fresh = refs - before
        fresh.chunked(BATCH).forEach { chunk ->
            tx.batch(
                    chunk.map { ref ->
                        tx.insertInto(s)
                            .set(s.SOURCE, ref.source)
                            .set(s.SERIES_KEY, ref.key)
                            .set(s.NORMALIZED, id)
                            .set(s.BOUND_AT, now)
                    }
                )
                .execute()
        }
    }

    private companion object {
        /** The keys of one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500

        /** The widths of the columns of the titles. */
        const val TITLE = 1000
        const val TITLES = 4000
    }
}
