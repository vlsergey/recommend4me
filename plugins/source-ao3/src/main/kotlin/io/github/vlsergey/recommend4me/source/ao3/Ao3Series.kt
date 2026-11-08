package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.SeriesData
import io.github.vlsergey.recommend4me.source.SeriesWork
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.BASE
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * THE SERIES OF ARCHIVE OF OUR OWN: a series' own page — its title, its dates, its description, whether it is complete
 * and its works, twenty a page, each with its number ("Part 3 of") — and the number of a work in its series a card or a
 * work's page tells. A page of a series is never its whole list: a work the archive shows only to the logged in, or on
 * another page, is not on it.
 */
internal class Ao3Series(private val items: ItemStore) {

    /** The page of the series [key]: of a page without its heading, nothing written. */
    fun page(doc: Document, key: String) {
        val title = doc.selectFirst("#main h2.heading")?.text()?.trim()?.ifEmpty { null } ?: return
        val meta = doc.selectFirst("dl.series.meta")
        val lines =
            meta
                ?.children()
                ?.filter { it.tagName() == "dt" }
                ?.mapNotNull { dt -> dt.nextElementSibling()?.let { dt.text().trim().removeSuffix(":") to it } }
                ?.toMap()
                .orEmpty()
        val complete =
            lines["Stats"]?.select("dt")?.firstOrNull { it.text().trim() == "Complete:" }?.nextElementSibling()
        items.keepSeries(
            SeriesData(
                key = key,
                url = "$BASE/series/$key",
                title = title,
                description = meta?.let { lines["Description"]?.let(PageText::of).orEmpty() },
                status =
                    when (complete?.text()?.trim()) {
                        "Yes" -> Books.Status.FINISHED.key
                        "No" -> Books.Status.IN_PROGRESS.key
                        else -> null
                    },
                begunAt = lines["Series Begun"]?.let(::dated),
                updatedAt = lines["Series Updated"]?.let(::dated),
                works =
                    doc.select("ul.series.work.index > li.work[id^=work_]").map { card ->
                        SeriesWork(card.id().removePrefix("work_"), number(card, key))
                    },
            )
        )
    }

    /** The number of the work [id] in the series of the link [a], as the text around it says: "Part 3 of". */
    fun place(id: String, key: String, a: Element) {
        a.parent()
            ?.let { PART.find(it.text())?.groupValues?.get(1)?.toIntOrNull() }
            ?.let {
                items.placeInSeries(id, key, it)
            }
    }

    /** The number of a card's work in the series [key]: its line of that series, "Part 3 of". */
    private fun number(card: Element, key: String): Int? =
        card
            .select("ul.series li")
            .firstOrNull { li ->
                li.select("a[href^=/series/]").any { SERIES.matchEntire(it.attr("href"))?.groupValues?.get(1) == key }
            }
            ?.let { PART.find(it.text())?.groupValues?.get(1)?.toIntOrNull() }

    /** A day of the archive: "2023-01-02". */
    private fun dated(dd: Element): Instant? = runCatching {
        LocalDate.parse(dd.text().trim()).atStartOfDay().toInstant(ZoneOffset.UTC)
    }
        .getOrNull()

    companion object {
        /** A series' address: "/series/5585796". */
        val SERIES = Regex("/series/(\\d+)/?")

        /** The number of a work in a series: "Part 6 of". */
        private val PART = Regex("Part\\s+(\\d+)\\s+of")
    }
}
