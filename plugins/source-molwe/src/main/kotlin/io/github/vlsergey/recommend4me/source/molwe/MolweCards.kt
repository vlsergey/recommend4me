package io.github.vlsergey.recommend4me.source.molwe

import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.READERS
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.REVIEWS
import io.github.vlsergey.recommend4me.source.molwe.Molwe.Companion.WORDS
import java.time.Instant
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The cards of the works of a list — the catalogue's, a fandom's, a tag's, an author's, the works like one — in either
 * view of the list the reader chooses. A tile tells a work's badges, its first fandom, its description and its readers;
 * a row tells its author, its size, its tags and its reviews too. Each writes what it shows as elements of their own: a
 * fandom of a card is the first of the work's alone, written of a work whose fandoms are not known yet; the characters
 * and pairings a row runs together in one text are read of the work's page, nothing picked out of a text.
 */
internal class MolweCards(private val items: ItemStore) {

    private val fields = MolweFields(items)

    /** Every card of the page written; the ids of their works. */
    fun read(doc: Document, url: String, now: Instant): List<String> {
        val author = authorOf(doc, url)
        return doc.select(CARD).mapNotNull { card(it, author, now) }
    }

    /** The work of a card, written; its id. None of a card that names no work. */
    private fun card(card: Element, author: FacetValue?, now: Instant): String? {
        val link = card.selectFirst(LINK)
        val id = link?.let { Molwe.workIdOf(it.absUrl("href")) }
        val title =
            card.select(LINK).map { it.text().trim() }.firstOrNull { it.isNotEmpty() }
                ?: link?.attr("aria-label")?.trim()?.ifEmpty { null }
        if (link == null || id == null || title == null) return null
        items.upsert(ItemHead(id, Molwe.workUrl(id), title), now)
        fields.kind(id, link.attr("href").startsWith("/originals/"))
        author?.let { items.setFacet(id, AUTHOR, listOf(it)) }
        write(id, card)
        return id
    }

    /** What every card tells of the work [id]: its badges, its fandom, its cover, numbers and description. */
    private fun write(id: String, card: Element) {
        val badges = card.select("div").firstOrNull { isBadges(it) }
        badges?.let { fields.badges(id, it.children().map(Element::text)) }
        val fandom = badges?.nextElementSibling()?.selectFirst("span[title]")?.attr("title")?.trim()
        if (fandom != null && items.facets(id)[FANDOM].isNullOrEmpty()) fields.fandoms(id, listOf(fandom), null)
        cover(card)?.let { items.setPictures(id, it) }
        numbers(id, card)
        row(id, card)
        (card.selectFirst("p[id^=work-cover-desc-]") ?: rowDescription(card))?.let {
            items.setTexts(id, mapOf(ANNOTATION to PageText.of(it)))
        }
    }

    /**
     * What a row of the list tells besides a tile, each of an element of its own: its author, its size, its tags. Its
     * characters and pairings it writes as one text, its names run together by commas: they are read of the work's
     * page, each an element of its own there.
     */
    private fun row(id: String, card: Element) {
        card.select("div:has(> span:containsOwn(Автор:)) > a[href^=/author/]").let { authors ->
            if (authors.isNotEmpty()) items.setFacet(id, AUTHOR, authors.mapNotNull { author(it) })
        }
        card.selectFirst("p:containsOwn(Размер:) > b")?.text()?.let(PageText::number)?.let {
            items.setNumbers(id, mapOf(WORDS to it.toDouble()))
        }
        card
            .selectFirst("p:containsOwn(Фандом:)")
            ?.parent()
            ?.children()
            ?.firstOrNull { it.tagName() == "div" }
            ?.let {
                fields.tags(id, it.children().map(Element::text))
            }
    }

    /** The readers of a card, and its reviews of a row. */
    private fun numbers(id: String, card: Element) {
        fun count(label: String) =
            card.selectFirst("b[aria-label^=\"$label:\"]")?.text()?.let(PageText::number)?.toDouble()
        items.setNumbers(
            id,
            listOfNotNull(count("Читатели")?.let { READERS to it }, count("Отзывы")?.let { REVIEWS to it }).toMap(),
        )
    }

    companion object {
        /** A work's card in a list. */
        const val CARD = "article:has(a[href^=\"/fanfics/\"]), article:has(a[href^=\"/originals/\"])"

        /** The link to the work in its card. */
        const val LINK = "a[href^=\"/fanfics/\"], a[href^=\"/originals/\"]"

        /** The captions of a row's lines of the site's fields, "Фандом:": the lines before its description. */
        private val LABELS = listOf("Фандом", "Персонажи", "Пейринги", "Опубликовано", "Размер")

        /** The div of the badges over a cover: spans alone, an age rating first ("16+"). */
        fun isBadges(div: Element): Boolean =
            div.childrenSize() > 0 &&
                div.children().all { it.tagName() == "span" } &&
                Regex("^\\d+\\+$").matches(div.child(0).text().trim())

        /** The cover of a card or of a header: its picture's own address; none of the site's picture of no cover. */
        fun cover(scope: Element): List<String>? {
            val src =
                scope
                    .select("img")
                    .firstOrNull { it.parents().none { p -> p.tagName() == "li" } }
                    ?.absUrl("src")
                    ?.ifEmpty { null } ?: return null
            val own =
                Regex("[?&]url=([^&]+)").find(src)?.groupValues?.get(1)?.let {
                    java.net.URLDecoder.decode(it, Charsets.UTF_8)
                } ?: src
            return if ("/images/fanfics/empty/" in own) emptyList() else listOf(own)
        }

        /** The author of a link to their page: their number there, their name. */
        fun author(a: Element): FacetValue? {
            val key = Regex("/author/(\\d+)").find(a.attr("href"))?.groupValues?.get(1) ?: return null
            return FacetValue(key, a.text().trim())
        }

        /** The author of an author's page: of every card on it. */
        private fun authorOf(doc: Document, url: String): FacetValue? {
            val key = Regex("^https?://molwe\\.ru/author/(\\d+)").find(url)?.groupValues?.get(1)
            val name = doc.selectFirst("main h1")?.text()?.trim()?.ifEmpty { null }
            return if (key == null || name == null) null else FacetValue(key, name)
        }

        /** A row's description: the paragraph after the lines of its fields. */
        private fun rowDescription(card: Element): Element? =
            card.selectFirst("p:containsOwn(Фандом:)")?.parent()?.children()?.lastOrNull { p ->
                p.tagName() == "p" && LABELS.none { p.text().startsWith("$it:") }
            }
    }
}
