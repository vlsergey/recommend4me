package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.AuthorKin
import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateValue
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.ItemFacet
import io.github.vlsergey.recommend4me.author.AuthorKin as KinOfAuthors
import io.github.vlsergey.recommend4me.author.AuthorKinship
import io.github.vlsergey.recommend4me.author.AuthorsKin
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

    /** The pair [r] as its window shows it, the word [said] of it; null of a work no longer there. */
    internal fun of(type: TypeStore, r: Ranked, said: Said): DuplicatePair? {
        val (first, second) = r.pair
        val described = views.describe(type, listOf(first, second))
        val clusters = works.of(type)
        fun shown(key: ItemKey): Described? =
            described[key]?.let { d ->
                Described(
                    d.work,
                    d.authors,
                    d.fields.withFacets(
                        shownOn(d.fields.facets, details.facetsOf(key, clusters), comparedFamilies(type, key))
                    ),
                )
            }
        val x = shown(first)
        val y = shown(second)
        if (x == null || y == null) return null
        val same = KinOfAuthors(type.authors.distinct.told()).share(x.authors, y.authors)
        val kin =
            if (same) null
            else AuthorKinship.of(type, x.authors, y.authors, answers.said(type), models.of(type), clusters)
        return views.pairOf(r, x, y, same, said, kin?.let(::kinOf))
    }

    /** How close two authors are by their works, as the window of a pair tells it. */
    private fun kinOf(kin: AuthorsKin) =
        AuthorKin(
            matching = kin.matching,
            expected = kin.expected.toBigDecimal(),
            oneEntry = kin.oneEntry,
            said = kin.told?.let { partly -> if (partly) AuthorKin.Said.PARTIAL else AuthorKin.Said.DIFFERENT },
        )

    /**
     * The facets of a work as compared, those of the layer as its screen gives them in their place ([shown]: its facets
     * as the screen shows them, none of a work no longer there); the site's own facets — its status, the ratings it
     * lets — as they were. A facet of the layer the work has no value of, none.
     */
    private fun shownOn(compared: List<ShownFacet>, shown: List<ItemFacet>?, families: Set<String>): List<ShownFacet> {
        if (shown == null) return compared
        val layer = shown.filter { it.editable }.groupBy { familyOf(it.facet) }
        val computed = layer.mapNotNull { (family, facets) ->
            val values = facets.flatMap { f -> f.propertyValues.filter(::has) }.distinctBy { it.key }
            if (values.isEmpty()) null
            else
                ShownFacet(
                    key = family,
                    labelKey = (facets.firstOrNull { it.facet == family } ?: facets.first()).labelKey,
                    values = values.map { DuplicateValue(it.name, it.nameKey) }.sortedBy { it.name },
                    single = facets.any { it.single == true },
                    computed = true,
                    compared = family in families,
                )
        }
        return compared.filter { it.key !in layer.keys } + computed
    }

    /** The facets the search compares the works of the site of [key] by, as compared. */
    private fun comparedFamilies(type: TypeStore, key: ItemKey): Set<String> =
        type.source(key.source)?.let { ComparedFacets(it).families.values.toSet() }.orEmpty()

    /**
     * Whether the work has the value, as its screen gives it: the user's word it has it, or of no word one neither
     * offered to answer on nor implied by a tag under it — the site's, a draft, the model's given one.
     */
    private fun has(v: FacetValueInfo): Boolean =
        v.corrected == FacetValueInfo.Corrected.ADDED ||
            v.corrected == FacetValueInfo.Corrected.CONFIRMED ||
            (v.corrected == null && v.offered != true && v.implied != true)
}
