package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.database.DuckDatabase
import io.github.vlsergey.recommend4me.database.duplicates.tables.references.DUPLICATE_CANDIDATE
import io.github.vlsergey.recommend4me.database.duplicates.tables.references.DUPLICATE_SEARCH
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.laps.Laps
import java.time.Instant
import org.duckdb.DuckDBAppender
import org.duckdb.DuckDBConnection
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DuplicateRepository::class.java)

/**
 * The candidates of the last search of the content type, in the store of its duplicates ([DuckDatabase]): made anew
 * whole by every search.
 */
class DuplicateRepository(private val store: DuckDatabase) {
    private val db: DSLContext = store.dsl

    /** How rare every value compared is, as the last search weighed it: what a pair it did not keep is compared by. */
    val rarity = ValueRarities(db)

    /** The pairs of a page chosen by its filters and its sort. */
    val choice = DuplicateChoice(db)

    /** The candidates read: by their keys, by their works', or streamed. */
    val reads = CandidateReads(db)

    /**
     * The candidates of a search in place of those before, and when it was made, with how rare every value is: in one
     * transaction, the rows of the candidates appended — no index of them kept, none made after (in H2 the ten indexes
     * of 2.6 million rows took two minutes of every search, 2026-11-22) — a reading in between sees the last search
     * whole.
     */
    fun replace(
        candidates: Collection<DuplicateCandidate>,
        scores: (DuplicateCandidate) -> PairScore,
        search: DuplicateSearch,
        rarity: Map<String, Float>,
    ) {
        // Where the time goes, told in the log: the tables emptied, the candidates ordered and written, the rarities,
        // the commit
        val laps = Laps()
        store.transaction { connection, tx ->
            tx.deleteFrom(DUPLICATE_SEARCH).execute()
            tx.deleteFrom(DUPLICATE_CANDIDATE).execute()
            laps.lap("emptied")
            keep(connection, candidates, scores, laps)
            this.rarity.replace(connection, tx, rarity)
            laps.lap("rarities of ${rarity.size} values")
            fill(tx, search)
        }
        laps.lap("committed")
        log.info("the {} candidates of a search written: {}", candidates.size, laps)
    }

    /** The [search] written by [tx], its candidates written. */
    private fun fill(tx: DSLContext, search: DuplicateSearch) {
        tx.insertInto(DUPLICATE_SEARCH)
            .set(DUPLICATE_SEARCH.ID, 1)
            .set(DUPLICATE_SEARCH.MADE_AT, search.madeAt)
            .set(DUPLICATE_SEARCH.ENCODER, search.encoder)
            .set(DUPLICATE_SEARCH.WORKS, search.works)
            .set(DUPLICATE_SEARCH.MODEL, search.model)
            .set(DUPLICATE_SEARCH.SAME_MODEL, search.same)
            .set(DUPLICATE_SEARCH.SERIES_MODEL, search.series)
            .set(DUPLICATE_SEARCH.FITTED_AT, search.fittedAt)
            .set(DUPLICATE_SEARCH.FIT_MILLIS, search.fitMillis)
            .execute()
    }

    /**
     * The [candidates] appended by the [connection], in the order of their key: the blocks of rows of a work's pairs
     * together, what a pair is found by without an index.
     */
    private fun keep(
        connection: DuckDBConnection,
        candidates: Collection<DuplicateCandidate>,
        scores: (DuplicateCandidate) -> PairScore,
        laps: Laps,
    ) {
        val ordered =
            candidates.sortedWith(
                compareBy<DuplicateCandidate>(
                    { it.first.source },
                    { it.first.id },
                    { it.second.source },
                    { it.second.id },
                )
            )
        laps.lap("ordered")
        connection.createAppender(DuckDBConnection.DEFAULT_SCHEMA, DUPLICATE_CANDIDATE.name.lowercase()).use { a ->
            ordered.forEach { d ->
                val score = scores(d)
                a.beginRow()
                a.append(d.first.source)
                a.append(d.first.id)
                a.append(d.second.source)
                a.append(d.second.id)
                a.append(d.title)
                a.add(d.description)
                a.add(d.facets.takeIf { it.isNotEmpty() }?.let(::written))
                a.add(d.size)
                a.append(d.foundFor.toByte())
                a.append(d.sameAuthor)
                a.append(d.titleWithin)
                a.append(d.titleHolding)
                a.add(d.authors?.alike)
                a.add(d.authors?.within)
                a.add(d.authors?.holding)
                a.add(d.titleVector)
                a.add(d.letters[LetteredText.DESCRIPTION])
                a.add(d.letters[LetteredText.FANDOMS])
                a.add(d.letters[LetteredText.CHARACTERS])
                a.append(d.partlySameAuthor)
                a.add(d.words)
                a.add(d.otherWords)
                a.append(score.same)
                a.add(score.series)
                a.add(score.answer?.name)
                a.append(score.derived)
                a.append(score.sameAuthors)
                a.append(score.sameTitle)
                score.ranks.forEach { a.add(it) }
                a.endRow()
            }
        }
        laps.lap("written")
    }

