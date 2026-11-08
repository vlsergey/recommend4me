package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.BASE
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.NOTES
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.ONLY_PART
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.SIGNAL_BOOKMARKED
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.SIGNAL_LATER
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.SIGNAL_SUBSCRIBED
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.STATUS
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads the pages of archiveofourown.org and writes what they say: the cards of a list (a tag, a search, a user's works
 * and bookmarks, a series, a collection, the history), a work's header with its chapters, the table of contents, the
 * comments shown.
 */
class Ao3Pages(private val context: SourceContext) {

    private val items = context.items
    private val fields = Ao3Fields(items)
    private val series = Ao3Series(items)

    fun read(page: CapturedPage): List<String> {
        val doc = Jsoup.parse(page.html, page.url)
        val path = runCatching { URI(page.url).path }.getOrDefault("")
        val touched = LinkedHashSet<String>()
        touched += cards(doc, page.capturedAt)
        // A series' own page: after the cards, whose works it lists
        Ao3Series.SERIES.matchEntire(path)?.groupValues?.get(1)?.let { series.page(doc, it) }
        WORK.matchEntire(path)?.let { m ->
            val id = m.groupValues[1]
            when (m.groupValues[2]) {
                "navigate" -> if (contents(doc, id)) touched += id
                else -> work(doc, id, page.capturedAt)?.let { touched += it }
            }
            if (comments(doc, id)) touched += id
        }
        return touched.toList()
    }

    // --- Cards of a list ---

    private fun cards(doc: Document, now: Instant): List<String> =
        doc.select("li.blurb").mapNotNull { card -> cardHead(card, now)?.also { id -> cardFields(card, id) } }

    /** The work a card is of, written with its title and day: its id; none of a card that names no work. */
    private fun cardHead(card: Element, now: Instant): String? {
        val link = card.selectFirst("h4.heading a[href^=/works/]")
        val id = link?.let {
            WORK.matchEntire(it.attr("href").substringBefore('?').substringBefore('#'))?.groupValues?.get(1)
        }
        val title = link?.text()?.trim()?.ifEmpty { null }
        if (id == null || title == null) return null
        items.upsert(
            ItemHead(
                id,
                "$BASE/works/$id",
                title,
                updatedAt = card.selectFirst("p.datetime")?.text()?.let(::cardDate),
            ),
            now,
        )
        return id
    }

