package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.SeriesData
import io.github.vlsergey.recommend4me.source.SeriesWork
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.BASE
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * THE SERIES OF FANFICS.ME ("Серия фанфиков"): a series' own page — its title, the lines of its header (the status of
 * its works, its description) and every work of it in its order, on one page —, a list of an author's series or of
 * every series, each with every work of it, and the number of a work in its series its header tells ("#3").
 */
internal class FanficsMeSeries(private val items: ItemStore) {

    /**
     * The page of the series [key]: of a page without its header — an error page, a changed markup — nothing written. A
     * series the author gave no title is titled as the site shows it ("Без названия").
     */
    fun page(doc: Document, key: String) {
        val rows =
            doc.select("div.FicHead div.tr")
                .mapNotNull { row ->
                    val label = clean(row.selectFirst("> .title")?.text().orEmpty()).removeSuffix(":")
                    row.selectFirst("> .content")?.let { label to it }
                }
                .toMap()
        if (rows.isEmpty()) return
        val title = doc.selectFirst("h1")?.let { clean(it.text()) }?.ifEmpty { null } ?: return
        items.keepSeries(
            SeriesData(
                key = key,
                url = "$BASE/serie$key",
                title = title,
                // The header shows a description when there is one: none of a header without it
                description = rows["Описание серии"]?.let(PageText::of).orEmpty(),
                status = rows["Статус"]?.let { status(it.text()) },
                works = works(doc.select("div.FicTable[id^=fic_]")),
                whole = true,
            )
        )
    }

    /** A list of series — an author's, every one — each with its title, description, status and every work of it. */
    fun list(doc: Document) {
        doc.select("div.FicSerie[id^=Serie_]").forEach { serie ->
            val key =
                serie.id().removePrefix("Serie_").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@forEach
            val title = serie.selectFirst("h4.SerieTitle")?.let { clean(it.text()) }?.ifEmpty { null } ?: return@forEach
            items.keepSeries(
                SeriesData(
                    key = key,
                    url = "$BASE/serie$key",
                    title = title,
                    description = serie.selectFirst(".FicSerie_Descr")?.let(PageText::of).orEmpty(),
                    status = serie.select(".ContentTable").firstNotNullOfOrNull { status(it.text()) },
                    works = works(serie.select("div.FicItem[id^=fic_]")),
                    whole = true,
                )
            )
        }
    }

    /** The number of the work [id] in its series [key], as its header's line of the series ends ("#3"). */
    fun place(id: String, key: String, line: Element) {
        NUMBER.find(line.text())?.groupValues?.get(1)?.toIntOrNull()?.let { items.placeInSeries(id, key, it) }
    }

    /** The works of a list, in its order: each by the id of its block ("fic_123"). */
    private fun works(blocks: List<Element>): List<SeriesWork> =
        blocks
            .mapNotNull { b -> b.id().removePrefix("fic_").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } }
            .distinct()
            .map { SeriesWork(it) }

    companion object {
        /** The number after a series' name on a work's header: "Только не с полукровкой #1". */
        private val NUMBER = Regex("#(\\d+)\\s*$")

        /**
         * The standard status of a series by the site's word on its works ("все законченные", "есть не законченные",
         * "есть замороженные"): of a text naming none, none.
         */
        fun status(text: String): String? {
            val t = text.lowercase()
            return when {
                "есть замороженные" in t -> Books.Status.FROZEN.key
                "есть не законченные" in t || "есть незаконченные" in t -> Books.Status.IN_PROGRESS.key
                "все законченные" in t -> Books.Status.FINISHED.key
                else -> null
            }
        }
    }
}
