package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.SeriesData
import io.github.vlsergey.recommend4me.source.SeriesWork
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.BASE
import io.github.vlsergey.recommend4me.source.ficbook.FicbookPages.Companion.workId
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * THE SERIES OF FICBOOK ("Серия"): a series' own page — its title, its description and every work of it as a card, in
 * the order its author gave them, every one when it lists as many as it counts —, and an author's list of series, each
 * with its description and every work of it. The site numbers no work: its place is its order there. It tells no status
 * of a series.
 */
internal class FicbookSeries(private val items: ItemStore) {

    /** The page of the series [key]: of a page without its title, nothing written. */
    fun page(doc: Document, key: String) {
        val heading = doc.selectFirst("main h1.heading") ?: return
        val title = TITLE.matchEntire(heading.text().trim())?.groupValues?.get(1)?.trim()?.ifEmpty { null } ?: return
        // The description stands under the title, before the count of the works; none of a series without one
        val about = heading.parent()?.parent()?.parent()
        val description = about?.selectFirst("div.text-muted.word-break")?.let(PageText::of).orEmpty()
        val works = works(doc.select("main section > article.fanfic-inline .fanfic-inline-title a[href^=/readfic/]"))
        // "В серии 31 работа": the page is every work of the series when it lists as many as it counts (owner,
        // 2026-11-21: "the series of several pages rewritten wrongly")
        val count =
            about?.select("div")?.firstNotNullOfOrNull { COUNT.matchEntire(it.ownText().trim()) }?.groupValues?.get(1)
        items.keepSeries(
            SeriesData(
                key = key,
                url = "$BASE/series/$key",
                title = title,
                description = description,
                works = works,
                whole = count?.toIntOrNull() == works.size,
            )
        )
    }

    /** An author's list of series, each with its title, description and every work of it. */
    fun list(doc: Document) {
        doc.select(".series-card").forEach { card ->
            val link = card.selectFirst("h3.series-title a[href^=/series/]") ?: return@forEach
            val key = SERIES.matchEntire(link.attr("href"))?.groupValues?.get(1) ?: return@forEach
            items.keepSeries(
                SeriesData(
                    key = key,
                    url = "$BASE/series/$key",
                    title = link.text().trim().ifEmpty { null },
                    description = card.selectFirst(".series-description")?.let(PageText::of).orEmpty(),
                    works = works(card.select("a.series-fic[href^=/readfic/]")),
                    whole = true,
                )
            )
        }
    }

    /** The works the links lead to, in their order, of those written: a work never seen is not of a series yet. */
    private fun works(links: List<Element>): List<SeriesWork> {
        val sites = links.mapNotNull { workId(it.attr("href")) }.distinct()
        val ids = items.idsOf(sites)
        return sites.mapNotNull { ids[it] }.map { SeriesWork(it) }
    }

    companion object {
        /** The heading of a series' page: "Серия «Изгиб Вселенной»". */
        private val TITLE = Regex("Серия\\s*«(.*)»")

        /** The count of a series' works: "В серии 31 работа". */
        private val COUNT = Regex("В серии (\\d+) работ\\S*")

        /** A series' address: "/series/20989". */
        val SERIES = Regex("/series/(\\d+)/?")
    }
}
