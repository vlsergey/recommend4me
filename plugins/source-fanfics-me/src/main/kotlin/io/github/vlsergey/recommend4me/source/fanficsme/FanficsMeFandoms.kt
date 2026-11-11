package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.FANDOM
import org.jsoup.nodes.Document

/**
 * What the site says its fandoms are of: the line under a fandom's name — «По серии книг Джоан Роулинг "Harry Potter",
 * их продолжениям и экранизациям» — at the head of its every page, and under every fandom of the rating of the fandoms.
 */
internal class FanficsMeFandoms(private val items: ItemStore) {

    /**
     * The head of a page of the fandom [key] — its news, its works, its encyclopedia: the line under its name, blank
     * when there is none. A page without the head changes nothing.
     */
    fun head(doc: Document, key: String) {
        val title = doc.selectFirst(".FandomHead_Title") ?: return
        items.describeFacetValues(FANDOM, mapOf(key to title.selectFirst("span")?.let { clean(it.text()) }.orEmpty()))
    }

    /** A page of the rating of the fandoms: every fandom listed, by the link of its name, and the line under it. */
    fun rating(doc: Document) {
        val described =
            doc.select(".FandomTable").mapNotNull { row ->
                val key =
                    row.selectFirst(".FandomTable_Title a[href]")
                        ?.attr("href")
                        ?.let { FANDOM_LINK.matchEntire(it) }
                        ?.groupValues
                        ?.get(1) ?: return@mapNotNull null
                key to row.selectFirst(".FandomTable_Description")?.let { clean(it.text()) }.orEmpty()
            }
        if (described.isNotEmpty()) items.describeFacetValues(FANDOM, described.toMap())
    }

    private companion object {
        /** A fandom's own page: "/fandom50". */
        val FANDOM_LINK = Regex("/fandom(\\d+)/?")
    }
}
