package io.github.vlsergey.recommend4me.source.aff

import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.PARTS
import io.github.vlsergey.recommend4me.source.aff.Aff.Companion.VIEWS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * AN AUTHOR'S PAGE ON THE SITE (members.adult-fanfiction.org/profile.php?id=<number>): the works they wrote, of one
 * archive at a time, a card each ([CARD]) — loaded by the page's script, sent by the extension as the user sees them. A
 * card tells the work's title, its days, its chapters and views, its description and its codes; its author is the
 * page's. Its category it tells by its last node alone: the archive's card tells the whole of it, not written here.
 */
internal class AffProfiles(private val items: ItemStore, private val fields: AffFields) {

    /** The works of the author's page [doc] at [url], written; their ids. None of another page. */
    fun read(doc: Document, url: String, now: Instant): List<String> {
        val author = PROFILE.find(url)?.groupValues?.get(1)
        val name = doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null }
        return if (author == null) emptyList()
        else doc.select(CARD).mapNotNull { card -> card(card, FacetValue(author, name ?: author), now) }
    }

    /** The work of a card, written; its id. None of a card that names no work. */
    private fun card(card: Element, author: FacetValue, now: Instant): String? {
        val link = card.selectFirst(LINK)
        val id = link?.let { Aff().itemIdOf(it.absUrl("href")) }
        val title = link?.text()?.trim()?.ifEmpty { null }
        return if (id == null || title == null) null else id.also { write(card, it, title, author, now) }
    }

    private fun write(card: Element, id: String, title: String, author: FacetValue, now: Instant) {
        val meta = card.select("div.story-card-meta-item").map { it.text().trim() }
        fun dated(label: String) =
            meta.firstNotNullOfOrNull { m -> m.substringAfter("$label:", "").trim().ifEmpty { null } }?.let(::date)
        items.upsert(ItemHead(id, Aff().itemUrl(id), title, updatedAt = dated("Updated") ?: dated("Created")), now)
        fields.archive(id, id.substringBeforeLast('-'))
        items.setFacet(id, AUTHOR, listOf(author))
        fun counted(word: String) =
            meta
                .firstNotNullOfOrNull { m ->
                    // After the word of the item's icon: "menu_book 3 chapters"
                    Regex("([\\d,]+)\\s+$word\\b", RegexOption.IGNORE_CASE).find(m)?.groupValues?.get(1)
                }
                ?.replace(",", "")
                ?.toDoubleOrNull()
        items.setNumbers(
            id,
            listOfNotNull(counted("chapters?")?.let { PARTS to it }, counted("views?")?.let { VIEWS to it }).toMap(),
        )
        card.selectFirst("div.story-card-description")?.let { items.setTexts(id, mapOf(ANNOTATION to PageText.of(it))) }
        fields.codes(id, card.select("div.story-card-tags span.story-tag").map { it.text().trim() })
    }

    companion object {
        /** A work's card on an author's page, and the link to its work in it. */
        const val CARD = "div.story-card"
        const val LINK = "div.story-card-title a[href]"

        /** An author's page: "profile.php?id=<number>". */
        private val PROFILE = Regex("^https?://members\\.adult-fanfiction\\.org/profile\\.php\\?(?:[^#]*&)?id=(\\d+)")

        private val DAY = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

        /** "July 22, 2023" — the site's days are of no zone given: taken as UTC. */
        private fun date(text: String): Instant? = runCatching {
            LocalDate.parse(text.trim(), DAY).atStartOfDay(ZoneOffset.UTC).toInstant()
        }
            .getOrNull()
    }
}
