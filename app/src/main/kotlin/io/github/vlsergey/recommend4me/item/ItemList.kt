package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.ItemPage
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.interest.Interest
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.search.Search
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import java.time.Instant
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
 * The list of the works of a content type. H2 joins no two files, so the list is put together here for each request
 * from what each file says, read in one batch per file and only the columns the list needs: the items' keys and update
 * times from the source databases, the values of the filtered facets, the grades from the ratings databases, the
 * predictions from the model database. Nothing of it is kept after the request.
 */
@Component
class ItemList(
    private val stores: Stores,
    private val works: Works,
    private val cards: ItemCards,
    private val search: Search,
    private val recommendations: Recommendations,
    private val suggestions: FacetSuggestions,
) {

    /**
     * One row of the list before its card is read; [expected] — the prediction times the chance of interest — is what
     * the list is sorted by first, [number] what it is sorted by otherwise.
     */
    private class Row(
        val key: ItemKey,
        val updatedAt: Instant,
        val firstSeenAt: Instant,
        val graded: Boolean,
        val prediction: Double?,
        val expected: Double?,
        val interest: Double?,
        val number: Double?,
    )

    /**
     * What the rows of every source are read by: the [order] and its number [sortNumber], the [places] of the grades of
     * an order by the surprise, the [relevance] of the works a search found, the [choices] of the filter facets and the
     * [ranges] of the numbers.
     */
    private class RowsAsked(
        val order: ItemOrder,
        val sortNumber: String?,
        val places: Map<Int, Double>?,
        val relevance: Map<ItemKey, Int>?,
        val choices: Map<String, FacetChoice>,
        val ranges: List<NumberRange>,
    )

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
        val clusters = works.of(type)
        val places = if (order == ItemOrder.SURPRISE) recommendations.gradePlaces(typeId) else null
        val found = line?.takeIf { it.isNotBlank() }?.let { search.search(typeId, it, language) }
        val relevance = found?.keys?.withIndex()?.associate { (i, k) -> k to i }
        val asked = RowsAsked(order, sortNumber, places, relevance, facets.associateBy { it.facet }, ranges)

        val rows = type.sources.filter { sources == null || it.id in sources }.flatMap { s -> rowsOf(type, s, asked) }
        val viewed =
            oneAWork(type, clusters, rows, relevance).filter { row ->
                when (view) {
                    ItemView.UNRATED -> !row.graded
                    ItemView.RATED -> row.graded
                    ItemView.ALL -> true
                }
            }
        val sorted = sorted(viewed, order, relevance)
        val pageKeys = sorted.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, MAX_PAGE)).map { it.key }
        val matches = found?.let { search.matches(typeId, it, pageKeys) }.orEmpty()
        val judged = found?.let { type.searchJudgments.of(it.line) }.orEmpty()
        return ItemPage(
            total = sorted.size,
            items = cards.cards(type, pageKeys, matches, search.places(matches), clusters, judged),
        )
    }

    /** The rows of the source [s] shown by what is [asked], each with what the list is sorted by. */
    private fun rowsOf(type: TypeStore, s: SourceStore, asked: RowsAsked): List<Row> {
        val keys = s.items.keys()
        val versions = keys.associate { it.id to it.version }
        val graded = cards.graded(s.ratings.all(), versions)
        val grades = graded.mapNotNull { (id, g) -> g.grade?.let { id to it } }.toMap()
        val predicted = type.predictions.predicted(s.id)
        val marks = s.interest.all()
        val numbers =
            if (asked.order == ItemOrder.NUMBER && asked.sortNumber != null) numberOf(s, asked.sortNumber).orEmpty()
            else emptyMap()
        val surprises =
            if (asked.order == ItemOrder.SURPRISE && asked.places != null) surprises(type, s, asked.places, grades)
            else emptyMap()
        val passes = facetFilter(s, asked.choices)
        // The ranges of the numbers the source has, with their values: a source without one is not limited by it
        val limits = asked.ranges.mapNotNull { r -> numberOf(s, r.numberId)?.let { r to it } }
        return keys.mapNotNull { k ->
            val key = ItemKey(s.id, k.id)
            if (!shows(key, asked.relevance, passes, limits)) return@mapNotNull null
            val p = predicted[k.id]
            // What the user said of the work decides its chance; the prediction alone without a model of interest
            val chance = Interest.chance(marks[k.id]?.interesting, p?.interest)
            // The score as the scale shows it — below its 0 it is 0: a chance multiplying a negative
            // score would put the likelier interesting work lower. Equal, the prediction decides
            val expected = p?.let { if (chance != null) it.score.coerceAtLeast(0.0) * chance else it.score }
            Row(
                key,
                k.updatedAt,
                k.firstSeenAt,
                k.id in grades,
                p?.score,
                expected,
                chance,
                numbers[k.id] ?: surprises[k.id],
            )
        }
    }

    /**
     * Whether the item [key] is shown: found by the search when searched ([relevance]), let by the filter facets
     * ([passes]) and within every range of the numbers ([limits]).
     */
    private fun shows(
        key: ItemKey,
        relevance: Map<ItemKey, Int>?,
        passes: ((String) -> Boolean)?,
        limits: List<Pair<NumberRange, Map<String, Double>>>,
    ): Boolean =
        when {
            relevance != null && key !in relevance -> false
            passes != null && !passes(key.id) -> false
            // A work without the number is not known to be within the range: not shown
            else -> limits.none { (r, values) -> values[key.id]?.let { it !in r } ?: true }
        }

    /**
     * How far the model that had not seen a graded work of the source [s] puts it from where the user's grade stands
     * ([places] of the grades): beyond the lowest grade's place for a work of it is no surprise, nor beyond the
     * highest's.
     */
    private fun surprises(
        type: TypeStore,
        s: SourceStore,
        places: Map<Int, Double>,
        grades: Map<String, Int>,
    ): Map<String, Double> {
        val low = places.values.min()
        val high = places.values.max()
        return type.heldOut
            .scores(s.id)
            .mapNotNull { (id, score) ->
                val place = places[grades[id]] ?: return@mapNotNull null
                id to
                    when (place) {
                        low -> (score - place).coerceAtLeast(0.0)
                        high -> (place - score).coerceAtLeast(0.0)
                        else -> abs(score - place)
                    }
            }
            .toMap()
    }

    /**
     * The [rows] with a work one row, whatever sites have it: of those found, the item a search found best, else the
     * one that says the most.
     */
    private fun oneAWork(
        type: TypeStore,
        clusters: WorkClusters,
        rows: List<Row>,
        relevance: Map<ItemKey, Int>?,
    ): List<Row> {
        val several = rows.filter { clusters.linked(it.key) }
        val fullness = works.fullness(type, several.map { it.key })
        val shown =
            several
                .groupBy { clusters.representative(it.key) }
                .values
                .mapTo(HashSet()) { same ->
                    (if (relevance != null) same.minBy { relevance.getValue(it.key) }
                        else same.maxBy { fullness[it.key] ?: 0 })
                        .key
                }
        return rows.filter { !clusters.linked(it.key) || it.key in shown }
    }

    /** The [rows] in the [order] asked; of a search, by the [relevance] alone. */
    private fun sorted(rows: List<Row>, order: ItemOrder, relevance: Map<ItemKey, Int>?): List<Row> =
        if (relevance != null) rows.sortedBy { relevance.getValue(it.key) } else rows.sortedWith(comparator(order))

    /** How the rows go in the [order]: the newest update first among the equal. */
    private fun comparator(order: ItemOrder): Comparator<Row> =
        when (order) {
            ItemOrder.SCORE ->
                compareByDescending<Row> { it.expected ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.prediction ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.updatedAt }
            // Interest first, the prediction among the equal; the prediction first, interest among the equal
            ItemOrder.INTEREST ->
                compareByDescending<Row> { it.interest ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.prediction ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.updatedAt }
            ItemOrder.GRADE ->
                compareByDescending<Row> { it.prediction ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.interest ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.updatedAt }
            ItemOrder.NUMBER,
            ItemOrder.SURPRISE ->
                compareByDescending<Row> { it.number ?: Double.NEGATIVE_INFINITY }.thenByDescending { it.updatedAt }
            ItemOrder.ADDED -> compareByDescending<Row> { it.firstSeenAt }.thenByDescending { it.updatedAt }
            ItemOrder.UPDATED -> compareByDescending { it.updatedAt }
        }

    /**
     * The values of the number [numberId] (shared, or "<source>.<key>") of the source's items, as the user corrected
     * them; null when the source has no such number.
     */
    private fun numberOf(s: SourceStore, numberId: String): Map<String, Double>? {
        val def = s.schema.numbers.firstOrNull { FeatureNames.numberId(s.source, it) == numberId } ?: return null
        val site = s.items.number(def.key)
        val overrides =
            s.corrections
                .allFields()
                .mapNotNull { (id, fields) ->
                    fields[Corrected.numberField(def.key)]?.toDoubleOrNull()?.let { id to it }
                }
                .toMap()
        return site + overrides
    }

    /**
     * Whether an item of the source shows by the filters: by every facet's [FacetChoice] the source has. Null when no
     * facet of the source is filtered.
     */
    private fun facetFilter(s: SourceStore, choices: Map<String, FacetChoice>): ((String) -> Boolean)? {
        val filtered =
            s.schema.facets.filter { it.filter }.mapNotNull { def -> choices[filterId(s, def)]?.let { def to it } }
        if (filtered.isEmpty()) return null
        val corrections = s.corrections.allFacets().groupBy { it.itemId }
        val values = filtered.map { (def, choice) -> valuesOf(s, def, corrections) to choice }
        return { id -> values.all { (byItem, choice) -> choice.passes(byItem[id].orEmpty()) } }
    }

    /**
     * Every item's values of one facet as the user sees them: the site's, the model's of a facet it works out, the
     * user's corrections on top. The model's made now of every work of the source, at every asking (user, 2026-10-15:
     * slow for now, made faster later), none kept.
     */
    fun valuesOf(
        s: SourceStore,
        def: FacetDef,
        corrections: Map<String, List<FacetCorrection>>,
    ): Map<String, List<String>> {
        val byItem = HashMap<String, List<String>>()
        s.items.forEachFacet(def.key) { id, keys -> byItem[id] = keys }
        if (def.infer)
            suggestions.chances(s, null, through = def.key).forEach { (id, chances) ->
                byItem[id] =
                    ModelValues.of(s.schema, mapOf(def.key to byItem[id].orEmpty()), chances)[def.key].orEmpty()
            }
        corrections.forEach { (id, list) ->
            val own = list.filter { it.facet == def.key }
            if (own.isNotEmpty())
                byItem[id] =
                    Corrected.facets(
                            mapOf(def.key to byItem[id].orEmpty()),
                            own,
                            if (def.single) setOf(def.key) else emptySet(),
                        )[def.key]
                        .orEmpty()
        }
        return byItem
    }

    private companion object {
        /** The most works a page of the list holds. */
        const val MAX_PAGE = 500
    }
}
