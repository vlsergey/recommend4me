package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.database.source.tables.references.SERIES
import io.github.vlsergey.recommend4me.database.source.tables.references.SERIES_PLACE
import io.github.vlsergey.recommend4me.source.SeriesData
import java.time.Instant
import org.jooq.DSLContext

/**
 * What a site says of one of its series, as its page said it last: its page, title, description, status (a standard
 * status of the works) and dates; null of a field no page showed. When its page was read last ([seenAt]) and when a
 * work was last made a work of it otherwise ([workSeenAt]): a page read before is to be visited again ([stale]).
 */
data class SiteSeries(
    val key: String,
    val url: String,
    val title: String?,
    val description: String?,
    val status: String?,
    val begunAt: Instant?,
    val updatedAt: Instant?,
    val seenAt: Instant,
    val workSeenAt: Instant?,
) {
    /** Whether a work was made a work of the series after its page was read last: the page did not list it. */
    val stale: Boolean
        get() = workSeenAt != null && workSeenAt > seenAt
}

/**
 * THE SERIES OF ONE SITE, in its source database: what their pages say of them ([SiteSeries]), and the number of a work
 * in a series as the site tells it. That a work is of a series is its facet of the series; here only its place.
 */
class SiteSeriesRepository(private val db: DSLContext) {

    /** What the page said of the series, [now]: a field it does not show kept as it was, a blank description none. */
    fun keep(data: SeriesData, now: Instant) {
        val s = SERIES
        val known = db.selectFrom(s).where(s.SERIES_KEY.eq(data.key)).fetchOne()
        val description = data.description?.trim()
        db.insertInto(s)
            .set(s.SERIES_KEY, data.key)
            .set(s.URL, data.url)
            .set(s.TITLE, data.title ?: known?.title)
            .set(s.DESCRIPTION, if (description == null) known?.description else description.ifEmpty { null })
            .set(s.STATUS, data.status ?: known?.status)
            .set(s.BEGUN_AT, data.begunAt ?: known?.begunAt)
            .set(s.UPDATED_AT, data.updatedAt ?: known?.updatedAt)
            .set(s.SEEN_AT, now)
            .onDuplicateKeyUpdate()
            .set(s.URL, data.url)
            .set(s.TITLE, data.title ?: known?.title)
            .set(s.DESCRIPTION, if (description == null) known?.description else description.ifEmpty { null })
            .set(s.STATUS, data.status ?: known?.status)
            .set(s.BEGUN_AT, data.begunAt ?: known?.begunAt)
            .set(s.UPDATED_AT, data.updatedAt ?: known?.updatedAt)
            .set(s.SEEN_AT, now)
            .execute()
    }

    /**
     * The works of the series [keys] met — a work made a work of them [now], not by their pages: their pages, read
     * before, are to be visited again. A series whose page was never read has no row, and is to be visited anyway.
     */
    fun worksSeen(keys: Collection<String>, now: Instant) {
        if (keys.isEmpty()) return
        db.update(SERIES).set(SERIES.WORK_SEEN_AT, now).where(SERIES.SERIES_KEY.`in`(keys)).execute()
    }

    /** The item's [position] in the series [key], in place of the one before. */
    fun place(key: String, itemId: Long, position: Int) {
        val p = SERIES_PLACE
        db.insertInto(p)
            .set(p.SERIES_KEY, key)
            .set(p.ITEM_ID, itemId)
            .set(p.POSITION, position)
            .onDuplicateKeyUpdate()
            .set(p.POSITION, position)
            .execute()
    }

    /** The item's place in the series [key] forgotten: it is of the series no more. */
    fun unplace(key: String, itemId: Long) {
        db.deleteFrom(SERIES_PLACE).where(SERIES_PLACE.SERIES_KEY.eq(key), SERIES_PLACE.ITEM_ID.eq(itemId)).execute()
    }

    /** What the pages of the series [keys] said of them, of those whose page was seen: key to the series. */
    fun of(keys: Collection<String>): Map<String, SiteSeries> =
        keys
            .distinct()
            .chunked(BATCH)
            .flatMap { chunk -> db.selectFrom(SERIES).where(SERIES.SERIES_KEY.`in`(chunk)).fetch() }
            .associate {
                it.seriesKey!! to
                    SiteSeries(
                        it.seriesKey!!,
                        it.url!!,
                        it.title,
                        it.description,
                        it.status,
                        it.begunAt,
                        it.updatedAt,
                        it.seenAt!!,
                        it.workSeenAt,
                    )
            }

    /** The places of the works of the series [keys] the site told: series to item to its number. */
    fun placesOf(keys: Collection<String>): Map<String, Map<Long, Int>> {
        val out = HashMap<String, HashMap<Long, Int>>()
        keys.distinct().chunked(BATCH).forEach { chunk ->
            db.select(SERIES_PLACE.SERIES_KEY, SERIES_PLACE.ITEM_ID, SERIES_PLACE.POSITION)
                .from(SERIES_PLACE)
                .where(SERIES_PLACE.SERIES_KEY.`in`(chunk))
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }[it.value2()!!] = it.value3()!! }
        }
        return out
    }

    private companion object {
        /** The series read by one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500
    }
}
