package io.github.vlsergey.recommend4me.source.molwe

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.CardDecor
import io.github.vlsergey.recommend4me.source.FacetDecor
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.PageDecor
import io.github.vlsergey.recommend4me.source.ReviewDecor
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import org.jsoup.Jsoup

/**
 * molwe.ru — Molwe, a Russian site of fan fiction and original writing. Read in the browser and seen by the extension —
 * the cards of every list (the catalogue's, a fandom's, a tag's, an author's), a work's page with its chapters named
 * and its reviews, a chapter's page with its text — and a work's pages downloaded by the application itself when the
 * user presses for it: one work's pages, never a crawl of the catalogue, never on its own. What the screen does not
 * show is not written.
 *
 * A work is known by its page whole; a card tells some of it, the two views of a list each another part ([MolweCards]).
 * Fan fiction and original writing share one numbering: a work's number is its id whichever it is.
 */
class Molwe : Source {
    override val id = ID
    override val contentType = "books"
    override val homepage = HOME
    override val modes = setOf(SourceMode.BROWSER, SourceMode.PAGE)

    override val schema =
        SourceSchema(
            facets =
                listOf(
                    Books.AUTHOR.asFacet(AUTHOR),
                    // "Ориджинал" among them: the fandom of the original writing
                    Books.FANDOM.asFacet(FANDOM),
                    Books.DIRECTION.asFacet(DIRECTION),
                    AgeRating.SITE.asFacet(),
                    Books.STATUS.asFacet(STATUS),
                    Books.TAG.asFacet(TAG),
                    // The pairings and characters as the authors write them, one field: a pairing "A / B" and a
                    // character alone; the application works the characters and pairings of the universes out of them
                    Books.PAIRINGS.asFacet(PAIRING),
                ),
            numbers =
                listOf(
                    StandardNumbers.WORDS.asNumber(WORDS),
                    Books.PARTS.asNumber(PARTS),
                    // The readers the site counts: how far the work reached
                    StandardNumbers.VIEWS.asNumber(READERS),
                    NumberDef(REVIEWS, NumberScale.LOG),
                ),
            texts =
                listOf(
                    TextDef(ANNOTATION, block = "text:annotation", searchWeight = 0.6f, role = TextRole.DESCRIPTION),
                    TextDef(NOTES, block = "text:notes", searchWeight = 0.5f, role = TextRole.NOTES),
                    TextDef(PAIRINGS_LINE, searchWeight = 0.7f, searchByMeaning = true),
                ),
            reviews = true,
            parts = true,
            universeLine = PAIRINGS_LINE,
            // A work of the fandom "Ориджинал" is an original one
            kindFrom = FANDOM,
        )

    override val parserVersion = 1

    /** The catalogues and their lists, a work's pages, an author's page; the main page with its cards. */
    override val capturePatterns =
        listOf(Regex("https://molwe\\.ru/(fanfics|originals|author)([/?#].*)?"), Regex("https://molwe\\.ru/?([?#].*)?"))

    /** A work's page: one of fan fiction, the site sends one of original writing on to its own. */
    override fun itemUrl(itemId: String) = workUrl(itemId)

    /** A chapter's page, by the id of its address. */
    override fun partUrl(itemId: String, partId: String) = "${itemUrl(itemId)}/read/$partId"

    /** An author's page; a fandom's, a tag's are known by their names alone, not by their addresses. */
    override fun valueUrl(facet: String, key: String, name: String) =
        when (facet) {
            AUTHOR -> "$HOME/author/$key"
            else -> null
        }

    override fun itemIdOf(url: String): String? = workIdOf(url)

    override val pageDecor =
        PageDecor(
            panelAfter = HERO,
            facets =
                listOf(
                    // The site's tags are its word, never answered on: the work's tags after them
                    FacetDecor(Books.TAGS.key, after = TAGS_BLOCK),
                    // The universes, characters and pairings worked out go in lines of their own under the site's block
                    // of them
                    FacetDecor(UniverseFacets.UNIVERSE, after = PAIRINGS_BLOCK),
                    FacetDecor(UniverseFacets.CHARACTERS, after = PAIRINGS_BLOCK),
                    FacetDecor(UniverseFacets.SECONDARY_CHARACTERS, after = PAIRINGS_BLOCK),
                    FacetDecor(UniverseFacets.PAIRINGS, after = PAIRINGS_BLOCK),
                ),
            reviews = ReviewDecor(MolweWork.REVIEW, idAttribute = MolweWork.REVIEW_ID),
            cards = CardDecor(MolweCards.CARD, MolweCards.LINK),
            // "Следующая" is a link but on the last page
            nextPage = "nav[aria-label=\"Пагинация\"] > a:last-child",
        )

