package io.github.vlsergey.recommend4me.source.molwe

import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.NOTES
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.PARTS
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.READERS
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.REVIEWS
import java.time.Instant
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * A work's own pages: its page — its header, its description with the line of its pairings and characters, its tags and
 * notes, every chapter named, the reviews its page has loaded — and a chapter's page with its text.
 */
internal class MolweWork(private val items: ItemStore) {

    private val fields = MolweFields(items)

    /** The work of a work's page or of a chapter's, written; its id, none of another page. */
    fun read(doc: Document, url: String, now: Instant): List<String> {
        val id = Molwe.workIdOf(url) ?: return emptyList()
        val chapter = CHAPTER_ADDRESS.find(url)?.groupValues?.get(1)
        val read = if (chapter == null) work(doc, id, now) else chapter(doc, id, chapter)
        return if (read) listOf(id) else emptyList()
    }

    // --- A work's page ---

    /** A work's page whole. False of a page without its header. */
    private fun work(doc: Document, id: String, now: Instant): Boolean {
        val hero = doc.selectFirst(Molwe.HERO)
        val title = hero?.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
        if (hero == null || title == null) return false
        val chapters = chapters(doc)
        items.upsert(
            ItemHead(id, Molwe.workUrl(id), title, updatedAt = chapters.mapNotNull { it.publishedAt }.maxOrNull()),
            now,
        )
        hero
            .select("div")
            .firstOrNull { MolweCards.isBadges(it) }
            ?.let { b ->
                fields.badges(id, b.children().map { it.text() })
            }
        header(id, hero, doc)
        MolweCards.cover(hero)?.let { items.setPictures(id, it) }
        description(id, doc)
        if (chapters.isNotEmpty()) items.saveParts(id, chapters)
        doc.selectFirst("#work-chapters h2")?.text()?.let(PageText::number)?.let {
            items.setNumbers(id, mapOf(PARTS to it.toDouble()))
        }
        reviews(doc, id)
        return true
    }

    /** The header's lines: the fandoms, the authors, the readers and the reviews. */
    private fun header(id: String, hero: Element, doc: Document) {
        val lines = hero.selectFirst("ul")?.children().orEmpty()
        val original =
            when {
                doc.selectFirst("nav[aria-label=breadcrumb] a[href=/originals]") != null -> true
                doc.selectFirst("nav[aria-label=breadcrumb] a[href=/fanfics]") != null -> false
                else -> null
            }
        lines
            .firstOrNull { it.text().startsWith("Фандом:") }
            ?.let { line ->
                fields.fandoms(id, line.children().drop(1).flatMap { fandomNames(it) }, original)
            } ?: original?.let { fields.kind(id, it) }
        lines
            .firstOrNull { it.text().startsWith("Автор:") }
            ?.let { line ->
                val authors = line.select("a[href^=/author/]").mapNotNull { MolweCards.author(it) }
                if (authors.isNotEmpty()) items.setFacet(id, AUTHOR, authors)
            }
        // The line of the counts: a group each, its icon, its number, its caption — "👁 189 читателей"
        val counts =
            lines
                .flatMap { it.children() }
                .filter { it.childrenSize() == 2 }
                .associate { group -> group.ownText().trim() to group.child(1).text().let(PageText::number) }
        items.setNumbers(
            id,
            listOfNotNull(
                    counts[READERS_CAPTION]?.let { READERS to it.toDouble() },
                    counts[REVIEWS_CAPTION]?.let { REVIEWS to it.toDouble() },
                )
                .toMap(),
        )
    }

    /**
     * The work's description and the blocks under it, each by its label: the line of pairings and characters, the tags,
     * the notes.
     */
    private fun description(id: String, doc: Document) {
        val blocks = doc.select("#work-description > div")
        val labelled =
            blocks
                .mapNotNull { b -> b.children().firstOrNull()?.ownText()?.trim()?.let { it to b } }
                .filter { (label, _) -> label in LABELS }
                .associate { (label, b) -> label to b.child(1) }
        blocks
            .firstOrNull { b -> b.children().firstOrNull()?.ownText()?.trim() !in LABELS }
            ?.let { items.setTexts(id, mapOf(ANNOTATION to PageText.of(it))) }
        labelled[CAST]?.let { block ->
            fields.cast(id, block.children().map { it.text() }.filter { it.trim() != NO_PAIRINGS })
        }
        labelled[TAGS]?.let { block -> fields.tags(id, block.children().map { it.text() }) }
        labelled[NOTE]?.let { items.setTexts(id, mapOf(NOTES to PageText.of(it))) }
    }

