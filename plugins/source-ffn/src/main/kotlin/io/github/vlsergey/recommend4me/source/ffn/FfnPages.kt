package io.github.vlsergey.recommend4me.source.ffn

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.ffn.Ffn.Companion.AUTHOR
import java.time.Instant
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Reads the pages of fanfiction.net and writes what they say: the cards of a list — a fandom's, a crossover's, a
 * search, an author's works and favourites — a work's page with its header, every chapter named and the text of the one
 * it shows, and its reviews.
 */
internal class FfnPages(private val items: ItemStore) {

    private val fields = FfnFields(items)

    fun read(page: CapturedPage): List<String> {
        val doc = Jsoup.parse(page.html, page.url)
        val touched = LinkedHashSet<String>()
        touched += cards(doc, page.url, page.capturedAt)
        val id = Ffn.workIdOf(page.url)
        when {
            id == null -> Unit
            STORY.containsMatchIn(page.url) && story(doc, id, page.url, page.capturedAt) -> touched += id
            REVIEWS_PAGE.containsMatchIn(page.url) && reviews(doc, id) -> touched += id
        }
        return touched.toList()
    }

    // --- Cards of a list ---

    private fun cards(doc: Document, url: String, now: Instant): List<String> {
        val context = ListContext.of(doc, url)
        return doc.select(CARD).mapNotNull { card(it, context, now) }
    }

    /** The work of a card, written; its id. None of a card that names no work. */
    private fun card(card: Element, context: ListContext, now: Instant): String? {
        val link = card.selectFirst(LINK)
        val id = link?.let { Ffn.workIdOf(it.absUrl("href")) }
        val title = link?.text()?.trim()?.ifEmpty { null }
        if (id == null || title == null) return null
        val meta = card.selectFirst("div.xgray")
        items.upsert(ItemHead(id, Ffn.workUrl(id), title, updatedAt = meta?.let(::updated)), now)
        context.category?.let { fields.category(id, it) }
        if (context.fandoms.isNotEmpty()) fields.fandoms(id, context.fandoms)
        meta?.let { fields.line(id, FfnLine.of(it.text())) }
        val author = card.selectFirst("a[href^=/u/]")?.let(::author) ?: context.author
        author?.let { items.setFacet(id, AUTHOR, listOf(it)) }
        card.selectFirst("div.z-indent")?.let { summary ->
            val own = summary.textNodes().joinToString(" ") { it.text() }.trim()
            items.setTexts(id, mapOf(ANNOTATION to own))
        }
        card.selectFirst("img.cimage")?.let { items.setPictures(id, cover(it.attr("src"))) }
        return id
    }

    // --- A work's page ---

    /** A work's page: its header, every chapter named and the text of the one it shows. False of one without it. */
    private fun story(doc: Document, id: String, url: String, now: Instant): Boolean {
        val top = doc.selectFirst("#profile_top")
        val title = top?.selectFirst("b.xcontrast_txt")?.text()?.trim()?.ifEmpty { null }
        if (top == null || title == null) return false
        val meta = top.selectFirst("span.xgray")
        items.upsert(ItemHead(id, Ffn.workUrl(id), title, updatedAt = meta?.let(::updated)), now)
        top.selectFirst("a[href^=/u/]")?.let(::author)?.let { items.setFacet(id, AUTHOR, listOf(it)) }
        top.selectFirst("> div.xcontrast_txt")?.let { items.setTexts(id, mapOf(ANNOTATION to PageText.of(it))) }
        meta?.let { fields.line(id, FfnLine.of(it.text())) }
        located(doc, id)
        val cover = doc.selectFirst("#img_large img")?.let { it.attr("data-original").ifEmpty { it.attr("src") } }
        cover?.let { items.setPictures(id, cover(it)) }
        chapters(doc, id, STORY.find(url)?.groupValues?.get(2)?.ifEmpty { null } ?: FIRST_PART)
        return true
    }

    /**
     * Where the work is, by the links over its header: a section and its fandom ("Books", "Harry Potter"), or a
     * crossover's one link, "Harry Potter + Pirates of the Caribbean Crossover", its two fandoms.
     */
    private fun located(doc: Document, id: String) {
        val links = doc.select("#pre_story_links a")
        val crossover = links.firstOrNull { "_Crossovers/" in it.attr("href") }
        if (crossover != null) {
            fields.fandoms(id, crossover.text().trim().removeSuffix(" Crossover").split(" + "))
        } else if (links.size >= 2) {
            fields.category(id, links.first()!!.text().trim())
            fields.fandoms(id, listOf(links.last()!!.text().trim()))
        }
    }

