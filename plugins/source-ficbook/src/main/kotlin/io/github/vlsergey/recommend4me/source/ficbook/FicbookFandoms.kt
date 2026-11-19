package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.FANDOM
import org.jsoup.nodes.Document

/** What the site says of its fandoms: of a fandom's own page, the list of its works, and of its catalogue. */
internal class FicbookFandoms(private val items: ItemStore) {

    /**
     * A fandom's own page, [key] its address after "/fanfiction/": its heading names it as the works do — «Роулинг
     * Джоан «Гарри Поттер»» — and adds what it is of, in a line of its own: the original's name, its kind and years —
     * «/ Rowling Joanne «Harry Potter» / Серия книг, 1997 - 2007». That line is the fandom's description, blank when
     * the heading has none: the page read, the site saying nothing more. A page without the heading changes nothing.
     */
    fun page(doc: Document, key: String) {
        val heading = doc.selectFirst("h1.heading") ?: return
        val said = heading.selectFirst("span")?.text()?.trim()?.removePrefix("/")?.trim().orEmpty()
        items.describeFacetValues(FANDOM, mapOf(key to said))
    }

    /**
     * A page of the site's catalogue of the fandoms of a group, [group] its address after "/fanfiction/" — "musicals",
     * "/fanfiction/musicals?p=19": every fandom of it by its address — the group and its slug, "musicals/elizabet" —
     * named as the works name it, «Элизабет», and described by the line under it, what it is of, as its own page's
     * heading says it: «Elisabeth / 1992-2016». A fandom's entry of no such link is left out.
     */
    fun catalogue(doc: Document, group: String) {
        val names = HashMap<String, String>()
        val descriptions = HashMap<String, String>()
        doc.select("div.mb-15").forEach { entry ->
            val link = entry.selectFirst("> div > a[href^=/fanfiction/$group/]") ?: return@forEach
            val key = link.attr("href").removePrefix("/fanfiction/").substringBefore('?').trimEnd('/')
            val name = link.text().trim()
            if (key.count { it == '/' } != 1 || name.isEmpty()) return@forEach
            names[key] = name
            entry.selectFirst("> div.text-muted")?.text()?.trim()?.ifEmpty { null }?.let { descriptions[key] = it }
        }
        if (names.isNotEmpty()) items.nameFacetValues(FANDOM, names)
        if (descriptions.isNotEmpty()) items.describeFacetValues(FANDOM, descriptions)
    }
}
