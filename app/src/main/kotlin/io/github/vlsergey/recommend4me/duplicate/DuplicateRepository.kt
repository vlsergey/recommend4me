package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_CANDIDATE
import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_SEARCH
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.laps.Laps
import java.time.Instant
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DuplicateRepository::class.java)

/** The candidates of the last search of the content type, in its model database: made anew whole by every search. */
class DuplicateRepository(private val db: DSLContext) {

    /** How rare every value compared is, as the last search weighed it: what a pair it did not keep is compared by. */
    val rarity = ValueRarities(db)

    /** The pairs of a page chosen by its filters and its sort. */
    val choice = DuplicateChoice(db)

    /** The candidates read: by their keys, by their works', or streamed. */
    val reads = CandidateReads(db)

    /**
     * The candidates of a search in place of those before, and when it was made. The last search's candidates are let
     * go by a truncation, which the database does at once and commits by itself: deleting half a million rows in the
     * transaction of the new ones took 12 s, and its commit 10 s more (H2 2.4, measured 2026-11-08); a truncation takes
     * 20 ms. The search's row goes first and comes back with the new candidates, in one transaction. A search broken in
     * between leaves no row, and the start looks again. A reading in that second sees no search, as of a type never
     * searched.
     */
    fun replace(
        candidates: Collection<DuplicateCandidate>,
        scores: (DuplicateCandidate) -> PairScore,
        search: DuplicateSearch,
        rarity: Map<String, Float>,
    ) {
        // Where the time goes, told in the log: the tables emptied, the rarities, the candidates ordered and written,
        // the commit
        val laps = Laps()
        db.deleteFrom(DUPLICATE_SEARCH).execute()
        db.truncate(DUPLICATE_CANDIDATE).execute()
        laps.lap("emptied")
        db.transaction { c ->
            this.rarity.replace(c.dsl(), rarity)
            laps.lap("rarities of ${rarity.size} values")
            fill(c.dsl(), candidates, scores, search, laps)
        }
        laps.lap("committed")
        log.info("the {} candidates of a search written: {}", candidates.size, laps)
    }

