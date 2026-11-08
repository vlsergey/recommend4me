package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.AWARDS
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.BASE
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.CHARS
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.COMMENTS
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.LIKES
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.NOTES
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.REVIEWS
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.SERIES
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.STATUS
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.VIEWS
import java.net.URI
import java.time.Instant
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads the pages of author.today and writes what they say. Every page with cards of works — a genre, a search, a
 * library, a series, an author's works — gives those works as the cards show them; a work's page gives the rest of the
 * work; a page of reviews its reviews ([AuthorTodayReviews]); the reader the text of a chapter ([AuthorTodayReader]). A
 * page the parser does not know gives the cards it has, if any.
 */
class AuthorTodayPages(context: SourceContext) {

    private val items = context.items
    private val genres = AuthorTodayGenres(items)
    private val marks = AuthorTodayMarks(context)
    private val reviews = AuthorTodayReviews(items)
    private val reader = AuthorTodayReader(items)
    private val series = AuthorTodaySeries(items)

    fun read(page: CapturedPage): List<String> {
        val doc = Jsoup.parse(page.html, page.url)
        val path = runCatching { URI(page.url).path }.getOrDefault("")
        // The user's own marks are shown only to a user who is logged in: a guest's page says nothing of them
        val loggedIn = LOGGED_IN.find(page.html)?.groupValues?.get(1) == "true"
        val touched = LinkedHashSet<String>()
        touched += cards(doc, page.capturedAt, loggedIn)
        // A series' own page, and an author's list of series: after the cards, whose works they list
        AuthorTodaySeries.SERIES.matchEntire(path)?.groupValues?.get(1)?.let { series.page(doc, it) }
        if (AUTHOR_SERIES.matches(path)) series.list(doc)
        WORK.matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            when (m.groupValues[2]) {
                "" -> work(doc, page.html, id, page.capturedAt, loggedIn)?.let { touched += it }
                "/reviews" -> if (reviews.list(doc, id)) touched += id
            }
        }
        REVIEW.matchEntire(path)?.let { m -> reviews.review(doc, m.groupValues[1])?.let { touched += it } }
        READER.matchEntire(path)?.let { m ->
            reader.chapter(doc, m.groupValues[1], m.groupValues[2], page.capturedAt)?.let { touched += it }
        }
        return touched.toList()
    }

    // --- Cards of a list ---

    /** Every card of a work on the page. */
    private fun cards(doc: Document, now: Instant, loggedIn: Boolean): List<String> =
        doc.select(".book-row").mapNotNull { card ->
            cardHead(card, now)?.also { id -> cardFields(card, id, loggedIn) }
        }

    /** The work a card is of, written with its title and day: its id; none of a card that names no work. */
    private fun cardHead(card: Element, now: Instant): String? {
        val link = card.selectFirst(".book-title a[href^=/work/]")
        val id = link?.let { WORK_ID.find(it.attr("href"))?.groupValues?.get(1) }
        val title = link?.text()?.trim()?.ifEmpty { null }
        if (id == null || title == null) return null
        items.upsert(ItemHead(id, "$BASE/work/$id", title, updatedAt = WorkBlock(card).updatedAt()), now)
        return id
    }

    /**
     * What a card tells of its work [id] besides its head: the fields, the numbers, the annotation, the cover — and, to
     * the user [loggedIn], the shelf of the library.
     */
    private fun cardFields(card: Element, id: String, loggedIn: Boolean) {
        val block = WorkBlock(card)
        block.authors(".book-author a[href^=/u/]").takeIf { it.isNotEmpty() }?.let { items.setFacet(id, AUTHOR, it) }
        AuthorTodayGenres.genresIn(card.select(".book-genres a[href^=/work/genre/]")).let { (form, genres) ->
            this.genres.write(id, form, genres, tags = null)
        }
        block.status()?.let { items.setFacet(id, STATUS, listOf(it)) }
        block.series()?.let { items.setFacet(id, SERIES, listOf(it)) }
        ageRating(id, adult = card.selectFirst(".label-adult-only") != null)
        items.setNumbers(
            id,
            listOfNotNull(
                    block.size()?.let { CHARS to it },
                    block.stat("Просмотры")?.let { VIEWS to it },
                    block.stat("Понравилось")?.let { LIKES to it },
                    block.stat("Комментарии")?.let { COMMENTS to it },
                    block.stat("Рецензии")?.let { REVIEWS to it },
                )
                .toMap(),
        )
        card.selectFirst(".annotation")?.let { items.setTexts(id, mapOf(ANNOTATION to PageText.of(it))) }
        cover(card.selectFirst("img"))?.let { items.setPictures(id, listOf(it)) }
        if (loggedIn) marks.library(id, card.selectFirst("library-button"))
    }

    /**
     * The age ratings the site lets the work have: its "18+" is NC-17 to NC-21, its lack G to R; which one, the model
     * works out of the tags (user, 2026-10-26).
     */
    private fun ageRating(id: String, adult: Boolean) {
        AgeRating.write(
            items,
            id,
            if (adult) AgeRating.between(AgeRating.NC17, AgeRating.NC21)
            else AgeRating.between(AgeRating.G, AgeRating.R),
        )
    }

    // --- A work's page ---

    private fun work(doc: Document, html: String, id: String, now: Instant, loggedIn: Boolean): String? {
        val panel = doc.selectFirst(".book-meta-panel")
        val title = panel?.selectFirst(".book-title")?.text()?.trim()?.ifEmpty { null }
        if (panel == null || title == null) return null
        val block = WorkBlock(panel)
        items.upsert(ItemHead(id, "$BASE/work/$id", title, updatedAt = block.updatedAt()), now)
        block.authors(".book-authors a[href^=/u/]").takeIf { it.isNotEmpty() }?.let { items.setFacet(id, AUTHOR, it) }
        val (form, genres) = AuthorTodayGenres.genresIn(panel.select(".book-genres a[href^=/work/genre/]"))
        // The tags of the block of them; no block — a changed markup — is no word that the work has none
        val tags =
            panel.selectFirst(".tags")?.let { block ->
                block.select("a").map { a -> AuthorTodayGenres.siteTag(a.attr("title").ifBlank { a.text() }.trim()) }
            }
        this.genres.write(id, form, genres, tags)
        block.status()?.let { items.setFacet(id, STATUS, listOf(it)) }
        block.series()?.let {
            items.setFacet(id, SERIES, listOf(it))
            series.place(id, panel)
        }
        // The work's "18+" told by its panel; a panel without it says nothing — the card says so
        if (panel.selectFirst(".label-adult-only") != null) ageRating(id, adult = true)
        val like = LIKE_BUTTON.find(html)
        workNumbers(doc, block, panel, id, like)
        workTexts(doc, id)
        chapters(doc, id)
        (cover(doc.selectFirst(".book-cover img.cover-image"))
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { "/content/" in it })
            ?.let { items.setPictures(id, listOf(it)) }
        if (loggedIn) marks.ofWork(id, doc, html, like)
        return id
    }

    /**
     * The numbers of the work its page shows: its size and views on its [panel], the likes of its like button ([like])
     * or of the panel, the comments, the reviews and the awards.
     */
    private fun workNumbers(doc: Document, block: WorkBlock, panel: Element, id: String, like: MatchResult?) {
        items.setNumbers(
            id,
            listOfNotNull(
                    block.size()?.let { CHARS to it },
                    block.stat("Просмотры")?.let { VIEWS to it },
                    (like?.groupValues?.get(1)?.toDoubleOrNull()
                            ?: panel.selectFirst(".like-count")?.text()?.let(PageText::number)?.toDouble())
                        ?.let { LIKES to it },
                    doc.selectFirst("#commentTotalCount")
                        ?.text()
                        ?.let(PageText::number)
                        ?.takeIf { it > 0 }
                        ?.let { COMMENTS to it.toDouble() },
                    doc.select("a[href=/work/$id/reviews]")
                        .firstNotNullOfOrNull { PageText.number(it.text()) }
                        ?.let { REVIEWS to it.toDouble() },
                    panel.selectFirst(".btn-reward")?.text()?.let(PageText::number)?.let { AWARDS to it.toDouble() },
                )
                .toMap(),
        )
    }

    /** The annotation and the author's notes of the work's tab of texts. */
    private fun workTexts(doc: Document, id: String) {
        // The texts of the tab of them; no tab — a changed markup — is no word that the work has none
        if (doc.selectFirst("#tab-annotation") == null) return
        val annotation = doc.select("#tab-annotation .annotation .rich-content")
        val notes = annotation.firstOrNull {
            it.selectFirst("p.text-primary")?.text()?.startsWith("Примечания") == true
        }
        items.setTexts(
            id,
            mapOf(
                ANNOTATION to annotation.firstOrNull { it != notes }?.let(PageText::of),
                NOTES to notes?.let { n -> PageText.of(n, "p.text-primary, script, style") },
            ),
        )
    }

    /** The chapters the work's table of contents names, each with the day it came out, without their texts. */
    private fun chapters(doc: Document, id: String) {
        val parts =
            doc.select("#tab-chapters .table-of-content li").mapIndexedNotNull { i, li ->
                val a = li.selectFirst("a[href^=/reader/]") ?: return@mapIndexedNotNull null
                val partId =
                    READER.matchEntire(a.attr("href").substringBefore('?'))?.groupValues?.get(2)
                        ?: return@mapIndexedNotNull null
                PartData(
                    partId,
                    i,
                    a.text().trim(),
                    null,
                    li.selectFirst("[data-time]")?.attr("data-time")?.let(::instant),
                )
            }
        if (parts.isNotEmpty()) items.saveParts(id, parts)
    }

    companion object {
        private val WORK = Regex("/work/(\\d+)(/reviews)?/?")

        /** An author's list of series: "/u/denratmanov/series". */
        private val AUTHOR_SERIES = Regex("/u/[^/]+/series/?")
        private val REVIEW = Regex("/review/(\\d+)/?")
        private val READER = Regex("/reader/(\\d+)/(\\d+)/?")
        private val WORK_ID = Regex("^/work/(\\d+)")

        private val LOGGED_IN = Regex("isAuthenticated:\\s*(true|false)")
        private val LIKE_BUTTON =
            Regex(
                "name: 'like-button', params: \\{targetId: \\d+, likeCount: (\\d+), type: 'Work', " +
                    "disabled: \\w+, voteId: ([^}\\s]+)\\s*}"
            )

        internal fun instant(value: String): Instant? = runCatching {
            Instant.parse(value.let { if (it.endsWith("Z")) it else "${it}Z" })
        }
            .getOrNull()

        private fun cover(img: Element?): String? =
            img?.attr("src")?.takeIf { it.contains("/content/") }?.substringBefore('?')
    }
}
