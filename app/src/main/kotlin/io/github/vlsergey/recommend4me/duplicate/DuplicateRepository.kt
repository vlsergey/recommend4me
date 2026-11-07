package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_CANDIDATE
import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_SEARCH
import io.github.vlsergey.recommend4me.item.ItemKey
import java.time.Instant
import org.jooq.DSLContext

/**
 * Two works of different sites that may be one — [first] the smaller key — with how alike each layer of theirs is (none
 * of a layer either has not): the cosines of the vectors of their titles and of their descriptions, the mean of the
 * smaller over the larger of the numbers of their size ([size]), and of every facet the sites share both have the
 * cosine of their values weighed by rarity ([facets], by the facet) — its [layers]. How likely they are one work, and
 * one series, is the models' of the user's answers ([PairChances]), made when read. [foundFor]: the works it was found
 * for — whose likeliest of the other site it is — [FIRST], [SECOND], both, or neither (an answered pair the search did
 * not keep). [sameAuthor]: the dictionary of authors gives the two one author, fully or in part
 * ([io.github.vlsergey.recommend4me.author.AuthorKin]).
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
) {
    val pair: Pair<ItemKey, ItemKey>
        get() = first to second

    /** How alike the two are, as a model of pairs reads it. */
    val layers: PairLayers
        get() = PairLayers(title, description, size, facets, sameAuthor)

    companion object {
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
)

/** The candidates of the last search of the content type, in its model database: made anew whole by every search. */
class DuplicateRepository(private val db: DSLContext) {

    /** The candidates of a search in place of those before, and when it was made: one transaction. */
    fun replace(candidates: Collection<DuplicateCandidate>, search: DuplicateSearch) = db.transaction { c ->
        val tx = c.dsl()
        tx.deleteFrom(DUPLICATE_CANDIDATE).execute()
        candidates.chunked(BATCH).forEach { chunk ->
            tx.batch(
                    chunk.map { d ->
                        tx.insertInto(DUPLICATE_CANDIDATE)
                            .set(DUPLICATE_CANDIDATE.SOURCE, d.first.source)
                            .set(DUPLICATE_CANDIDATE.ITEM_ID, d.first.id)
                            .set(DUPLICATE_CANDIDATE.OTHER_SOURCE, d.second.source)
                            .set(DUPLICATE_CANDIDATE.OTHER_ITEM_ID, d.second.id)
                            .set(DUPLICATE_CANDIDATE.TITLE, d.title)
                            .set(DUPLICATE_CANDIDATE.DESCRIPTION, d.description)
                            .set(DUPLICATE_CANDIDATE.SIZE, d.size)
                            .set(DUPLICATE_CANDIDATE.FACETS, d.facets.takeIf { it.isNotEmpty() }?.let(::written))
                            .set(DUPLICATE_CANDIDATE.FOUND_FOR, d.foundFor.toByte())
                            .set(DUPLICATE_CANDIDATE.SAME_AUTHOR, d.sameAuthor)
                    }
                )
                .execute()
        }
        tx.deleteFrom(DUPLICATE_SEARCH).execute()
        tx.insertInto(DUPLICATE_SEARCH)
            .set(DUPLICATE_SEARCH.ID, 1)
            .set(DUPLICATE_SEARCH.MADE_AT, search.madeAt)
            .set(DUPLICATE_SEARCH.ENCODER, search.encoder)
            .set(DUPLICATE_SEARCH.WORKS, search.works)
            .set(DUPLICATE_SEARCH.MODEL, search.model)
            .set(DUPLICATE_SEARCH.SAME_MODEL, search.same)
            .set(DUPLICATE_SEARCH.SERIES_MODEL, search.series)
            .execute()
    }

    /** The models of the user's answers fitted anew by the plugin of pairs [model], of the candidates as they are. */
    fun keepModels(model: String, same: ByteArray?, series: ByteArray?) {
        db.update(DUPLICATE_SEARCH)
            .set(DUPLICATE_SEARCH.MODEL, model)
            .set(DUPLICATE_SEARCH.SAME_MODEL, same)
            .set(DUPLICATE_SEARCH.SERIES_MODEL, series)
            .where(DUPLICATE_SEARCH.ID.eq(1))
            .execute()
    }

    /** The last search; null when there was none. */
    fun search(): DuplicateSearch? =
        db.selectFrom(DUPLICATE_SEARCH).fetchOne()?.let {
            DuplicateSearch(it.madeAt!!, it.encoder!!, it.works!!, it.model, it.sameModel, it.seriesModel)
        }

    /** Every candidate: of some hundred thousand, read for one request and let go. */
    fun all(): List<DuplicateCandidate> =
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
            )
            .from(DUPLICATE_CANDIDATE)
            .fetchSize(FETCH)
            .fetch { r ->
                DuplicateCandidate(
                    ItemKey(r.value1()!!, r.value2()!!),
                    ItemKey(r.value3()!!, r.value4()!!),
                    r.value5()!!,
                    r.value6(),
                    r.value7(),
                    r.value8()?.let(::read)?.mapValues { (_, v) -> v.toFloat() }.orEmpty(),
                    r.value9()!!.toInt(),
                    r.value10()!!,
                )
            }

    /** Numbers by name as kept: "<name>=<number>" a line. */
    private fun written(numbers: Map<String, Number>): String =
        numbers.entries.joinToString("\n") { (name, v) -> "$name=$v" }

    private fun read(text: String): Map<String, String> =
        text.lines().filter { '=' in it }.associate { it.substringBeforeLast('=') to it.substringAfterLast('=') }

    companion object {
        private const val BATCH = 1000
        private const val FETCH = 5000
    }
}
