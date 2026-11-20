package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_CANDIDATE
import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_SEARCH
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.laps.Laps
import java.time.Instant
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DuplicateRepository::class.java)

/**
 * Two works of different sites that may be one — [first] the smaller key — with how alike each layer of theirs is (none
 * of a layer either has not): the cosines of the vectors of their titles and of their descriptions, the mean of the
 * smaller over the larger of the numbers of their size ([size]), and of every facet the sites share both have the
 * cosine of their values weighed by rarity ([facets], by the facet) — its [layers]. How likely they are one work, and
 * one series, is the models' of the user's answers ([PairChances]), made when read. [foundFor]: the works it was found
 * for — whose likeliest of the other site it is — [FIRST], [SECOND], both, or neither (an answered pair the search did
 * not keep). [sameAuthor]: the dictionary of authors gives the two one author; [partlySameAuthor]: none in common, but
 * one in part one with the other's ([io.github.vlsergey.recommend4me.author.AuthorKin]).
 */
class DuplicateCandidate(
    val first: ItemKey,
    val second: ItemKey,
    val title: Float,
    val description: Float?,
    val size: Float?,
    val facets: Map<String, Float>,
    var foundFor: Int = 0,
    val sameAuthor: Boolean = false,
    /** How much of the first's title the second's is, and of the second's the first's ([PairLayers.titleWithin]). */
    val titleWithin: Float = 0f,
    val titleHolding: Float = 0f,
    /** How alike the names of the two's authors are letter by letter; none when either has none. */
    val authors: Lexical? = null,
    /** The cosine of the vectors of their titles, and of the trigrams of their other texts ([PairLayers]). */
    val titleVector: Float? = null,
    val letters: Map<LetteredText, Float> = emptyMap(),
    val partlySameAuthor: Boolean = false,
    /**
     * The words of the first work and of the second, as its site states them or worked out of its characters, the
     * search compared them by; none of one not known: what the pairs are sorted by their size by.
     */
    val words: Float? = null,
    val otherWords: Float? = null,
) {
    val pair: Pair<ItemKey, ItemKey>
        get() = first to second

    /** How alike the two are, as a model of pairs reads it. */
    val layers: PairLayers
        get() =
            PairLayers(
                title,
                description,
                size,
                facets,
                sameAuthor,
                first.source,
                second.source,
                titleWithin,
                titleHolding,
                authors,
                titleVector,
                letters,
                partlySameAuthor,
            )

    companion object {
        /**
         * The pair of [first] and [second] of the [layers], found for the works of [foundFor], of the [words] of the
         * first and the [otherWords] of the second.
         */
        fun of(
            first: ItemKey,
            second: ItemKey,
            layers: PairLayers,
            foundFor: Int = 0,
            words: Float? = null,
            otherWords: Float? = null,
        ) =
            DuplicateCandidate(
                first,
                second,
                layers.title,
                layers.description,
                layers.size,
                layers.facets,
                foundFor,
                layers.sameAuthor,
                layers.titleWithin,
                layers.titleHolding,
                layers.authors,
                layers.titleVector,
                layers.letters,
                layers.partlySameAuthor,
                words,
                otherWords,
            )

        /** Of [foundFor], the bit of the first work, and of the second. */
        const val FIRST = 1
        const val SECOND = 2
    }
}

/**
 * When the candidates were last looked for, by which encoder of texts, of how many works; the models of the user's
 * answers as the plugin of pairs [model] packed them ([PairModel]): of one work ([same]) and of one series ([series]),
 * none of a kind no pair was said to be of.
 */
class DuplicateSearch(
    val madeAt: Instant,
    val encoder: String,
    val works: Int,
    val model: String?,
    val same: ByteArray?,
    val series: ByteArray?,
    /** When the models were last fitted, and how long it took; none of models fitted before it was kept. */
    val fittedAt: Instant? = null,
    val fitMillis: Long? = null,
)

/** The candidates of the last search of the content type, in its model database: made anew whole by every search. */
class DuplicateRepository(private val db: DSLContext) {

    /** How rare every value compared is, as the last search weighed it: what a pair it did not keep is compared by. */
    val rarity = ValueRarities(db)

