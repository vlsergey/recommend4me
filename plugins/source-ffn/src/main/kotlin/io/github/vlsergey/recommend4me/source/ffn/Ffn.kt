package io.github.vlsergey.recommend4me.source.ffn

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.CardDecor
import io.github.vlsergey.recommend4me.source.FacetDecor
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.PageDecor
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.universe.UniverseFacets

/**
 * fanfiction.net — FanFiction.Net, the oldest archive of fan fiction, in many languages. Read in the browser alone and
 * seen by the extension — the cards of every list, a work's page with its chapters named and the text of the one it
 * shows, its reviews: the site answers no request but a browser's, so the application downloads none of its pages.
 *
 * Most of what a work is the site writes in one line of its own template ([FfnLine]), on its page and on its card
 * alike; the rest — title, author, description, days, chapters — of elements of their own.
 */
class Ffn : Source {
    override val id = ID
    override val contentType = "books"
    override val homepage = HOME
    override val modes = setOf(SourceMode.BROWSER)

    override val schema =
        SourceSchema(
            facets =
                listOf(
                    Books.AUTHOR.asFacet(AUTHOR),
                    // The site's section of the fandom: "Books", "Anime/Manga", "Games"
                    FacetDef(CATEGORY, filter = true, searchWeight = 0.3f),
                    Books.FANDOM.asFacet(FANDOM),
                    AgeRating.SITE.asFacet(),
                    Books.STATUS.asFacet(STATUS),
                    StandardLanguages.LANGUAGE.asFacet(LANGUAGE),
                    // The site's genres: its tags
                    Books.TAG.asFacet(TAG),
                    // The characters and pairings as the line writes them, one field: a pairing "A/B" of the names in
                    // its brackets, a character alone
                    Books.PAIRINGS.asFacet(PAIRING),
                ),
            numbers =
                listOf(
                    StandardNumbers.WORDS.asNumber(WORDS),
                    Books.PARTS.asNumber(PARTS),
                    // The readers who made the work a favourite: the site's likes
                    StandardNumbers.LIKES.asNumber(FAVS),
                    NumberDef(FOLLOWS, NumberScale.LOG),
                    NumberDef(REVIEWS, NumberScale.LOG),
                ),
            texts =
                listOf(
                    TextDef(ANNOTATION, block = "text:annotation", searchWeight = 0.6f, role = TextRole.DESCRIPTION),
                    TextDef(PAIRINGS_LINE, searchWeight = 0.7f, searchByMeaning = true),
                ),
            reviews = true,
            parts = true,
            universeLine = PAIRINGS_LINE,
            kindFrom = FANDOM,
        )

    /** 2: a card's summary read whole, the words a search marked bold in it among its words. */
    override val parserVersion = 2

    /** A work's pages and reviews, an author's page, the lists of the sections, the crossovers and the search. */
    override val capturePatterns =
        listOf(
            Regex(
                "https://(?:www\\.)?fanfiction\\.net/" +
                    "(s|r|u|book|anime|cartoon|comic|game|misc|movie|play|tv|search|community|[^/?#]+_Crossovers)" +
                    "([/?#].*)?"
            )
        )

    override fun itemUrl(itemId: String) = workUrl(itemId)

    /** A chapter's page, by its number. */
    override fun partUrl(itemId: String, partId: String) = "$HOME/s/$itemId/$partId/"

    /** An author's page. */
    override fun valueUrl(facet: String, key: String, name: String) =
        when (facet) {
            AUTHOR -> "$HOME/u/$key/"
            else -> null
        }

    override fun itemIdOf(url: String): String? = workIdOf(url)

    override val pageDecor =
        PageDecor(
            panelAfter = HEADER,
            facets =
                listOf(
                    FacetDecor(Books.TAGS.key, after = HEADER),
                    FacetDecor(UniverseFacets.UNIVERSE, after = HEADER),
                    FacetDecor(UniverseFacets.CHARACTERS, after = HEADER),
                    FacetDecor(UniverseFacets.SECONDARY_CHARACTERS, after = HEADER),
                    FacetDecor(UniverseFacets.PAIRINGS, after = HEADER),
                ),
            cards = CardDecor(FfnPages.CARD, FfnPages.LINK),
            // "Next »" is the last link of the line of pages; on the last page the page's own number is
            nextPage = "#content_wrapper_inner > center > a:last-child",
        )

    override fun capture(page: CapturedPage, context: SourceContext): List<String> = FfnPages(context.items).read(page)

    companion object {
        const val ID = "ffn"
        const val HOME = "https://www.fanfiction.net"

        /** A work's page: its first chapter. */
        fun workUrl(itemId: String) = "$HOME/s/$itemId/1/"

        /** The work a page of the site is about — a chapter of it, its reviews — or null. */
        fun workIdOf(url: String): String? = ITEM_ADDRESS.find(url)?.groupValues?.get(1)

        private val ITEM_ADDRESS = Regex("^https?://(?:www\\.|m\\.)?fanfiction\\.net/(?:s|r)/(\\d+)(?:[/?#]|$)")

        /** The header of a work's page: its title, author, description and line. */
        private const val HEADER = "#profile_top"

        const val AUTHOR = "author"
        const val CATEGORY = "category"
        const val FANDOM = "fandom"
        const val STATUS = "status"
        const val LANGUAGE = "language"
        const val TAG = "tag"
        const val PAIRING = "pairing"

        const val WORDS = "words"
        const val PARTS = "parts"
        const val FAVS = "favs"
        const val FOLLOWS = "follows"
        const val REVIEWS = "reviews"

        const val ANNOTATION = "annotation"
        const val PAIRINGS_LINE = "pairingsLine"
    }
}
