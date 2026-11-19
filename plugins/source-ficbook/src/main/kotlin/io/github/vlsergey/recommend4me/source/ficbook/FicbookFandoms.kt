package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.FANDOM
import org.jsoup.nodes.Document

/** What the site says of its fandoms: of a fandom's own page, the list of its works. */
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
}