    /** What a card tells of its work [id] besides its head: the fields, the summary, the series, the statistics. */
    private fun cardFields(card: Element, id: String) {
        fields.authors(id, card.select("h4.heading a[rel=author]"))
        fields.fandoms(id, card.select("h5.fandoms a.tag"))
        card.selectFirst("ul.required-tags span.rating")?.attr("title")?.let { fields.rating(id, it) }
        card.selectFirst("ul.required-tags span.category")?.attr("title")?.let { written ->
            fields.category(id, written.split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }
        card.selectFirst("ul.required-tags span.iswip")?.let { s ->
            val status = if (s.hasClass("complete-yes")) Books.Status.FINISHED else Books.Status.IN_PROGRESS
            items.setFacet(id, STATUS, listOf(FacetValue(status.key, status.modelName)))
        }
        // The work's own tags, not those of the bookmark the user wrote under it
        val tags = card.children().filter { it.tagName() == "ul" && it.hasClass("tags") }
        fun tagsOf(kind: String) = tags.flatMap { it.select("li.$kind a.tag") }
        fields.tagged(id, tagsOf("warnings"), tagsOf("relationships"), tagsOf("characters"), tagsOf("freeforms"))
        card
            .children()
            .firstOrNull { it.tagName() == "blockquote" && it.hasClass("summary") }
            ?.let {
                items.setTexts(id, mapOf(ANNOTATION to PageText.of(it)))
            }
        card
            .children()
            .firstOrNull { it.tagName() == "ul" && it.hasClass("series") }
            ?.selectFirst("a[href^=/series/]")
            ?.let { fields.series(id, it) }
        card.children().firstOrNull { it.tagName() == "dl" && it.hasClass("stats") }?.let { fields.stats(id, it) }
    }

    // --- A work's page ---

    /**
     * A work's page — its first chapter, another, or the whole work: the header and every chapter shown. None for a
     * page without the header: the warning before an adult work, a work locked to the site's users.
     */
    private fun work(doc: Document, id: String, now: Instant): String? {
        val meta = doc.selectFirst("dl.work.meta")
        val preface = doc.selectFirst("#workskin > div.preface.group")
        val title = preface?.selectFirst("h2.title")?.text()?.trim()?.ifEmpty { null }
        if (meta == null || preface == null || title == null) return null
        val stats = meta.selectFirst("dl.stats")
        val updated =
            stats?.selectFirst("dd.status")?.text()?.let(::isoDate)
                ?: stats?.selectFirst("dd.published")?.text()?.let(::isoDate)
        items.upsert(ItemHead(id, "$BASE/works/$id", title, updatedAt = updated), now)
        fields.authors(id, preface.select("h3.byline a[rel=author]"))
        fields.fandoms(id, meta.select("dd.fandom a.tag"))
        meta.selectFirst("dd.rating a.tag")?.text()?.let { fields.rating(id, it.trim()) }
        meta
            .select("dd.category a.tag")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.let { fields.category(id, it) }
        fields.tagged(
            id,
            meta.select("dd.warning a.tag"),
            meta.select("dd.relationship a.tag"),
            meta.select("dd.character a.tag"),
            meta.select("dd.freeform a.tag"),
        )
        meta.selectFirst("dd.series span.position a[href^=/series/]")?.let { fields.series(id, it) }
        stats?.let { fields.stats(id, it) }
        meta.selectFirst("dd.language")?.let { fields.language(id, it) }
        // A section of the preface the work has not: none; one whose text is not found: its markup changed, left as it
        // is
        fun text(section: String): Pair<Boolean, String?> {
            val found = preface.selectFirst(section) ?: return true to null
            return found.selectFirst("blockquote.userstuff")?.let { true to PageText.of(it) } ?: (false to null)
        }
        items.setTexts(
            id,
            listOf(ANNOTATION to text(".summary"), NOTES to text(".notes"))
                .filter { (_, read) -> read.first }
                .associate { (key, read) -> key to read.second },
        )
        marks(doc, id)
        chapters(doc, id, title)
        return id
    }

    /**
     * The chapters: every one the menu of the chapters names, without its text, and the text of every chapter the page
     * shows. A work of one chapter names none: its text is its only part.
     */
    private fun chapters(doc: Document, id: String, title: String) {
        val named =
            doc.select("#chapter_index option")
                .mapIndexed { i, o ->
                    PartData(o.attr("value"), i, NUMBERED.replace(o.text().trim(), ""), null)
                }
                .filter { it.id.isNotEmpty() }
        val position = named.associate { it.id to it.position }
        val shown =
            doc.select("#chapters > div.chapter[id^=chapter-]").mapNotNull { chapter ->
                val heading = chapter.selectFirst("div.chapter.preface h3.title") ?: return@mapNotNull null
                val chapterId =
                    heading.selectFirst("a[href]")?.attr("href")?.let { CHAPTER.find(it)?.groupValues?.get(1) }
                        ?: return@mapNotNull null
                val text =
                    chapter
                        .selectFirst("div.userstuff[role=article]")
                        ?.let { PageText.of(it, NOT_THE_TEXT) }
                        ?.ifBlank { null } ?: return@mapNotNull null
                val number = chapter.id().removePrefix("chapter-").toIntOrNull()
                PartData(chapterId, position[chapterId] ?: number?.minus(1) ?: -1, chapterTitle(heading.text()), text)
            }
        val parts = named.filter { n -> shown.none { it.id == n.id } } + shown
        if (parts.isNotEmpty()) {
            items.saveParts(id, parts)
            return
        }
        doc.selectFirst("#chapters > div.userstuff")
            ?.let { PageText.of(it, NOT_THE_TEXT) }
            ?.takeIf { it.isNotBlank() }
            ?.let { text ->
                items.saveParts(id, listOf(PartData(ONLY_PART, 0, title, text)))
            }
    }

    /**
     * The table of contents (/works/<id>/navigate): every chapter with the day it came out — of a work known already.
     */
    private fun contents(doc: Document, id: String): Boolean {
        val parts =
            if (items.find(id) == null) emptyList()
            else
                doc.select("ol.chapter.index li").mapIndexedNotNull { i, li ->
                    val a = li.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                    val chapterId = CHAPTER.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                    val day = li.selectFirst("span.datetime")?.text()?.trim()?.removePrefix("(")?.removeSuffix(")")
                    PartData(chapterId, i, NUMBERED.replace(a.text().trim(), ""), null, day?.let(::isoDate))
                }
        if (parts.isNotEmpty()) items.saveParts(id, parts)
        return parts.isNotEmpty()
    }

    /**
     * The user's own marks of the work — kudos left, a bookmark, a subscription, marked for later — read only of a page
     * shown to a user logged in: the greeting names them.
     */
    private fun marks(doc: Document, id: String) {
        val me =
            doc.selectFirst("#greeting a[href^=/users/]")?.attr("href")?.let {
                Ao3Fields.USER.find(it)?.groupValues?.get(1)
            } ?: return
        val yes = Books.YES.key
        doc.selectFirst("#kudos")?.let { kudos ->
            val left = kudos.select("a[href]").any { it.attr("href") == "/users/$me" }
            // A long list is cut short: the user's name missing from it says nothing
            if (left || kudos.selectFirst("#kudos_more_link") == null)
                context.signals.set(id, Books.LIKED.key, yes.takeIf { left })
        }
        doc.selectFirst("ul.work.navigation li.bookmark a")?.text()?.let {
            context.signals.set(id, SIGNAL_BOOKMARKED, yes.takeIf { _ -> it.startsWith("Edit") })
        }
        doc.selectFirst("ul.work.navigation li.subscribe input[type=submit]")?.attr("value")?.let {
            context.signals.set(id, SIGNAL_SUBSCRIBED, yes.takeIf { _ -> it.startsWith("Unsubscribe") })
        }
        doc.selectFirst("ul.work.navigation li.mark a")?.text()?.let {
            context.signals.set(id, SIGNAL_LATER, yes.takeIf { _ -> it.startsWith("Mark as Read") })
        }
    }

    // --- Comments ---

    /** The comments the page shows, of the work or of its chapter. */
    private fun comments(doc: Document, id: String): Boolean {
        val list = doc.select("li.comment[id^=comment_]")
        if (list.isEmpty() || items.find(id) == null) return false
        items.saveReviews(
            id,
            list.mapNotNull { c ->
                val text =
                    c.children()
                        .firstOrNull { it.tagName() == "blockquote" && it.hasClass("userstuff") }
                        ?.let(PageText::of)
                        ?.ifBlank { null } ?: return@mapNotNull null
                val byline = c.selectFirst("h4.byline")
                ReviewData(
                    c.id().removePrefix("comment_"),
                    byline?.selectFirst("a[href^=/users/]")?.text()?.trim()
                        ?: byline?.ownText()?.trim()?.ifEmpty { null },
                    null,
                    text,
                    byline?.selectFirst("span.posted.datetime")?.let(::commentDate),
                )
            },
        )
        return true
    }

    companion object {
        /** A work's address: "/works/<id>", "/works/<id>/chapters/<chapter>", "/works/<id>/navigate". */
        private val WORK = Regex("/works/(\\d+)(?:/chapters/\\d+|/(navigate))?/?")
        private val CHAPTER = Regex("/works/\\d+/chapters/(\\d+)")

        /** The number of a chapter in a menu and a table of contents: "1. Argument". */
        private val NUMBERED = Regex("^\\d+\\.\\s*")

        /** What the text of a chapter holds that is not the text: the heading "Chapter Text", scripts. */
        private const val NOT_THE_TEXT = "h3.landmark, script, style"

        private val CARD_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        private val COMMENT_TIME = DateTimeFormatter.ofPattern("d MMM yyyy h:mma", Locale.ENGLISH)

        /** "2026-10-13" — the site's days are of no zone given: taken as UTC. */
        private fun isoDate(text: String): Instant? = runCatching {
            LocalDate.parse(text.trim()).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
            .getOrNull()

        /** "13 Oct 2026". */
        private fun cardDate(text: String): Instant? = runCatching {
            LocalDate.parse(text.trim(), CARD_DATE).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
            .getOrNull()

        /** "Mon 03 Aug 2026 10:41PM UTC", from its parts: the day, the month, the year and the time. */
        private fun commentDate(posted: Element): Instant? {
            val parts =
                listOf("span.date", "abbr.month", "span.year", "span.time").map { posted.selectFirst(it)?.text() }
            if (parts.any { it == null }) return null
            return runCatching {
                LocalDateTime.parse(parts.joinToString(" "), COMMENT_TIME).toInstant(ZoneOffset.UTC)
            }
                .getOrNull()
        }

        /** "Chapter 1: Argument" → "Argument"; "Chapter 2" stays. */
        private fun chapterTitle(heading: String): String =
            heading.trim().let { t -> Regex("^Chapter \\d+:\\s*(.+)$").find(t)?.groupValues?.get(1) ?: t }
    }
}
