package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.contenttype.StandardFacet
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.api.model.FacetFilter
import io.github.vlsergey.recommend4me.api.model.FilterValue
import io.github.vlsergey.recommend4me.api.model.FilterValues
import io.github.vlsergey.recommend4me.api.model.ItemPage
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.interest.Interest
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.search.Search
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.abs

enum class ItemView { UNRATED, RATED, ALL }
/** The orders of the list; ADDED — the newest in the application first, by when it first saw the work. */
/**
 * The orders of the list: by the formula — the prediction times the chance of interest — by
 * interest first, by the prediction of the grade first; by the dates, a number, the surprise.
 */
enum class ItemOrder { SCORE, INTEREST, GRADE, UPDATED, ADDED, NUMBER, SURPRISE }

/**
 * What a filter facet shows: a work with a value [shown] — when given, those alone, every other
 * value hidden, one a site adds later too — or else a value not [hidden], every other shown, one
 * added later too; a work without any value of the facet unless [withoutHidden]. Of a facet whose
 * values are a scale, those of the [range] alone — from one value of it to another — when given.
 */
class FacetChoice(
    val facet: String, val hidden: Set<String>, val shown: Set<String>?, val withoutHidden: Boolean, val range: Set<String>? = null,
) {
    fun passes(keys: Collection<String>): Boolean = when {
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
            val (from, to) = range.split("..", limit = 2).takeIf { it.size == 2 } ?: return null
            // An end left out is open; one that is no number makes the whole no range
            val low = from.takeIf { it.isNotBlank() }?.let { it.trim().toDoubleOrNull() ?: return null }
            val high = to.takeIf { it.isNotBlank() }?.let { it.trim().toDoubleOrNull() ?: return null }
            return NumberRange(id, low, high)
        }
    }
}