    /**
     * The candidates of a search in place of those before, and when it was made. The last search's candidates are let
     * go by a truncation, which the database does at once and commits by itself: deleting half a million rows in the
     * transaction of the new ones took 12 s, and its commit 10 s more (H2 2.4, measured 2026-11-08); a truncation takes
     * 20 ms. The search's row goes first and comes back with the new candidates, in one transaction. A search broken in
     * between leaves no row, and the start looks again. A reading in that second sees no search, as of a type never
     * searched.
     */
    fun replace(candidates: Collection<DuplicateCandidate>, search: DuplicateSearch, rarity: Map<String, Float>) {
        // Where the time goes, told in the log: the tables emptied, the rarities, the candidates ordered and written,
        // the commit
        val laps = Laps()
        db.deleteFrom(DUPLICATE_SEARCH).execute()
        db.truncate(DUPLICATE_CANDIDATE).execute()
        laps.lap("emptied")
        db.transaction { c ->
            this.rarity.replace(c.dsl(), rarity)
            laps.lap("rarities of ${rarity.size} values")
            fill(c.dsl(), candidates, search, laps)
        }
        laps.lap("committed")
        log.info("the {} candidates of a search written: {}", candidates.size, laps)
    }

    /** The [candidates] and the [search] written by [tx], into tables emptied. */
    private fun fill(tx: DSLContext, candidates: Collection<DuplicateCandidate>, search: DuplicateSearch, laps: Laps) {
        keep(tx, candidates, laps)
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
    private fun keep(tx: DSLContext, candidates: Collection<DuplicateCandidate>, laps: Laps) {
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
     * Every candidate: of some hundred thousand, read for one request and let go — streamed, not held twice as rows and
     * as candidates. The few names of the sources and of the facets' families one string each, not a copy a pair.
     */
    fun all(): List<DuplicateCandidate> = read(DSL.noCondition())

    /**
     * The candidates both works of which are among the [items]: of the works of two authors, say. Found by the table's
     * key, of each work the pairs it is the first of, the second looked up among them: no index of the second works
     * written with every search.
     */
    fun among(items: Collection<ItemKey>): List<DuplicateCandidate> {
        val c = DUPLICATE_CANDIDATE
        val all = items.toHashSet()
        return all.flatMap { w -> read(c.SOURCE.eq(w.source).and(c.ITEM_ID.eq(w.id))).filter { it.second in all } }
    }

    /** The candidates the [condition] picks, streamed. */
    private fun read(condition: Condition): List<DuplicateCandidate> {
        val out = ArrayList<DuplicateCandidate>()
        val names = HashMap<String, String>()
        val one = { s: String -> names.getOrPut(s) { s } }
        db.select(
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
            )
            .from(DUPLICATE_CANDIDATE)
            .where(condition)
            .fetchSize(FETCH)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach { r ->
                    out +=
                        DuplicateCandidate(
                            ItemKey(one(r.value1()!!), r.value2()!!),
                            ItemKey(one(r.value3()!!), r.value4()!!),
                            r.value5()!!,
                            r.value6(),
                            r.value7(),
                            r.value8()?.let { numbers(it, one) }.orEmpty(),
                            r.value9()!!.toInt(),
                            r.value10()!!,
                            r.value11() ?: 0f,
                            r.value12() ?: 0f,
                            lexical(r.value13(), r.value14(), r.value15()),
                            r.value16(),
                            letters(r.value17(), r.value18(), r.value19()),
                            r.value20()!!,
                            r.value21(),
                            r.value22(),
                        )
                }
            }
        return out
    }

    /** Numbers by name as kept: "<name>=<number>" a line. */
    private fun written(numbers: Map<String, Number>): String =
        numbers.entries.joinToString("\n") { (name, v) -> "$name=$v" }

    /**
     * The numbers by name of a [text] [written]: a line's name before its last '=', a line of none passed over; each
     * name as [one] gives it.
     */
    private fun numbers(text: String, one: (String) -> String): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        var from = 0
        while (from <= text.length) {
            val end = text.indexOf('\n', from).takeIf { it >= 0 } ?: text.length
            val eq = text.lastIndexOf('=', end - 1)
            if (eq >= from) out[one(text.substring(from, eq))] = text.substring(eq + 1, end).toFloat()
            from = end + 1
        }
        return out
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
            )
    }
}

/** How alike a pair's texts are letter by letter as kept: of the description, the fandoms, the characters. */
private fun letters(description: Float?, fandoms: Float?, characters: Float?): Map<LetteredText, Float> =
    listOf(
            LetteredText.DESCRIPTION to description,
            LetteredText.FANDOMS to fandoms,
            LetteredText.CHARACTERS to characters,
        )
        .mapNotNull { (text, v) -> v?.let { text to it } }
        .toMap()

/** How alike the names of a pair's authors are as kept: none of a pair of no names compared. */
private fun lexical(alike: Float?, within: Float?, holding: Float?): Lexical? = alike?.let {
    Lexical(it, within ?: 0f, holding ?: 0f)
}