    /** Every chapter the menu names, "2. A Different Life", and the text of the one the page shows. */
    private fun chapters(doc: Document, id: String, shown: String) {
        val named =
            doc.select("#chap_select").firstOrNull()?.select("option[value]").orEmpty().map { o ->
                val number = o.attr("value").trim()
                PartData(number, (number.toIntOrNull() ?: 0) - 1, o.text().trim().removePrefix("$number.").trim(), null)
            }
        val text = doc.selectFirst("#storytext")?.let { PageText.of(it) }?.ifBlank { null }
        val shownPart = text?.let {
            PartData(shown, (shown.toIntOrNull() ?: 1) - 1, named.firstOrNull { p -> p.id == shown }?.title, it)
        }
        val parts = named.filter { it.id != shownPart?.id } + listOfNotNull(shownPart)
        if (parts.isNotEmpty()) items.saveParts(id, parts)
    }

    // --- Reviews ---

    /** The reviews the page shows, of a work known: each by the site's own id, its reviewer, its day. */
    private fun reviews(doc: Document, id: String): Boolean {
        val rows = doc.select("#gui_table1 tbody td")
        if (items.find(id) == null || rows.isEmpty()) return false
        items.saveReviews(
            id,
            rows.mapNotNull { td ->
                val reviewId =
                    td.select("a[href*=reviewid=]").firstNotNullOfOrNull {
                        REVIEW_ID.find(it.attr("href"))?.groupValues?.get(1)
                    }
                val text = td.selectFirst("> div")?.let { PageText.of(it) }?.ifBlank { null }
                if (reviewId == null || text == null) return@mapNotNull null
                val reviewer =
                    td.selectFirst("> a[href^=/u/]")?.text()?.trim()
                        ?: td.textNodes().map(TextNode::text).map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ReviewData(reviewId, reviewer, null, text, td.selectFirst("span[data-xutime]")?.let(::moment))
            },
        )
        return true
    }

    /**
     * What a list's page tells of every card on it: the section and the fandom of a fandom's list — the line over it
     * "Books › Harry Potter", its fandom the text after the arrow — the two fandoms of a crossover's, linked over it;
     * the author of an author's page.
     */
    private class ListContext(val category: String?, val fandoms: List<String>, val author: FacetValue?) {
        companion object {
            fun of(doc: Document, url: String): ListContext {
                val inner = doc.selectFirst("#content_wrapper_inner")
                val arrow = inner?.selectFirst("> span.icon-chevron-right")
                val crossover = inner?.select("> a[href^=/crossovers/]").orEmpty().map { it.text().trim() }
                val fandom = (arrow?.nextSibling() as? TextNode)?.text()?.trim()?.ifEmpty { null }
                val author =
                    USER_PAGE.find(url)?.groupValues?.get(1)?.let { key ->
                        inner?.selectFirst("> span")?.text()?.trim()?.ifEmpty { null }?.let { FacetValue(key, it) }
                    }
                return ListContext(
                    category = arrow?.previousElementSibling()?.takeIf { it.tagName() == "a" }?.text()?.trim(),
                    fandoms = if (crossover.size == 2) crossover else listOfNotNull(fandom),
                    author = author,
                )
            }
        }
    }

    companion object {
        /** A work's card in a list. */
        const val CARD = "div.z-list"

        /** The link to the work in its card. */
        const val LINK = "a.stitle"

        /** The chapter a work's page shows when its address names none. */
        const val FIRST_PART = "1"

        /** A work's page: "/s/<id>/<chapter>/<title>". */
        private val STORY = Regex("/s/(\\d+)(?:/(\\d*))?")

        /** A work's reviews: "/r/<id>/<chapter>/<page>/". */
        private val REVIEWS_PAGE = Regex("/r/\\d+")

        /** A member's own page: "/u/<id>/<name>". */
        private val USER_PAGE = Regex("^https?://(?:www\\.)?fanfiction\\.net/u/(\\d+)")

        /** A link to a member: "/u/<id>/<name>". */
        private val USER_LINK = Regex("^/u/(\\d+)")

        /** The link to report a review, "report_review.php?reviewid=<id>": the review's own id. */
        private val REVIEW_ID = Regex("reviewid=(\\d+)")

        /** A cover by the address of any of its sizes, "/image/7587904/75/": of its largest; none of no cover. */
        private val IMAGE = Regex("/image/(\\d+)/")

        fun cover(src: String): List<String> =
            IMAGE.find(src)?.groupValues?.get(1)?.let { listOf("${Ffn.HOME}/image/$it/180/") } ?: emptyList()

        /** A member by a link to them: their number, their name. */
        fun author(a: Element): FacetValue? =
            USER_LINK.find(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, a.text().trim()) }

        /** A moment the site tells by its attribute, in seconds: "data-xutime". */
        private fun moment(span: Element): Instant? =
            span.attr("data-xutime").toLongOrNull()?.let(Instant::ofEpochSecond)

        /** When the line says the work was updated — of a work never updated, published. */
        private fun updated(line: Element): Instant? {
            val days = line.select("span[data-xutime]")
            val updated = days.firstOrNull {
                (it.previousSibling() as? TextNode)?.text()?.trimEnd()?.endsWith("Updated:") == true
            }
            return (updated ?: days.lastOrNull())?.let(::moment)
        }
    }
}
