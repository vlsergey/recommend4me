package io.github.vlsergey.recommend4me.source.aff

import io.github.vlsergey.recommend4me.cast.CastHabit
import io.github.vlsergey.recommend4me.cast.SaidCast
import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
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
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * adult-fanfiction.org — AFF, the Adult FanFiction archive: fan fiction and original writing for adults, in archives of
 * their own (a subdomain each: hp, anime, tv…). Read in the browser and seen by the extension — the cards of every
 * list, a work's chapter with its header, its reviews — and a work's chapters downloaded by the application itself when
 * the user presses for it: one work's pages, never a crawl of the catalogue, never on its own. What the screen does not
 * show is not written.
 *
 * A work is known by its card: what it is about, its tags, its days are on the card alone, the work's own page shows
 * its chapters.
 */
class Aff : Source {
    override val id = ID
    override val contentType = "books"
    override val homepage = HOME
    override val modes = setOf(SourceMode.BROWSER, SourceMode.PAGE)

    override val schema =
        SourceSchema(
            facets =
                listOf(
                    Books.AUTHOR.asFacet(AUTHOR),
                    FacetDef(ARCHIVE, filter = true, searchWeight = 0.3f),
                    Books.FANDOM.asFacet(FANDOM),
                    // The work's place in the archive's tree of categories as written, whole: the fandom, the kind of
                    // relationships and the pairing are read out of it
                    FacetDef(CATEGORY, searchWeight = 0.4f),
                    AgeRating.SITE.asFacet(),
                    Books.DIRECTION.asFacet(DIRECTION),
                    Books.STATUS.asFacet(STATUS),
                    Books.TAG.asFacet(TAG),
                    Books.PAIRINGS.asFacet(PAIRING),
                ),
            numbers =
                listOf(
                    Books.PARTS.asNumber(PARTS),
                    StandardNumbers.VIEWS.asNumber(VIEWS),
                    // The readers' recommendations are the site's likes: one number with every site's
                    StandardNumbers.LIKES.asNumber(RECOMMENDED),
                    NumberDef(REVIEWS, NumberScale.LOG),
                    NumberDef(READING, NumberScale.LOG),
                ),
            texts =
                listOf(
                    TextDef(ANNOTATION, block = "text:annotation", searchWeight = 0.6f, role = TextRole.DESCRIPTION),
                    TextDef(PAIRINGS_LINE, searchWeight = 0.7f, searchByMeaning = true),
                ),
            reviews = true,
            parts = true,
            universeLine = PAIRINGS_LINE,
            // The archive of the original writing tells a work is no fan fiction
            kindFrom = ARCHIVE,
        )

    /**
     * 2: a work's page reads its place in the archive's tree too — its fandom of it — as a card of the archive does.
     */
    override val parserVersion = 2

    override val capturePatterns =
        listOf(
            Regex("https://(?!www\\.|www2\\.|members\\.|adsys\\.)[a-z0-9]+\\.adult-fanfiction\\.org(/.*)?"),
            Regex("https://members\\.adult-fanfiction\\.org/profile\\.php\\?.*"),
        )

    override fun itemUrl(itemId: String): String {
        val (archive, number) = parts(itemId)
        return "https://$archive.$DOMAIN/story.php?no=$number"
    }

    /** A chapter's page, by its number in the work. */
    override fun partUrl(itemId: String, partId: String) = "${itemUrl(itemId)}&chapter=$partId"

    /** An author's page, and the list of an archive's works. */
    override fun valueUrl(facet: String, key: String, name: String) =
        when (facet) {
            AUTHOR -> "https://members.$DOMAIN/profile.php?id=$key"
            ARCHIVE -> "https://$key.$DOMAIN/"
            else -> null
        }

    override fun itemIdOf(url: String): String? =
        ITEM_ADDRESS.find(url)?.groupValues?.let { (_, archive, number) -> idOf(archive, number) }

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
            // A work's card in every list — an archive's, a category's, a search — and on an author's page, of another
            // markup
            cards = CardDecor("${AffPages.CARD}, ${AffProfiles.CARD}", "a.story-title, ${AffProfiles.LINK}"),
            // "Next »" is the last link of the pagination; on the last page a number is
            nextPage = "div.pagination > a.pagination-btn:last-child",
        )

    override fun capture(page: CapturedPage, context: SourceContext): List<String> = AffPages(context).read(page)

    /** The site's habit: a pairing "A/B" of names; a name alone a character. */
    override val castHabit = CastHabit { entries -> SaidCast.of(entries.map { it.name ?: it.key }, '/') }

    /**
     * The work's chapters, every one, read as if the browser had asked for them: the first chapter's page names the
     * others. Of a work known by its card alone: one never known is not read — its card tells whether it is kept.
     */
    override fun refresh(itemId: String, context: SourceContext): Boolean {
        val first = itemUrl(itemId)
        val read = if (context.items.find(itemId) == null) null else fetch(first)
        val captured = read != null && itemId in capture(CapturedPage(first, read, Instant.now()), context)
        if (captured) otherChapters(itemId, context)
        return captured
    }

    /** The chapters of a work but its first, each read as its own page, in their order. */
    private fun otherChapters(itemId: String, context: SourceContext) {
        context.items
            .parts(itemId)
            .keys
            .filter { it != FIRST_PART }
            .sortedBy { it.toIntOrNull() }
            .forEach { part ->
                val url = partUrl(itemId, part)
                fetch(url)?.let { capture(CapturedPage(url, it, Instant.now()), context) }
            }
    }

    /** A page of the site as the browser would get it; null of a work that is not there. */
    private fun fetch(url: String): String? {
        val response = paced {
            http.send(
                HttpRequest.newBuilder(URI(url)).timeout(TIMEOUT).header("User-Agent", USER_AGENT).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        }
        return when (response.statusCode()) {
            HttpURLConnection.HTTP_OK -> response.body()
            HttpURLConnection.HTTP_NOT_FOUND -> null
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
        const val ID = "aff"
        const val DOMAIN = "adult-fanfiction.org"
        const val HOME = "https://www.$DOMAIN"

        /** A work's id: its archive and its number there, "hp-600100589". */
        fun idOf(archive: String, number: String) = "$archive-$number"

        /** The archive and the number of a work's id. */
        private fun parts(itemId: String): Pair<String, String> =
            itemId.substringBeforeLast('-') to itemId.substringAfterLast('-')

        /** A work's page and its reviews: "https://<archive>.adult-fanfiction.org/story.php?no=<number>&chapter=2". */
        private val ITEM_ADDRESS =
            Regex("^https?://([a-z0-9]+)\\.adult-fanfiction\\.org/(?:story|reviews)\\.php\\?(?:[^#]*&)?no=(\\d+)")

        /** The header of a work's page: its title, its author, its category and statistics. */
        private const val HEADER = "div.card:has(> div > div.story-header-two-column)"

        /** The chapter a work's page shows when it names none. */
        const val FIRST_PART = "1"

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
        const val ARCHIVE = "archive"
        const val FANDOM = "fandom"
        const val CATEGORY = "category"
        const val DIRECTION = "direction"
        const val STATUS = "status"
        const val TAG = "tag"
        const val PAIRING = "pairing"

        const val PARTS = "parts"
        const val VIEWS = "views"
        const val RECOMMENDED = "recommended"
        const val REVIEWS = "reviews"
        const val READING = "reading"

        const val ANNOTATION = "annotation"
        const val PAIRINGS_LINE = "pairingsLine"
    }
}