    /**
     * The models of the user's answers fitted anew by the plugin of pairs [model], of the candidates as they are, at
     * [fittedAt] in [millis].
     */
    fun keepModels(model: String, same: ByteArray?, series: ByteArray?, fittedAt: Instant, millis: Long) {
        db.update(DUPLICATE_SEARCH)
            .set(DUPLICATE_SEARCH.MODEL, model)
            .set(DUPLICATE_SEARCH.SAME_MODEL, same)
            .set(DUPLICATE_SEARCH.SERIES_MODEL, series)
            .set(DUPLICATE_SEARCH.FITTED_AT, fittedAt)
            .set(DUPLICATE_SEARCH.FIT_MILLIS, millis)
            .where(DUPLICATE_SEARCH.ID.eq(1))
            .execute()
    }

    /** The candidate of the two works, the smaller key [first], as the last search kept it; null of none. */
    fun find(first: ItemKey, second: ItemKey): DuplicateCandidate? {
        val c = DUPLICATE_CANDIDATE
        return db.select(
                c.TITLE,
                c.DESCRIPTION,
                c.SIZE,
                c.FACETS,
                c.FOUND_FOR,
                c.SAME_AUTHOR,
                c.TITLE_WITHIN,
                c.TITLE_HOLDING,
                c.AUTHORS_ALIKE,
                c.AUTHORS_WITHIN,
                c.AUTHORS_HOLDING,
                c.TITLE_VECTOR,
                c.DESCRIPTION_LETTERS,
                c.FANDOM_LETTERS,
                c.CHARACTER_LETTERS,
                c.PARTLY_SAME_AUTHOR,
            )
            .from(c)
            .where(
                c.SOURCE.eq(first.source),
                c.ITEM_ID.eq(first.id),
                c.OTHER_SOURCE.eq(second.source),
                c.OTHER_ITEM_ID.eq(second.id),
            )
            .fetchOne { r ->
                DuplicateCandidate(
                    first,
                    second,
                    r.value1()!!,
                    r.value2(),
                    r.value3(),
                    r.value4()?.let { numbers(it) { s -> s } }.orEmpty(),
                    r.value5()!!.toInt(),
                    r.value6()!!,
                    r.value7() ?: 0f,
                    r.value8() ?: 0f,
                    lexical(r.value9(), r.value10(), r.value11()),
                    r.value12(),
                    letters(r.value13(), r.value14(), r.value15()),
                    r.value16()!!,
                )
            }
    }

    /** The last search; null when there was none. */
    fun search(): DuplicateSearch? =
        db.selectFrom(DUPLICATE_SEARCH).fetchOne()?.let {
            DuplicateSearch(
                it.madeAt!!,
                it.encoder!!,
                it.works!!,
                it.model,
                it.sameModel,
                it.seriesModel,
                it.fittedAt,
                it.fitMillis,
            )
        }

    /**
     * The user's word [answer] on the [pair], the smaller key first, kept with it at once — of none, taken back —: the
     * pair struck out of the open ones, or put back, till the next search tells every word anew.
     */
    fun said(pair: Pair<ItemKey, ItemKey>, answer: DuplicateAnswer?) {
        val c = DUPLICATE_CANDIDATE
        db.update(c)
            .set(c.ANSWER, answer?.name)
            .set(c.ANSWER_DERIVED, false)
            .where(
                c.SOURCE.eq(pair.first.source),
                c.ITEM_ID.eq(pair.first.id),
                c.OTHER_SOURCE.eq(pair.second.source),
                c.OTHER_ITEM_ID.eq(pair.second.id),
            )
            .execute()
    }

    private companion object {
        /** A number of none, or the number: what the appender takes a column of nulls by. */
        fun DuckDBAppender.add(v: Float?) = if (v == null) appendNull() else append(v)

        fun DuckDBAppender.add(v: Double?) = if (v == null) appendNull() else append(v)

        fun DuckDBAppender.add(v: String?) = if (v == null) appendNull() else append(v)
    }
}