/**
 * The list of the works of a content type. H2 joins no two files, so the list is put together here
 * for each request from what each file says, read in one batch per file and only the columns the
 * list needs: the items' keys and update times from the source databases, the values of the
 * filtered facets, the grades from the ratings databases, the predictions from the model database.
 * Nothing of it is kept after the request.
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

    /** A facet as the filters of a content type know it: the shared key of its sources' facets, or "<source>.<key>" — as the model does. */
    private fun filterId(store: SourceStore, facet: io.github.vlsergey.recommend4me.source.FacetDef) = FeatureNames.facetId(store.source, facet)

    /**
     * One row of the list before its card is read; [expected] — the prediction times the chance of
     * interest — is what the list is sorted by first, [number] what it is sorted by otherwise.
     */
    private class Row(
        val key: ItemKey, val updatedAt: Instant, val firstSeenAt: Instant, val graded: Boolean,
        val prediction: Double?, val expected: Double?, val interest: Double?, val number: Double?,
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
        val choices = facets.associateBy { it.facet }

        val rows = ArrayList<Row>()
        type.sources.filter { sources == null || it.id in sources }.forEach { s ->
            val keys = s.items.keys()
            val versions = keys.associate { it.id to it.version }
            val graded = cards.graded(s.ratings.all(), versions)
            val grades = graded.mapNotNull { (id, g) -> g.grade?.let { id to it } }.toMap()
            val predicted = type.models.predicted(s.id)
            val marks = s.interest.all()
            val numbers = if (order == ItemOrder.NUMBER && sortNumber != null) numberOf(s, sortNumber).orEmpty() else emptyMap()
            // How far the model that had not seen a graded work puts it from where the user's grade
            // stands: beyond the lowest grade's place for a work of it is no surprise, nor beyond the highest's
            val surprises = if (order == ItemOrder.SURPRISE && places != null) {
                val low = places.values.min()
                val high = places.values.max()
                type.models.heldOut(s.id).mapNotNull { (id, score) ->
                    val place = places[grades[id]] ?: return@mapNotNull null
                    id to when (place) {
                        low -> (score - place).coerceAtLeast(0.0)
                        high -> (place - score).coerceAtLeast(0.0)
                        else -> abs(score - place)
                    }
                }.toMap()
            } else emptyMap()
            val passes = facetFilter(s, choices)
            // The ranges of the numbers the source has, with their values: a source without one is not limited by it
            val limits = ranges.mapNotNull { r -> numberOf(s, r.numberId)?.let { r to it } }
            keys.forEach { k ->
                val key = ItemKey(s.id, k.id)
                if (relevance != null && key !in relevance) return@forEach
                if (passes != null && !passes(k.id)) return@forEach
                // A work without the number is not known to be within the range: not shown
                if (limits.any { (r, values) -> values[k.id]?.let { it !in r } ?: true }) return@forEach
                val p = predicted[k.id]
                // What the user said of the work decides its chance; the prediction alone without a model of interest
                val chance = Interest.chance(marks[k.id]?.interesting, p?.interest)
                // The score as the scale shows it — below its 0 it is 0: a chance multiplying a negative
                // score would put the likelier interesting work lower. Equal, the prediction decides
                val expected = p?.let { if (chance != null) it.score.coerceAtLeast(0.0) * chance else it.score }
                rows += Row(key, k.updatedAt, k.firstSeenAt, k.id in grades, p?.score, expected, chance, numbers[k.id] ?: surprises[k.id])
            }
        }
        val viewed = rows.filter { row ->
            when (view) {
                ItemView.UNRATED -> !row.graded
                ItemView.RATED -> row.graded
                ItemView.ALL -> true
            }
        }
        val sorted = when {
            relevance != null -> viewed.sortedBy { relevance.getValue(it.key) }
            order == ItemOrder.SCORE -> viewed.sortedWith(
                compareByDescending<Row> { it.expected ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.prediction ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.updatedAt },
            )
            // Interest first, the prediction among the equal; the prediction first, interest among the equal
            order == ItemOrder.INTEREST -> viewed.sortedWith(
                compareByDescending<Row> { it.interest ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.prediction ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.updatedAt },
            )
            order == ItemOrder.GRADE -> viewed.sortedWith(
                compareByDescending<Row> { it.prediction ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.interest ?: Double.NEGATIVE_INFINITY }
                    .thenByDescending { it.updatedAt },
            )
            order == ItemOrder.NUMBER || order == ItemOrder.SURPRISE ->
                viewed.sortedWith(compareByDescending<Row> { it.number ?: Double.NEGATIVE_INFINITY }.thenByDescending { it.updatedAt })
            order == ItemOrder.ADDED -> viewed.sortedWith(compareByDescending<Row> { it.firstSeenAt }.thenByDescending { it.updatedAt })
            else -> viewed.sortedByDescending { it.updatedAt }
        }
        val pageKeys = sorted.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 500)).map { it.key }
        val matches = found?.let { search.matches(typeId, it, pageKeys) }.orEmpty()
        val judged = found?.let { type.searchJudgments.of(it.line) }.orEmpty()
        return ItemPage(total = sorted.size, items = cards.cards(type, pageKeys, matches, search.places(matches), clusters, judged))
    }

    /** The values of the number [numberId] (shared, or "<source>.<key>") of the source's items, as the user corrected them; null when the source has no such number. */
    private fun numberOf(s: SourceStore, numberId: String): Map<String, Double>? {
        val def = s.schema.numbers.firstOrNull { FeatureNames.numberId(s.source, it) == numberId } ?: return null
        val site = s.items.number(def.key)
        val overrides = s.corrections.allFields().mapNotNull { (id, fields) ->
            fields[Corrected.numberField(def.key)]?.toDoubleOrNull()?.let { id to it }
        }.toMap()
        return site + overrides
    }

    /**
     * Whether an item of the source shows by the filters: by every facet's [FacetChoice] the source
     * has. Null when no facet of the source is filtered.
     */
    private fun facetFilter(s: SourceStore, choices: Map<String, FacetChoice>): ((String) -> Boolean)? {
        val filtered = s.schema.facets.filter { it.filter }.mapNotNull { def -> choices[filterId(s, def)]?.let { def to it } }
        if (filtered.isEmpty()) return null
        val corrections = s.corrections.allFacets().groupBy { it.itemId }
        val values = filtered.map { (def, choice) -> valuesOf(s, def, corrections) to choice }
        return { id -> values.all { (byItem, choice) -> choice.passes(byItem[id].orEmpty()) } }
    }

    /**
     * Every item's values of one facet as the user sees them: the site's, the model's of a facet it
     * works out, the user's corrections on top. The model's made now of every work of the source, at
     * every asking (user, 2026-10-15: slow for now, made faster later), none kept.
     */
    fun valuesOf(s: SourceStore, def: FacetDef, corrections: Map<String, List<FacetCorrection>>): Map<String, List<String>> {
        val byItem = HashMap<String, List<String>>()
        s.items.forEachFacet(def.key) { id, keys -> byItem[id] = keys }
        if (def.infer) suggestions.chances(s, null, through = def.key).forEach { (id, chances) ->
            byItem[id] = ModelValues.of(s.schema, mapOf(def.key to byItem[id].orEmpty()), chances)[def.key].orEmpty()
        }
        corrections.forEach { (id, list) ->
            val own = list.filter { it.facet == def.key }
            if (own.isNotEmpty()) byItem[id] = Corrected.facets(mapOf(def.key to byItem[id].orEmpty()), own, if (def.single) setOf(def.key) else emptySet())[def.key].orEmpty()
        }
        return byItem
    }

    /**
     * The filter facets of a content type, without their values: no work read, no model run — shown
     * at once. The values of each are read apart, when it is opened or chosen by ([filterValues]).
     */
    fun facets(typeId: String): List<FacetFilter> {
        val type = stores.type(typeId) ?: return emptyList()
        val filters = LinkedHashMap<String, FacetFilter>()
        type.sources.forEach { s ->
            s.schema.facets.filter { it.filter }.forEach { def ->
                filters.getOrPut(filterId(s, def)) {
                    FacetFilter(
                        id = filterId(s, def),
                        labelKey = I18nKeys.facetLabel(s.id, def),
                        noneLabelKey = if (def.none) I18nKeys.facetNoneLabel(s.id, def) else null,
                        ordered = (def.standard?.ordered == true).takeIf { it },
                    )
                }
            }
        }
        return filters.values.toList()
    }

    /**
     * THE VALUES OF ONE FILTER FACET with how many works have each — every source of the type that
     * has it, summed. Of a facet the site gives, its works' own values with the user's answers,
     * counted. Of a facet a model works out (the universe, the languages), NO WORK READ AND NO MODEL
     * RUN: the values of the model kept with how many works it expects of each — its share of the
     * works it was fitted on ([FacetSuggestions.weights]) — and those the site or the user give
     * that it does not know, counted; the works without any value not told. (Run over every work at
     * every opening of the filters, the chain of the models took 150-195 s of the 75 621 works of
     * fanfics.me.) Null of no such type or filter.
     */
    fun filterValues(typeId: String, facetId: String): FilterValues? {
        val type = stores.type(typeId) ?: return null
        val counted = HashMap<String, Int>()
        val expected = HashMap<String, Double>()
        val names = HashMap<String, String>()
        val nameKeys = HashMap<String, String>()
        var scale: StandardFacet? = null
        var with = 0
        var total = 0
        var withKnown = true
        var found = false
        type.sources.forEach { s ->
            val def = s.schema.facets.firstOrNull { it.filter && filterId(s, it) == facetId } ?: return@forEach
            found = true
            def.standard?.takeIf { it.ordered }?.let { scale = it }
            val corrections = s.corrections.allFacets().filter { it.facet == def.key }
            val weights = if (def.infer) suggestions.weights(s, def.key) else null
            if (!def.infer) {
                total += s.items.count()
                valuesOf(s, def, corrections.groupBy { it.itemId }).values.forEach { keys ->
                    if (keys.isNotEmpty()) with++
                    keys.forEach { counted.merge(it, 1, Int::plus) }
                }
            } else {
                withKnown = false
                // The model's share of a value over the works it was fitted on, as so many of this source's works
                val works = s.items.count()
                weights?.forEach { (key, share) -> expected.merge(key, share * works, Double::plus) }
                // What the site or the user gives that the model does not know: counted as given
                s.items.valueUses(def.key).forEach { (key, n) -> if (weights?.containsKey(key) != true) counted.merge(key, n, Int::plus) }
                corrections.filter { it.added && weights?.containsKey(it.key) != true }.forEach { counted.merge(it.key, 1, Int::plus) }
            }
            val keys = counted.keys + expected.keys
            names += s.names(def.key, keys)
            keys.forEach { key -> s.nameKey(def.key, key)?.let { nameKeys.putIfAbsent(key, it) } }
            corrections.filter { it.name != null }.forEach { names.putIfAbsent(it.key, it.name!!) }
        }
        if (!found) return null
        fun value(key: String): FilterValue {
            val estimate = expected[key]
            val count = counted[key]
            return FilterValue(
                key = key, name = names[key] ?: key,
                nameKey = nameKeys[key] ?: scale?.let { I18nKeys.standardValueOf(it, key) },
                items = estimate?.let { Math.round(it + (count ?: 0)).toInt() } ?: count,
                estimated = (estimate != null).takeIf { it },
            )
        }
        // Of a scale every value in its order, one no work has with nought; of any other the most works first
        val values = scale?.values?.map { value(it.key) }
            ?: (counted.keys + expected.keys).map(::value).sortedByDescending { it.items ?: 0 }
        return FilterValues(propertyValues = values, itemsWithout = if (withKnown) total - with else null)
    }

    /** The values of the scale from [from] to [to] of the filter facet [facet] of the type; null of a facet that is no scale. */
    fun rangeOf(typeId: String, facet: String, from: String?, to: String?): Set<String>? {
        val type = stores.type(typeId) ?: return null
        val scale = type.sources.firstNotNullOfOrNull { s -> s.schema.facets.firstOrNull { it.filter && filterId(s, it) == facet }?.standard?.takeIf { it.ordered } } ?: return null
        return scale.between(from, to).toSet()
    }
}
