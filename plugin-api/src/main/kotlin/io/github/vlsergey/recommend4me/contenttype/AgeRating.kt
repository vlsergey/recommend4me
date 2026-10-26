package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemStore

/**
 * THE AGE RATING OF A WORK, one scale for every site — G, PG-13, R, NC-17, NC-21, in that order: the
 * site's word on it is the ratings it lets the work have ([SITE]) — one, of a site that states it
 * (ficbook, Archive of Our Own); a bound, of one that tells no more (author.today's "18+": NC-17 to
 * NC-21; a game of F95zone: R to NC-21); none, of one that tells nothing — and the layer's: the
 * work's rating ([RATING]), the site's when it lets one alone, worked out of the work's tags within
 * what the site lets otherwise, by one model of every site's works (the bounds learnt from too).
 */
object AgeRating {
    val G = StandardValue("g", "G")
    val PG13 = StandardValue("pg-13", "PG-13")
    val R = StandardValue("r", "R")
    val NC17 = StandardValue("nc-17", "NC-17")
    val NC21 = StandardValue("nc-21", "NC-21")

    /** The scale, from the mildest. */
    val VALUES = listOf(G, PG13, R, NC17, NC21)

    const val SITE_KEY = "age-rating-site"
    const val KEY = "age-rating"

    /** The ratings the site lets the work have: shown right above the work's ([RATING]). */
    val SITE = StandardFacet(SITE_KEY, values = VALUES, role = FacetRole.ORIGINAL)

    /** The work's age rating: one, every work has one; filtered from one rating to another. */
    val RATING = StandardFacet(
        KEY, values = VALUES, role = FacetRole.CONTENT, filter = true, onCard = true, infer = true, single = true, original = SITE,
        ordered = true, required = true,
    )

    /**
     * The site's word on the work's age rating written: the ratings it lets the work have — and the
     * work's own, when it lets one alone. Nothing of a site that tells nothing (an empty list).
     */
    fun write(items: ItemStore, itemId: String, allowed: List<StandardValue>) {
        items.setFacet(itemId, SITE_KEY, allowed.map { FacetValue(it.key, it.modelName) })
        items.setFacet(itemId, KEY, allowed.singleOrNull()?.let { listOf(FacetValue(it.key, it.modelName)) }.orEmpty())
    }

    /** The ratings from [from] to [to], both in. */
    fun between(from: StandardValue, to: StandardValue): List<StandardValue> = VALUES.subList(VALUES.indexOf(from), VALUES.indexOf(to) + 1)
}
