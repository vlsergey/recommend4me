package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.CardDecor
import io.github.vlsergey.recommend4me.source.FacetDecor
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.Job
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.PageDecor
import io.github.vlsergey.recommend4me.source.PictureDecor
import io.github.vlsergey.recommend4me.source.ReviewDecor
import io.github.vlsergey.recommend4me.source.ScrapeProgress
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.time.Instant

/**
 * fanfics.me — Фанфикс, the former hogwartsnet.ru: fan fiction of many fandoms, Harry Potter's
 * first, and original works. The site asks no visitor to prove itself, and the application reads
 * it as the browser does: the whole catalogue by the site's search of every work, twenty cards a
 * page with all a work's header says ([FanficsMeSync]); a work's own page with its text, the notes
 * of its author and the comments when the user asks for it or grades a work known by its card
 * alone; and every page the user opens, sent by the extension.
 *
 * The site's word on a work is structured: the fandoms, the characters and the pairings by their
 * ids of its encyclopedia; the genres, the warnings and the events ("События") of its lists — the
 * three the site's tags here, each described by the site's own help and the encyclopedia of a fandom.
 * The author of a translation is its translator, who put it on the site; the writer of the original
 * is a facet of its own, named on the work's page alone.
 */
class FanficsMe : Source {
    override val id = ID
    override val contentType = "books"
    override val homepage = BASE
    override val modes = setOf(SourceMode.BROWSER, SourceMode.PAGE, SourceMode.UPDATES, SourceMode.SCRAPE_ALL)

    override val schema = SourceSchema(
        facets = listOf(
            Books.AUTHOR.asFacet(AUTHOR),
            FacetDef(ORIGINAL_AUTHOR, searchWeight = 0.6f, names = true),
            Books.FANDOM.asFacet(FANDOM),
            Books.DIRECTION.asFacet(DIRECTION),
            AgeRating.SITE.asFacet(),
            Books.STATUS.asFacet(STATUS),
            FacetDef(SIZE, filter = true, onCard = true, role = FacetRole.FORM),
            // The genres, the warnings and the events of the site: its tags, as they are
            Books.TAG.asFacet(TAG),
            // The characters and pairings of the site's encyclopedia, by their ids: the application
            // works the characters and pairings of the universes out of them
            Books.PAIRINGS.asFacet(PAIRING),
            FacetDef(SERIES, searchWeight = 0.7f, role = FacetRole.SERIES),
            FacetDef(ORIGINAL_LANGUAGE, filter = true),
        ),
        numbers = listOf(
            // Characters, not words, on every card: a number of its own; the words on the work's page alone
            StandardNumbers.CHARS.asNumber(CHARS),
            StandardNumbers.WORDS.asNumber(WORDS),
            Books.PARTS.asNumber(PARTS),
            StandardNumbers.VIEWS.asNumber(VIEWS),
            NumberDef(READERS, NumberScale.LOG),
            NumberDef(COMMENTS, NumberScale.LOG),
            NumberDef(RECOMMENDATIONS, NumberScale.LOG),
        ),
        texts = listOf(
            TextDef(ANNOTATION, block = "text:annotation", searchWeight = 0.6f, role = TextRole.DESCRIPTION),
            TextDef(NOTES, block = "text:notes", searchWeight = 0.5f, role = TextRole.NOTES),
            TextDef(PAIRINGS_LINE, searchWeight = 0.7f, searchByMeaning = true),
            TextDef(ORIGINAL_TITLE, searchWeight = 0.7f),
            TextDef(ORIGINAL_LINK, searchWeight = 0f),
        ),
        reviews = true,
        parts = true,
        universeLine = PAIRINGS_LINE,
        // A work of the fandom "Ориджиналы" is an original one
        kindFrom = FANDOM,
    )

    /** The translators kept before as a facet of their own made the authors of their translations. */
    override val migrations = listOf("classpath:db/fanfics.me")

    override val capturePatterns = listOf(
        Regex("https://fanfics\\.me/(fic\\d+|read\\.php|find|fandom\\d+|character\\d+|paring[\\d_]+|serie\\d+|collection\\d+|user\\d+|translations|recommends|fics_rating|html)([/?#].*)?"),
    )

    override fun itemUrl(itemId: String) = "$BASE/fic$itemId"

    /** A chapter in the reader, by its number from 0 — the site's own of it. */
    override fun partUrl(itemId: String, partId: String) = "$BASE/read.php?id=$itemId&chapter=$partId"

    override fun itemIdOf(url: String): String? =
        (WORK_ADDRESS.find(url) ?: READER_ADDRESS.find(url))?.groupValues?.get(1)