    /** Every chapter the page names, without its text: its id, number, title and day. */
    private fun chapters(doc: Document): List<PartData> =
        doc.select("#work-chapters a[href*=/read/]").mapNotNull { a ->
            val part = CHAPTER_ADDRESS.find(a.absUrl("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val spans = a.select("span").map { it.ownText().trim() }
            val number = spans.firstOrNull()?.toIntOrNull()
            PartData(
                part,
                (number ?: 0) - 1,
                spans.getOrNull(1)?.ifEmpty { null },
                null,
                spans.firstOrNull { DAY.matches(it) }?.let(PageText::russianDate),
            )
        }

    /**
     * The reviews the page has loaded — none written before they are: its script loads them after the page — each by
     * the site's own id.
     */
    private fun reviews(doc: Document, id: String) {
        val shown = doc.select("#work-reviews $REVIEW")
        if (shown.isEmpty()) return
        items.saveReviews(
            id,
            shown.mapNotNull { r ->
                val own = r.select("p").filter { p -> p.closest(REVIEW) == r }
                val text = own.joinToString("\n\n") { PageText.of(it) }.ifBlank { null } ?: return@mapNotNull null
                val author = r.select("div").firstOrNull { it.childrenSize() == 0 && it.text().isNotBlank() }
                ReviewData(r.attr(REVIEW_ID), author?.text()?.trim(), null, text, null)
            },
        )
    }

    // --- A chapter's page ---

    /**
     * A chapter's page: its title, the reader's heading, and its text, of a work known; its place in the work is told
     * by the work's page alone. False of a page without its text.
     */
    private fun chapter(doc: Document, id: String, part: String): Boolean {
        val title = doc.selectFirst("[data-no-chrome-toggle] > div > p")?.text()?.trim()?.ifEmpty { null }
        val text =
            doc.select("p[id^=paragraph-]").map { PageText.of(it) }.filter { it.isNotBlank() }.joinToString("\n\n")
        if (items.find(id) == null || text.isBlank()) return false
        items.saveParts(id, listOf(PartData(part, UNKNOWN_PLACE, title, text)))
        return true
    }

    companion object {
        /** A review on a work's page. */
        const val REVIEW = "div[data-comment-id]"

        /** The attribute of a review's own id. */
        const val REVIEW_ID = "data-comment-id"

        /** A chapter's page: ".../read/<its id>". */
        private val CHAPTER_ADDRESS = Regex("/read/([0-9a-fA-F-]+)")

        /** The place of a part its page does not tell: a known part keeps its own ([PartData.position]). */
        private const val UNKNOWN_PLACE = -1

        /** A chapter's day, "06.11.2026". */
        private val DAY = Regex("^\\d{2}\\.\\d{2}\\.\\d{4}$")

        /** The captions of the header's counts. */
        private const val READERS_CAPTION = "читателей"
        private const val REVIEWS_CAPTION = "отзывов"

        private const val CAST = "Пэйринг и персонажи:"
        private const val TAGS = "Метки"
        private const val NOTE = "Примечание:"
        private val LABELS = setOf(CAST, TAGS, NOTE)

        /** The site's words of a line of pairings and characters of none. */
        private const val NO_PAIRINGS = "Пэйринги не указаны"

        /**
         * The fandoms of the header's line, each an element of its own: its link's name, or — a fandom the site has no
         * page of — its last text, the separator before it a text of its own; the line's own text of a single one of no
         * element ("Ориджинал").
         */
        private fun fandomNames(value: Element): List<String> =
            if (value.childrenSize() == 0) listOf(value.text())
            else
                value.children().map { f -> f.selectFirst("a")?.text() ?: f.textNodes().lastOrNull()?.text().orEmpty() }
    }
}