    override fun capture(page: CapturedPage, context: SourceContext): List<String> {
        val doc = Jsoup.parse(page.html, page.url)
        val touched = LinkedHashSet<String>()
        touched += MolweCards(context.items).read(doc, page.url, page.capturedAt)
        touched += MolweWork(context.items).read(doc, page.url, page.capturedAt)
        return touched.toList()
    }

    /**
     * The work's page and every chapter it names, read as if the browser had asked for them. False of a work the site
     * has not.
     */
    override fun refresh(itemId: String, context: SourceContext): Boolean {
        val read = fetch(itemUrl(itemId)) ?: return false
        val captured = itemId in capture(CapturedPage(read.first, read.second, Instant.now()), context)
        if (captured) chapters(itemId, context)
        return captured
    }

    /** The chapters of a work, each read as its own page. */
    private fun chapters(itemId: String, context: SourceContext) {
        context.items.parts(itemId).keys.forEach { part ->
            fetch(partUrl(itemId, part))?.let { (url, html) ->
                capture(CapturedPage(url, html, Instant.now()), context)
            }
        }
    }

    /**
     * A page of the site as the browser would get it — its address after the site sent it on, and its body; null of a
     * work that is not there.
     */
    private fun fetch(url: String): Pair<String, String>? {
        val response = paced {
            http.send(
                HttpRequest.newBuilder(URI(url)).timeout(TIMEOUT).header("User-Agent", USER_AGENT).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        return when (response.statusCode()) {
            HttpURLConnection.HTTP_OK -> response.uri().toString() to response.body()
            HttpURLConnection.HTTP_NOT_FOUND -> null
            HttpURLConnection.HTTP_FORBIDDEN -> error("the site shows the work to its signed-in readers alone (403)")
            TOO_MANY_REQUESTS -> error("the site asks to wait before the next request (429)")
            else -> error("the site answered ${response.statusCode()}")
        }
    }

    /** When the last request to the site was sent: the next waits out [PAUSE] after it. */
    private var lastRequest: Instant = Instant.EPOCH

    /** One request at a time, [PAUSE] apart. */
    @Synchronized
    private fun <T> paced(request: () -> T): T {
        val wait = Duration.between(Instant.now(), lastRequest.plus(PAUSE))
        if (!wait.isNegative) Thread.sleep(wait.toMillis())
        try {
            return request()
        } finally {
            lastRequest = Instant.now()
        }
    }

    companion object {
        const val ID = "molwe"
        const val HOME = "https://molwe.ru"

        /** A work's page: one of fan fiction, the site sends one of original writing on to its own. */
        fun workUrl(itemId: String) = "$HOME/fanfics/$itemId"

        /** The work a page of the site is about — its own, a chapter of it — or null. */
        fun workIdOf(url: String): String? = ITEM_ADDRESS.find(url)?.groupValues?.get(1)

        /** A work's page, a chapter of it: "https://molwe.ru/fanfics/20627/read/<chapter>", "/originals/20656". */
        private val ITEM_ADDRESS = Regex("^https?://molwe\\.ru/(?:fanfics|originals)/(\\d+)(?:[/?#]|$)")

        /** The header of a work's page: its cover, title, fandom, author and statistics. */
        const val HERO = "#work-hero"

        /** The block of the site's line of pairings and characters, the second of the work's description. */
        const val PAIRINGS_BLOCK = "#work-description > div:nth-child(2)"

        /** The block of the site's tags, the third of the work's description. */
        const val TAGS_BLOCK = "#work-description > div:nth-child(3)"

        private val http: HttpClient =
            HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build()
        private val TIMEOUT: Duration = Duration.ofSeconds(60)

        /** Says what asks: one work's pages at its reader's request. */
        private const val USER_AGENT =
            "recommend4me-bot (a personal recommender; one work's pages at its user's request)"

        private val PAUSE: Duration = Duration.ofSeconds(3)

        /** HTTP's "Too Many Requests": the site asks to wait. */
        private const val TOO_MANY_REQUESTS = 429

        const val AUTHOR = "author"
        const val FANDOM = "fandom"
        const val DIRECTION = "direction"
        const val STATUS = "status"
        const val TAG = "tag"
        const val PAIRING = "pairing"

        const val WORDS = "words"
        const val PARTS = "parts"
        const val READERS = "readers"
        const val REVIEWS = "reviews"

        const val ANNOTATION = "annotation"
        const val NOTES = "notes"
        const val PAIRINGS_LINE = "pairingsLine"
    }
}
