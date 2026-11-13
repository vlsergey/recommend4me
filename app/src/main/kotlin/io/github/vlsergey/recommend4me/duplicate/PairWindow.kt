package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.AuthorKin
import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateValue
import io.github.vlsergey.recommend4me.api.model.DuplicateWorkFacet
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.ItemFacet
import io.github.vlsergey.recommend4me.author.AuthorKinship
import io.github.vlsergey.recommend4me.item.ItemDetailsReader
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component

/**
 * THE WINDOW OF ONE PAIR OF WORKS, more than a row of the screen of duplicates tells: every facet of the layer of each
 * work — its tags, its universes, its characters — as its screen gives it, the model's given values among them, its
 * chances made now, in place of the values the search compared (the site's tags by the normalized ones, the universes
 * of the site's fandoms alone); and of two works of no author in common, how close their authors are by their works
 * ([AuthorKinship]) — worked out as it is shown, never kept.
 */
@Component
class PairWindow(
    private val details: ItemDetailsReader,
    private val works: Works,
    private val answers: PairAnswers,
    private val models: PairModels,
    private val views: DuplicateViews,
) {

    /** The [pair] as its window shows it. */
    internal fun of(type: TypeStore, pair: DuplicatePair): DuplicatePair {
        val first = ItemKey(pair.first.source, pair.first.item)
        val second = ItemKey(pair.second.source, pair.second.item)
        val described = views.describe(type, listOf(first, second))
        val clusters = works.of(type)
        val kin =
            if (pair.sameAuthor) null
            else
                AuthorKinship.of(
                    type,
                    described[first]?.authors.orEmpty(),
                    described[second]?.authors.orEmpty(),
                    answers.said(type),
                    models.of(type),
                    clusters,
                )
        return pair.copy(
            first = pair.first.copy(facets = shownOn(pair.first.facets, details.facetsOf(first, clusters))),
            second = pair.second.copy(facets = shownOn(pair.second.facets, details.facetsOf(second, clusters))),
            authorKin =
                kin?.let {
                    AuthorKin(
                        matching = it.matching,
                        expected = it.expected.toBigDecimal(),
                        oneEntry = it.oneEntry,
                        said =
                            it.told?.let { partly -> if (partly) AuthorKin.Said.PARTIAL else AuthorKin.Said.DIFFERENT },
                    )
                },
        )
    }

    /**
     * The facets of a work as compared, those of the layer as its screen gives them in their place ([shown]: its facets
     * as the screen shows them, none of a work no longer there); the site's own facets — its status, the ratings it
     * lets — as they were. A facet of the layer the work has no value of, none.
     */
    private fun shownOn(compared: List<DuplicateWorkFacet>, shown: List<ItemFacet>?): List<DuplicateWorkFacet> {
        if (shown == null) return compared
        val layer = shown.filter { it.editable }.groupBy { familyOf(it.facet) }
        val computed = layer.mapNotNull { (family, facets) ->
            val values = facets.flatMap { f -> f.propertyValues.filter(::has) }.distinctBy { it.key }
            if (values.isEmpty()) null
            else
                DuplicateWorkFacet(
                    key = family,
                    labelKey = (facets.firstOrNull { it.facet == family } ?: facets.first()).labelKey,
                    propertyValues = values.map { DuplicateValue(it.name, it.nameKey) }.sortedBy { it.name },
                    computed = true,
                    single = facets.any { it.single == true }.takeIf { it },
                )
        }
        return compared.filter { it.key !in layer.keys } + computed
    }

    /**
     * Whether the work has the value, as its screen gives it: the user's word it has it, or of no word one neither
     * offered to answer on nor implied by a tag under it — the site's, a draft, the model's given one.
     */
    private fun has(v: FacetValueInfo): Boolean =
        v.corrected == FacetValueInfo.Corrected.ADDED ||
            v.corrected == FacetValueInfo.Corrected.CONFIRMED ||
            (v.corrected == null && v.offered != true && v.implied != true)
}
