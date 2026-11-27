package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.database.dictionaries.tables.records.NormalizedSeriesRecord
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_SERIES
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_SERIES
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE LIST OF THE NORMALIZED SERIES OF ONE CONTENT TYPE, as kept in the dictionaries ([NormalizedSeriesRepository]): a
 * page chosen in the database by the columns kept of every series ([SeriesFilter]) — its filter, its order and its
 * limit in SQL, the total counted by the same filter —, and the series of a few numbers as the list shows them.
 */
class SeriesList(private val db: DSLContext, private val type: String) {

    /** The series [ids] of the type, as their list shows them: id to it; none of no such series. */
    fun entries(ids: Collection<Long>): Map<Long, SeriesEntry> =
        ids.distinct()
            .chunked(BATCH)
            .flatMap { chunk ->
                db.selectFrom(NORMALIZED_SERIES)
                    .where(NORMALIZED_SERIES.ID.`in`(chunk), NORMALIZED_SERIES.CONTENT_TYPE.eq(type))
                    .fetch(::entryOf)
            }
            .associateBy { it.id }

    /**
     * The [offset]th to the [offset] + [limit]th series the [filter] lets, the most works first, of one number of works
     * by their titles, and how many it lets: chosen and counted in the database.
     */
    fun page(filter: SeriesFilter, offset: Int, limit: Int): Pair<Int, List<SeriesEntry>> {
        val n = NORMALIZED_SERIES
        val lets = conditionOf(filter)
        val total = db.fetchCount(n, lets)
        val rows =
            db.selectFrom(n)
                .where(lets)
                .orderBy(n.WORKS.desc(), DSL.lower(n.TITLE).asc(), n.ID.asc())
                .offset(offset)
                .limit(limit)
                .fetch(::entryOf)
        return total to rows
    }

    /** What the [filter] lets of the series of the type, in SQL. */
    private fun conditionOf(filter: SeriesFilter): Condition {
        val n = NORMALIZED_SERIES
        val s = SITE_SERIES
        fun hasSite(source: String) =
            DSL.exists(DSL.selectOne().from(s).where(s.NORMALIZED.eq(n.ID), s.SOURCE.eq(source)))
        return DSL.and(
            listOfNotNull(
                n.CONTENT_TYPE.eq(type),
                filter.search?.takeIf { it.isNotBlank() }?.let { n.TITLES.containsIgnoreCase(it.trim()) },
                filter.said?.let(n.BY_HAND::eq),
                filter.toVisit?.let(n.TO_VISIT::eq),
                n.SITES.ge(filter.minSites).takeIf { filter.minSites > 0 },
            ) + filter.with.map(::hasSite) + filter.without.map { DSL.not(hasSite(it)) }
        )
    }

    private fun entryOf(r: NormalizedSeriesRecord) =
        SeriesEntry(r.id!!, r.title!!, r.works!!, r.sites!!, r.byHand!!, r.toVisit!!)

    private companion object {
        /** The keys of one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500
    }
}
