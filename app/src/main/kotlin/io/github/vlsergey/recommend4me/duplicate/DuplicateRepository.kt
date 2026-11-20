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
     * 20 ms. The search's row goes first and comes back once the new candidates are written and indexed. A search
     * broken in between leaves no row, and the start looks again. A reading in between sees no search, as of a type
     * never searched.
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
        // The indexes made after the rows, each of a pass over them, not kept in step with every row written: with
        // the index of every sort, the rows of 1.2 million pairs took 206 s and their one transaction's commit 402 s
        // (2026-11-20); the rows written a batch a statement, each committed by itself — none sees the table before the
        // search's row comes back
        INDEXES.keys.forEach { db.execute("DROP INDEX IF EXISTS $it") }
        laps.lap("emptied")
        keep(db, candidates, scores, laps)
        INDEXES.forEach { (name, columns) -> db.execute("CREATE INDEX $name ON duplicate_candidate ($columns)") }
        laps.lap("indexed")
        db.transaction { c ->
            this.rarity.replace(c.dsl(), rarity)
            laps.lap("rarities of ${rarity.size} values")
            fill(c.dsl(), search)
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
                    *score.ranks,
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

    companion object {
        private const val BATCH = 1000

        /**
         * The indexes of the candidates by their second works and by the key of every sort beside the word on the pair
         * ([DuplicateChoice]), by their columns: as the migrations make them (V51, V53, V54), made anew after the rows.
         */
        private val INDEXES =
            mapOf(
                "duplicate_candidate_other" to "other_source, other_item_id",
                "duplicate_candidate_by_chance" to "answer, same_chance DESC",
                "duplicate_candidate_by_series" to "answer, series_chance DESC",
                "duplicate_candidate_by_size" to "answer, by_size DESC",
                "duplicate_candidate_by_series_size" to "answer, by_series_size DESC",
                "duplicate_candidate_by_doubt" to "answer, by_doubt DESC",
                "duplicate_candidate_by_series_doubt" to "answer, by_series_doubt DESC",
                "duplicate_candidate_by_formula" to "answer, by_formula DESC",
                "duplicate_candidate_by_interest" to "answer, by_interest DESC",
                "duplicate_candidate_by_grade" to "answer, by_grade DESC",
            )

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
                DUPLICATE_CANDIDATE.BY_SIZE,
                DUPLICATE_CANDIDATE.BY_SERIES_SIZE,
                DUPLICATE_CANDIDATE.BY_DOUBT,
                DUPLICATE_CANDIDATE.BY_SERIES_DOUBT,
                DUPLICATE_CANDIDATE.BY_FORMULA,
                DUPLICATE_CANDIDATE.BY_INTEREST,
                DUPLICATE_CANDIDATE.BY_GRADE,
            )
    }
}
