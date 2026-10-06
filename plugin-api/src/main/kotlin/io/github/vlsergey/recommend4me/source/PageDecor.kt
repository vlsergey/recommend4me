package io.github.vlsergey.recommend4me.source

/**
 * How the browser extension turns the site's own pages into the application's interface: where on
 * a work's page its panel goes — the prediction, the grade, the tags with the suggested ones — and
 * which of the site's own elements it marks up in place: the values of facets, the reviews, the
 * pictures, the cards of the works in a list.
 *
 * Everything is CSS selectors over the page as the user sees it. An element of the site is matched
 * to what the application knows by what both see: a facet value by its name (or key), a review by
 * its id, a picture by its address, a card by the link to its work ([Source.itemIdOf]).
 */
class PageDecor(
    /** On a work's page: the panel goes after the first element this selects; none — it floats in a corner. */
    val panelAfter: String? = null,
    val facets: List<FacetDecor> = emptyList(),
    val reviews: ReviewDecor? = null,
    val pictures: PictureDecor? = null,
    val cards: CardDecor? = null,
)

/**
 * A facet on a work's page, one of two ways:
 *
 * - [values]: the site's own elements of its values. Each is matched to a value by its `title` or
 *   its text against the value's name or key, ignoring case, and gets the user's buttons in place;
 *   the values the user added and the suggested ones are put after the last of them;
 * - [after]: the element after which a line of the facet's values is put whole — for a facet the
 *   application works out ([FacetDef.infer]), whose values are not the site's.
 */
class FacetDecor(val facet: String, val values: String? = null, val after: String? = null) {
    init {
        require((values == null) != (after == null)) { "A facet is marked up in place or after an element: $facet" }
    }
}

/**
 * The reviews on a page: [selector] picks every review, its id is the attribute [idAttribute] with
 * [idPrefix] taken off — as the source keeps it ([ReviewData.id]).
 */
class ReviewDecor(val selector: String, val idAttribute: String = "id", val idPrefix: String = "")

/** The pictures of a work on its page: images whose `src` — or the link around them — is one of the item's pictures. */
class PictureDecor(val selector: String)

/** The cards of works in a list: [selector] picks a card, [link] the link to its work within it. */
class CardDecor(val selector: String, val link: String)