    override val pageDecor = PageDecor(
        // After the work's header with its description and the buttons to read it
        panelAfter = HEAD,
        facets = listOf(
            // The site's genres, warnings and events are its word, never answered on: the work's tags after the header's last line
            FacetDecor(Books.TAGS.key, after = "$HEAD .tr:has(.DateUpdate)"),
            // The universes, characters and pairings worked out go in lines of their own under the site's line of them
            FacetDecor(UniverseFacets.UNIVERSE, after = CHARACTERS_ROW),
            FacetDecor(UniverseFacets.CHARACTERS, after = CHARACTERS_ROW),
            FacetDecor(UniverseFacets.SECONDARY_CHARACTERS, after = CHARACTERS_ROW),
            FacetDecor(UniverseFacets.PAIRINGS, after = CHARACTERS_ROW),
        ),
        reviews = ReviewDecor("table.MessageBody[id^='$COMMENT_PREFIX']", idPrefix = COMMENT_PREFIX),
        pictures = PictureDecor("$HEAD .FicHead_cover img"),
        cards = CardDecor("div.FicTable[id^='fic_']", ".FicTable_Title h4 a[href^='/fic']"),
    )

    override fun capture(page: CapturedPage, context: SourceContext): List<String> = FanficsMePages(context).read(page)

    override fun scrape(mode: SourceMode, context: SourceContext, job: Job) = FanficsMeSync(context, client).run(mode, job)

    override fun scrapeProgress(context: SourceContext): ScrapeProgress = FanficsMeSync.progress(context)

    /**
     * The work's own page and its whole text in the reader, read as if the browser had sent them.
     * The text of a work rated NC-17 the site shows only to a reader logged in: of it, the header alone.
     */
    override fun refresh(itemId: String, context: SourceContext): Boolean {
        val url = itemUrl(itemId)
        val html = client.get(url) ?: return false
        val pages = FanficsMePages(context)
        if (itemId !in pages.read(CapturedPage(url, html, Instant.now()))) return false
        context.keepPage(url, html, itemId)
        val reader = "$BASE/read.php?id=$itemId"
        client.get(reader)?.let { text ->
            if (itemId in pages.read(CapturedPage(reader, text, Instant.now()))) context.keepPage(reader, text, itemId)
        }
        return true
    }

    /** A work known by its card alone — no chapter of it — is downloaded once it is graded. */
    override fun needsRefresh(itemId: String, context: SourceContext): Boolean =
        context.items.find(itemId) != null && context.items.parts(itemId).isEmpty()

    /** A cover as the site lists it — "…_200_300.jpg" — and its original, the larger first. */
    override fun pictureAddresses(url: String): List<String> = listOf(url.replace(PREVIEW_SIZE, ""), url).distinct()

    override fun pictureHeaders(url: String, context: SourceContext): Map<String, String> = mapOf("User-Agent" to FanficsMeClient.USER_AGENT)

    private val client = FanficsMeClient()

    companion object {
        const val ID = "fanfics.me"
        const val BASE = "https://fanfics.me"

        /** A work's page and its own pages: "/fic123", "/fic123/comments". */
        private val WORK_ADDRESS = Regex("^https?://(?:www\\.)?fanfics\\.me/fic(\\d+)(?:[/?#]|$)")

        /** The reader of a work's text: "/read.php?id=123&chapter=2". */
        private val READER_ADDRESS = Regex("^https?://(?:www\\.)?fanfics\\.me/read\\.php\\?(?:[^#]*&)?id=(\\d+)(?:[&#]|$)")

        /** The size a cover is listed by, after its name. */
        private val PREVIEW_SIZE = Regex("_\\d+_\\d+(?=\\.\\w+$)")

        /** The header of a work's page: the block holding its id. */
        private const val HEAD = "div.FicHead:has(#fic_id)"

        /** The line of the header with the work's characters and pairings. */
        private const val CHARACTERS_ROW = "$HEAD .tr:has(> .content > a[href^='/character'], > .content > a[href^='/paring'])"

        /** What the id of a comment's table begins with. */
        const val COMMENT_PREFIX = "MessageCommentTable_"

        const val AUTHOR = "author"
        const val ORIGINAL_AUTHOR = "originalAuthor"
        const val FANDOM = "fandom"
        const val DIRECTION = "direction"
        const val STATUS = "status"
        const val SIZE = "size"
        const val TAG = "tag"
        const val PAIRING = "pairing"
        const val SERIES = "series"
        const val ORIGINAL_LANGUAGE = "originalLanguage"

        const val CHARS = "chars"
        const val WORDS = "words"
        const val PARTS = "parts"
        const val VIEWS = "views"
        const val READERS = "readers"
        const val COMMENTS = "comments"
        const val RECOMMENDATIONS = "recommendations"

        const val ANNOTATION = "annotation"
        const val NOTES = "notes"
        const val PAIRINGS_LINE = "pairingsLine"
        const val ORIGINAL_TITLE = "originalTitle"
        const val ORIGINAL_LINK = "originalLink"

        /** The fandom of the original works, "Ориджиналы". */
        const val ORIGINALS = "1"
    }
}