    /** The [candidates] with their [scores] and the [search] written by [tx], into tables emptied. */
    private fun fill(
        tx: DSLContext,
        candidates: Collection<DuplicateCandidate>,
        scores: (DuplicateCandidate) -> PairScore,
        search: DuplicateSearch,
        laps: Laps,
    ) {
        keep(tx, candidates, scores, laps)
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
     * The [candidates] written by [tx], one statement prepared and bound a row each: half a million statements of their
     * own, each parsed by the database apart, were most of a search's half a minute of keeping.
     */
    private fun keep(
        tx: DSLContext,
        candidates: Collection<DuplicateCandidate>,
        scores: (DuplicateCandidate) -> PairScore,
        laps: Laps,
    ) {
        // In the order of the table's key: every row written after the last, not into the middle of its pages — half a
        // million rows in the order found took 20 s of a search, most of it the database finding their places
        // (2026-11-18)
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
        ordered.chunked(BATCH).forEach { chunk ->
            val batch =
                tx.batch(tx.insertInto(DUPLICATE_CANDIDATE).columns(COLUMNS).values(List(COLUMNS.size) { null }))
            chunk.forEach { d ->
                val score = scores(d)
                batch.bind(
                    d.first.source,
                    d.first.id,
                    d.second.source,
                    d.second.id,
                    d.title,
                    d.description,
                    d.size,
                    d.facets.takeIf { it.isNotEmpty() }?.let(::written),
                    d.foundFor.toByte(),
                    d.sameAuthor,
                    d.titleWithin,
                    d.titleHolding,
                    d.authors?.alike,
                    d.authors?.within,
                    d.authors?.holding,
                    d.titleVector,
                    d.letters[LetteredText.DESCRIPTION],
                    d.letters[LetteredText.FANDOMS],
                    d.letters[LetteredText.CHARACTERS],
                    d.partlySameAuthor,
                    d.words,
                    d.otherWords,
                    score.same,
                    score.series,
                    score.answer?.name,
                    score.derived,
                    score.sameAuthors,
                    score.sameTitle,
                )
            }
            batch.execute()
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

    /**
     * Every pair scored anew — its chance of one work and of one series by the [chances] of it —, streamed and written
     * by the table's key a [BATCH] at a time; how many.
     */
    fun rescore(chances: (DuplicateCandidate) -> Pair<Double, Double?>): Int {
        var count = 0
        val chunk = ArrayList<DuplicateCandidate>(BATCH)
        reads.forEach { d ->
            chunk += d
            if (chunk.size == BATCH) {
                scored(chunk, chances)
                count += chunk.size
                chunk.clear()
            }
        }
        if (chunk.isNotEmpty()) scored(chunk, chances)
        return count + chunk.size
    }

    /** The chances of the [pairs] by [chances] written, one statement bound a pair each. */
    private fun scored(pairs: List<DuplicateCandidate>, chances: (DuplicateCandidate) -> Pair<Double, Double?>) {
        val c = DUPLICATE_CANDIDATE
        val batch =
            db.batch(
                db.update(c)
                    .set(c.SAME_CHANCE, null as Double?)
                    .set(c.SERIES_CHANCE, null as Double?)
                    .where(c.SOURCE.eq(""), c.ITEM_ID.eq(""), c.OTHER_SOURCE.eq(""), c.OTHER_ITEM_ID.eq(""))
            )
        pairs.forEach { d ->
            val (same, series) = chances(d)
            batch.bind(same, series, d.first.source, d.first.id, d.second.source, d.second.id)
        }
        batch.execute()
    }

    companion object {
        private const val BATCH = 1000

        private const val FETCH = 5000

        /** The columns of a candidate written, in the order a row of them is bound. */
        private val COLUMNS =
            listOf(
                DUPLICATE_CANDIDATE.SOURCE,
                DUPLICATE_CANDIDATE.ITEM_ID,
                DUPLICATE_CANDIDATE.OTHER_SOURCE,
                DUPLICATE_CANDIDATE.OTHER_ITEM_ID,
                DUPLICATE_CANDIDATE.TITLE,
                DUPLICATE_CANDIDATE.DESCRIPTION,
                DUPLICATE_CANDIDATE.SIZE,
                DUPLICATE_CANDIDATE.FACETS,
                DUPLICATE_CANDIDATE.FOUND_FOR,
                DUPLICATE_CANDIDATE.SAME_AUTHOR,
                DUPLICATE_CANDIDATE.TITLE_WITHIN,
                DUPLICATE_CANDIDATE.TITLE_HOLDING,
                DUPLICATE_CANDIDATE.AUTHORS_ALIKE,
                DUPLICATE_CANDIDATE.AUTHORS_WITHIN,
                DUPLICATE_CANDIDATE.AUTHORS_HOLDING,
                DUPLICATE_CANDIDATE.TITLE_VECTOR,
                DUPLICATE_CANDIDATE.DESCRIPTION_LETTERS,
                DUPLICATE_CANDIDATE.FANDOM_LETTERS,
                DUPLICATE_CANDIDATE.CHARACTER_LETTERS,
                DUPLICATE_CANDIDATE.PARTLY_SAME_AUTHOR,
                DUPLICATE_CANDIDATE.WORDS,
                DUPLICATE_CANDIDATE.OTHER_WORDS,
                DUPLICATE_CANDIDATE.SAME_CHANCE,
                DUPLICATE_CANDIDATE.SERIES_CHANCE,
                DUPLICATE_CANDIDATE.ANSWER,
                DUPLICATE_CANDIDATE.ANSWER_DERIVED,
                DUPLICATE_CANDIDATE.SAME_AUTHORS,
                DUPLICATE_CANDIDATE.SAME_TITLE,
            )
    }
}
