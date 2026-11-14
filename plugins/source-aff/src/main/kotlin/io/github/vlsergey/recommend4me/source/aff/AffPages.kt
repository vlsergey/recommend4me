package io.github.vlsergey.recommend4me.source.aff

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.FIRST_PART
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.PARTS
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.READING
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.RECOMMENDED
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.REVIEWS
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.VIEWS
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads the pages of adult-fanfiction.org and writes what they say: the cards of a list (an archive's latest, a
 * category, a search; an author's works, [AffProfiles]), a work's page — its header and the chapter it shows, every
 * chapter named — and its reviews; the site's legend of its codes, where a page shows it ([AffFields.legend]); the
 * pages of the categories it links ([AffCategories]). A work is written by its card: its page and its reviews add to a
 * work known by one.
 */
class AffPages(private val context: SourceContext) {

    private val items = context.items
    private val fields = AffFields(items)
    private val profiles = AffProfiles(items, fields)

    fun read(page: CapturedPage): List<String> {
        val doc = Jsoup.parse(page.html, page.url)
        val touched = LinkedHashSet<String>()
        touched += cards(doc, page.capturedAt)
        touched += profiles.read(doc, page.url, page.capturedAt)
        fields.legend(doc)
        AffCategories(context.database).learn(doc)
        val id = Aff().itemIdOf(page.url)
        if (id != null && items.find(id) != null) {
            val path = runCatching { URI(page.url).path }.getOrDefault("")
            if (path.endsWith("/story.php") && work(doc, id, page.capturedAt)) touched += id
            if (path.endsWith("/reviews.php") && reviews(doc, id)) touched += id
        }
        return touched.toList()
    }

    // --- Cards of a list ---

    private fun cards(doc: Document, now: Instant): List<String> =
        doc.select(CARD).mapNotNull { card -> card(card, now) }

    /** The work of a card, written whole; its id. None of a card that names no work. */
    private fun card(card: Element, now: Instant): String? {
        val link = card.selectFirst("a.story-title")
        val id = link?.let { Aff().itemIdOf(it.absUrl("href")) }
        val title = link?.text()?.trim()?.ifEmpty { null }
        val codes = card.select("span.story-tag").map { it.text().trim() }.filter { it.isNotEmpty() }
        return if (id == null || title == null) null else id.also { write(card, it, title, codes, now) }
    }

    /** The work [id] of the [card] written whole: its head, author, place, rating, statistics, description, codes. */
    private fun write(card: Element, id: String, title: String, codes: List<String>, now: Instant) {
        val meta = metaOf(card.select("div.story-meta-item"))
        items.upsert(
            ItemHead(id, Aff().itemUrl(id), title, updatedAt = (meta["Updated"] ?: meta["Published"])?.let(::cardDate)),
            now,
        )
        fields.archive(id, id.substringBeforeLast('-'))
        author(id, card.selectFirst("a.story-author"))
        card.selectFirst("div.story-location")?.let { location ->
            fields.located(id, location.ownText().split('»', '�'))
        }
        meta["Rated"]?.let { fields.rating(id, it) }
        numbers(id, meta)
        card.selectFirst("div.story-description")?.let { items.setTexts(id, mapOf(ANNOTATION to PageText.of(it))) }
        fields.codes(id, codes)
    }

    /** The author of the link [a] to their profile: their id there, their name. */
    private fun author(id: String, a: Element?) {
        val key = a?.attr("href")?.let { PROFILE.find(it)?.groupValues?.get(1) } ?: return
        items.setFacet(id, AUTHOR, listOf(FacetValue(key, a.text().trim())))
    }

    /** The statistics a card or a header tells, by their labels: the chapters, the views, the reviews… */
    private fun numbers(id: String, meta: Map<String, String>) {
        fun count(label: String) =
            meta[label]?.let { Regex("\\d[\\d,]*").find(it)?.value?.replace(",", "") }?.toDoubleOrNull()
        items.setNumbers(
            id,
            listOfNotNull(
                    count("Chapters")?.let { PARTS to it },
                    count("Views")?.let { VIEWS to it },
                    count("Reviews")?.let { REVIEWS to it },
                    (count("Recommendations") ?: count("Recommended"))?.let { RECOMMENDED to it },
                    count("Currently Reading")?.let { READING to it },
                )
                .toMap(),
        )
    }

    // --- A work's page ---

    /**
     * A work's page: its header — its title, author, rating, statistics and place in the archive's tree — every chapter
     * its menu names and the text of the one it shows. False of a page without the header.
     */
    private fun work(doc: Document, id: String, now: Instant): Boolean {
        val header = doc.selectFirst("div.story-header-two-column")
        val title = header?.selectFirst("div.story-header-left h1")?.text()?.trim()?.ifEmpty { null }
        if (header == null || title == null) return false
        items.upsert(ItemHead(id, Aff().itemUrl(id), title), now)
        author(id, header.selectFirst("div.story-header-author a[href]"))
        val stats =
            header.select("div.story-header-stats > div").associate { d ->
                d.selectFirst("strong")?.text()?.trim()?.removeSuffix(":").orEmpty() to
                    d.selectFirst("span")?.text()?.trim().orEmpty()
            }
        stats["Rating"]?.let { fields.rating(id, it) }
        numbers(id, stats)
        // Its place in the archive's tree, "AtS/BtVS Crossovers › FemmeSlash - Female/Female": the fandom, the kind of
        // its relationships and its pairing read out of it, as of a card
        header.selectFirst("div.story-header-category > span:not(.material-symbols-outlined)")?.let {
            fields.located(id, it.text().split('›'))
        }
        chapters(doc, id)
        return true
    }

    /**
     * The chapters: every one the menu names, without its text, and the text of the one the page shows. A work of one
     * chapter has no menu: its text is its first chapter.
     */
    private fun chapters(doc: Document, id: String) {
        val named =
            doc.select("select.chapter-select option[value]").map { o ->
                val number = o.attr("value").trim()
                PartData(number, (number.toIntOrNull() ?: 0) - 1, chapterTitle(o.text()), null)
            }
        val shownNumber = doc.selectFirst("select.chapter-select option[selected]")?.attr("value")?.trim() ?: FIRST_PART
        val text =
            doc.selectFirst("div.chapter-content-card div.chapter-body")?.let { PageText.of(it) }?.ifBlank { null }
        val shown = text?.let {
            PartData(
                shownNumber,
                (shownNumber.toIntOrNull() ?: 1) - 1,
                doc.selectFirst("div.chapter-content-card h2.chapter-title")?.text()?.trim()?.ifEmpty { null },
                it,
            )
        }
        val parts = named.filter { it.id != shown?.id } + listOfNotNull(shown)
        if (parts.isNotEmpty()) items.saveParts(id, parts)
    }

    // --- Reviews ---

    /** The reviews the page shows, each known by its reviewer and its time — the site gives them no id of their own. */
    private fun reviews(doc: Document, id: String): Boolean {
        val list = doc.select("div.review-entry")
        if (list.isEmpty()) return false
        items.saveReviews(
            id,
            list.mapNotNull { r ->
                val text =
                    r.selectFirst("div.review-content")?.let(PageText::of)?.ifBlank { null } ?: return@mapNotNull null
                val author =
                    r.selectFirst("div.review-author")?.ownText()?.trim()?.ifEmpty { null }
                        ?: r.selectFirst("div.review-author a")?.text()?.trim()?.ifEmpty { null }
                val posted = r.selectFirst("div.review-date")?.ownText()?.trim()
                ReviewData("${author.orEmpty()}@${posted.orEmpty()}", author, null, text, posted?.let(::reviewDate))
            },
        )
        return true
    }

    companion object {
        /** A work's card in a list. */
        const val CARD = "div.story-entry"

        /** A member's profile: "profile.php?id=<number>". */
        private val PROFILE = Regex("profile\\.php\\?(?:[^#]*&)?id=(\\d+)")

        private val CARD_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)
        private val REVIEW_TIME =
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("MMMM d, yyyy 'at' h:mm a")
                .toFormatter(Locale.ENGLISH)

        /** The statistics of a card by their labels: "Published: Nov 10, 2026" → "Published" to "Nov 10, 2026". */
        private fun metaOf(items: List<Element>): Map<String, String> = items.associate { item ->
            item.selectFirst("strong")?.text()?.trim()?.removeSuffix(":").orEmpty() to
                item.text().substringAfter(':').trim()
        }

        /** "Nov 10, 2026" — the site's days are of no zone given: taken as UTC. */
        private fun cardDate(text: String): Instant? = runCatching {
            LocalDate.parse(text.trim(), CARD_DATE).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
            .getOrNull()

        /** "April 20, 2023 at 12:00 AM". */
        private fun reviewDate(text: String): Instant? = runCatching {
            LocalDateTime.parse(text.trim(), REVIEW_TIME).toInstant(ZoneOffset.UTC)
        }
            .getOrNull()

        /** "Chapter 1 - Unimportant" → "Unimportant"; "Chapter 2" stays. */
        private fun chapterTitle(text: String): String =
            text.trim().let { t -> Regex("^Chapter \\d+\\s*-\\s*(.+)$").find(t)?.groupValues?.get(1) ?: t }
    }
}
