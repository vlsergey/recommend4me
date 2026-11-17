package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.ItemPage
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.search.Search
import io.github.vlsergey.recommend4me.search.SearchSnippet
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.worklist.ListAsked
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.ListQuery
import kotlin.math.abs
import org.springframework.stereotype.Component

enum class ItemView {
    UNRATED,
    RATED,
    ALL,
}

/** The orders of the list; ADDED — the newest in the application first, by when it first saw the work. */
/**
 * The orders of the list: by the formula — the prediction times the chance of interest — by interest first, by the
 * prediction of the grade first; by the dates, a number, the surprise.
 */
enum class ItemOrder {
    SCORE,
    INTEREST,
    GRADE,
    UPDATED,
    ADDED,
    NUMBER,
    SURPRISE,
}

/**
 * What a filter facet shows: a work with a value [shown] — when given, those alone, every other value hidden, one a
 * site adds later too — or else a value not [hidden], every other shown, one added later too; a work without any value
 * of the facet unless [withoutHidden]. Of a facet whose values are a scale, those of the [range] alone — from one value
 * of it to another — when given.
 */
class FacetChoice(
    val facet: String,
    val hidden: Set<String>,
    val shown: Set<String>?,
    val withoutHidden: Boolean,
    val range: Set<String>? = null,
) {
    fun passes(keys: Collection<String>): Boolean =
        when {
            keys.isEmpty() -> !withoutHidden
            range != null -> keys.any { it in range }
            shown != null -> keys.any { it in shown }
            else -> keys.any { it !in hidden }
        }
}

/** The works whose number [numberId] lies within [from]..[to], both ends included; a null end is open. */
class NumberRange(val numberId: String, val from: Double?, val to: Double?) {
    operator fun contains(value: Double) = (from == null || value >= from) && (to == null || value <= to)

    companion object {
        /** "<number id>=<from>..<to>", an end left out open; null when it is not one. */
        fun parse(text: String): NumberRange? {
            val (id, range) = text.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() } ?: return null
            val ends = range.split("..", limit = 2)
            // An end left out is open; one that is no number makes the whole no range
            return if (ends.size == 2 && ends.all(::isEnd)) NumberRange(id, endOf(ends[0]), endOf(ends[1])) else null
        }

        /** Whether [text] is an end of a range: left out, or a number. */
        private fun isEnd(text: String) = text.isBlank() || text.trim().toDoubleOrNull() != null

        /** The end [text] of a range ([isEnd]): null when left out, open. */
        private fun endOf(text: String) = text.takeIf { it.isNotBlank() }?.trim()?.toDouble()
    }
}

/**
 * A facet as the filters of a content type know it: the shared key of its sources' facets, or "<source>.<key>" — as the
 * model does.
 */
internal fun filterId(store: SourceStore, facet: FacetDef) = FeatureNames.facetId(store.source, facet)

/**
 * The list of the works of a content type: what passes its filters, in its order, counted and cut into a page, asked of
 * its index ([ListIndex][io.github.vlsergey.recommend4me.worklist.ListIndex]) alone — no database read, no model run,
 * of a million works (user, 2026-11-17); the cards of the page alone read of the databases, by their keys.
 */
@Component
class ItemList(
    private val stores: Stores,
    private val cards: ItemCards,
    private val search: Search,
    private val recommendations: Recommendations,
    private val indexes: ListIndexes,
) {

    fun page(
        typeId: String,
        view: ItemView,
        order: ItemOrder,
        sortNumber: String?,
        line: String?,
        facets: List<FacetChoice>,
        sources: Set<String>?,
        ranges: List<NumberRange>,
        offset: Int,
        limit: Int,
        /** The language the [line] is read as (ISO 639-1). */
        language: String,
    ): ItemPage {
        val type = stores.type(typeId) ?: return ItemPage(0, emptyList())
        val found = line?.takeIf { it.isNotBlank() }?.let { search.search(typeId, it, language) }
        val relevance = found?.keys?.withIndex()?.associate { (i, k) -> k to i }
        val surprise =
            if (order != ItemOrder.SURPRISE) emptyMap()
            else recommendations.gradePlaces(typeId)?.let { surprises(type, it) }.orEmpty()
        val asked = ListAsked(view, order, sortNumber, relevance, facets, sources, ranges, surprise)
        val page = ListQuery.page(indexes.of(typeId), type, asked, offset, limit.coerceIn(1, MAX_PAGE))
        val matches = found?.let { search.matches(typeId, it, page.keys) }.orEmpty()
        // The user's word on the snippet every work is shown with: of another snippet of it, none
        val judged =
            found
                ?.let { type.searchJudgments.of(it.line) }
                ?.let { SearchSnippet.judgedShown(it, matches.mapValues { (_, m) -> SearchSnippet.of(m) }) }
                .orEmpty()
        return ItemPage(
            total = page.total,
            items = cards.cards(type, page.keys, matches, search.places(matches), page.clusters, judged),
        )
    }

    /**
     * Of every graded item of the type, how far the model that had not seen it puts it from where the user's grade
     * stands ([places] of the grades): beyond the lowest grade's place for a work of it is no surprise, nor beyond the
     * highest's. The graded items alone read.
     */
    private fun surprises(type: TypeStore, places: Map<Int, Double>): Map<ItemKey, Double> {
        val low = places.values.min()
        val high = places.values.max()
        return type.sources
            .flatMap { s ->
                val ratings = s.ratings.all()
                val versions = s.items.heads(ratings.map { it.itemId }).mapValues { it.value.version }
                val grades = cards.graded(ratings, versions).mapNotNull { (id, g) -> g.grade?.let { id to it } }.toMap()
                type.heldOut.scores(s.id).mapNotNull { (id, score) ->
                    val place = places[grades[id]] ?: return@mapNotNull null
                    ItemKey(s.id, id) to
                        when (place) {
                            low -> (score - place).coerceAtLeast(0.0)
                            high -> (place - score).coerceAtLeast(0.0)
                            else -> abs(score - place)
                        }
                }
            }
            .toMap()
    }

    private companion object {
        /** The most works a page of the list holds. */
        const val MAX_PAGE = 500
    }
}
