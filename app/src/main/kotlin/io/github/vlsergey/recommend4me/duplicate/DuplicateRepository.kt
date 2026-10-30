package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_CANDIDATE
import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_SEARCH
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import java.time.Instant

/**
 * Two works of different sites that may be one — [first] the smaller key — with how alike each layer
 * of theirs is (none of a layer either has not): the cosines of the vectors of their titles and of
 * their descriptions, the mean of the smaller over the larger of the numbers of their size ([size]),
 * and of every facet the sites share both have the cosine of their values weighed by rarity
 * ([facets], by the facet). How likely they are one work is the model's of the user's answers
 * ([DuplicateModel]), made when read. [foundFor]: the works it was found for — whose likeliest of
 * the other site it is — [FIRST], [SECOND], both, or neither (an answered pair the search did not keep).
 */
class DuplicateCandidate(
    val first: ItemKey, val second: ItemKey, val title: Float, val description: Float?, val size: Float?, val facets: Map<String, Float>,
    var foundFor: Int = 0,
) {
    val pair: Pair<ItemKey, ItemKey> get() = first to second

    companion object {
        /** Of [foundFor], the bit of the first work, and of the second. */
        const val FIRST = 1
        const val SECOND = 2
    }
}

/** When the candidates were last looked for, by which encoder of texts, of how many works; the weights of the model of the user's answers by feature, none before any. */
class DuplicateSearch(val madeAt: Instant, val encoder: String, val works: Int, val weights: Map<String, Double>?)

/** The candidates of the last search of the content type, in its model database: made anew whole by every search. */
class DuplicateRepository(private val db: DSLContext) {

    /** The candidates of a search in place of those before, and when it was made: one transaction. */
    fun replace(candidates: Collection<DuplicateCandidate>, search: DuplicateSearch) = db.transaction { c ->
        val tx = c.dsl()
        tx.deleteFrom(DUPLICATE_CANDIDATE).execute()
        candidates.chunked(BATCH).forEach { chunk ->
            tx.batch(chunk.map { d ->
                tx.insertInto(DUPLICATE_CANDIDATE)
                    .set(DUPLICATE_CANDIDATE.SOURCE, d.first.source).set(DUPLICATE_CANDIDATE.ITEM_ID, d.first.id)
                    .set(DUPLICATE_CANDIDATE.OTHER_SOURCE, d.second.source).set(DUPLICATE_CANDIDATE.OTHER_ITEM_ID, d.second.id)
                    .set(DUPLICATE_CANDIDATE.TITLE, d.title).set(DUPLICATE_CANDIDATE.DESCRIPTION, d.description)
                    .set(DUPLICATE_CANDIDATE.SIZE, d.size).set(DUPLICATE_CANDIDATE.FACETS, d.facets.takeIf { it.isNotEmpty() }?.let(::written))
                    .set(DUPLICATE_CANDIDATE.FOUND_FOR, d.foundFor.toByte())
            }).execute()
        }
        tx.deleteFrom(DUPLICATE_SEARCH).execute()
        tx.insertInto(DUPLICATE_SEARCH)
            .set(DUPLICATE_SEARCH.ID, 1).set(DUPLICATE_SEARCH.MADE_AT, search.madeAt)
            .set(DUPLICATE_SEARCH.ENCODER, search.encoder).set(DUPLICATE_SEARCH.WORKS, search.works)
            .set(DUPLICATE_SEARCH.WEIGHTS, search.weights?.let(::written))
            .execute()
    }

    /** The weights of the model of the user's answers fitted anew, of the candidates as they are. */
    fun keepWeights(weights: Map<String, Double>?) {
        db.update(DUPLICATE_SEARCH).set(DUPLICATE_SEARCH.WEIGHTS, weights?.let(::written)).where(DUPLICATE_SEARCH.ID.eq(1)).execute()
    }

    /** The last search; null when there was none. */
    fun search(): DuplicateSearch? = db.selectFrom(DUPLICATE_SEARCH).fetchOne()?.let {
        DuplicateSearch(it.madeAt!!, it.encoder!!, it.works!!, it.weights?.let(::read)?.mapValues { (_, v) -> v.toDouble() })
    }

    /** Every candidate: of some hundred thousand, read for one request and let go. */
    fun all(): List<DuplicateCandidate> =
        db.select(
            DUPLICATE_CANDIDATE.SOURCE, DUPLICATE_CANDIDATE.ITEM_ID, DUPLICATE_CANDIDATE.OTHER_SOURCE, DUPLICATE_CANDIDATE.OTHER_ITEM_ID,
            DUPLICATE_CANDIDATE.TITLE, DUPLICATE_CANDIDATE.DESCRIPTION, DUPLICATE_CANDIDATE.SIZE, DUPLICATE_CANDIDATE.FACETS, DUPLICATE_CANDIDATE.FOUND_FOR,
        ).from(DUPLICATE_CANDIDATE).fetchSize(FETCH).fetch { r ->
            DuplicateCandidate(
                ItemKey(r.value1()!!, r.value2()!!), ItemKey(r.value3()!!, r.value4()!!), r.value5()!!, r.value6(), r.value7(),
                r.value8()?.let(::read)?.mapValues { (_, v) -> v.toFloat() }.orEmpty(),
                r.value9()!!.toInt(),
            )
        }

    /** Numbers by name as kept: "<name>=<number>" a line. */
    private fun written(numbers: Map<String, Number>): String = numbers.entries.joinToString("\n") { (name, v) -> "$name=$v" }

    private fun read(text: String): Map<String, String> =
        text.lines().filter { '=' in it }.associate { it.substringBeforeLast('=') to it.substringAfterLast('=') }

    companion object {
        private const val BATCH = 1000
        private const val FETCH = 5000
    }
}
