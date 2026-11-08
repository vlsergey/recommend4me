package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.SeriesData
import io.github.vlsergey.recommend4me.source.SeriesWork
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.BASE
import java.time.Instant
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * THE SERIES OF AUTHOR.TODAY ("Цикл"): a series' own page — its title, status, dates, description and every work of it
 * numbered, on one page —, and an author's list of series, each with its status, description and every work of it in
 * its order. A work's own page tells its number in its series ("#9").
 */
internal class AuthorTodaySeries(private val items: ItemStore) {

    /** The page of the series [key]: of a page without its title, nothing written. */
    fun page(doc: Document, key: String) {
        val title =
            doc.selectFirst("h1.panel-heading")?.text()?.trim()?.let { TITLE.matchEntire(it) }?.groupValues?.get(1)
        if (title.isNullOrBlank()) return
        val about = doc.selectFirst(".collection-description")
        val dates = about?.select(".mb span.hint-top[data-time]").orEmpty()
        items.keepSeries(
            SeriesData(
                key = key,
                url = "$BASE/work/series/$key",
                title = title.trim(),
                // The description follows the status and the dates; none of a series without one
                description =
                    about
                        ?.let { a ->
                            a.children().lastOrNull {
                                it.tagName() == "div" && !it.hasClass("mb") && !it.hasClass("mb-sm")
                            }
                        }
                        ?.let(PageText::of)
                        .orEmpty(),
                status = about?.selectFirst(".mb span.label")?.let { status(it.text()) },
                begunAt = dated(dates, "Опубликован"),
                updatedAt = dated(dates, "Обновление"),
                works =
                    doc.select(".collection-work-list .book-row").mapNotNull { row ->
                        val id = row.selectFirst(".book-title a[href^=/work/]")?.let { workId(it.attr("href")) }
                        id?.let { SeriesWork(it, row.selectFirst(".label-row-index")?.text()?.trim()?.toIntOrNull()) }
                    },
                whole = true,
            )
        )
    }

    /** An author's list of series, each with its title, status, description and every work of it in its order. */
    fun list(doc: Document) {
        doc.select("article.work-series").forEach { article ->
            val link = article.selectFirst("h2.title a[href^=/work/series/]") ?: return@forEach
            val key = SERIES.matchEntire(link.attr("href"))?.groupValues?.get(1) ?: return@forEach
            items.keepSeries(
                SeriesData(
                    key = key,
                    url = "$BASE/work/series/$key",
                    title = link.text().trim().ifEmpty { null },
                    description = article.selectFirst(".description")?.let(PageText::of).orEmpty(),
                    status = article.selectFirst(".mb-sm .hint-top")?.let { status(it.text()) },
                    works =
                        article
                            .select(".bookcard .bookcard-title a[href^=/work/]")
                            .mapNotNull { workId(it.attr("href")) }
                            .distinct()
                            .map { SeriesWork(it) },
                    whole = true,
                )
            )
        }
    }

    /** The number of the work [id] in its series, after the link to it on its page ("#9"). */
    fun place(id: String, block: Element) {
        val link = block.selectFirst("a[href^=/work/series/]") ?: return
        val key = SERIES.matchEntire(link.attr("href").substringBefore('?'))?.groupValues?.get(1) ?: return
        val number = NUMBER.find(link.nextElementSibling()?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
        number?.let { items.placeInSeries(id, key, it) }
    }

    /** The date of the span of the hint [hint] among [dates]: "Опубликован", "Обновление". */
    private fun dated(dates: List<Element>, hint: String): Instant? =
        dates
            .firstOrNull { it.attr("data-hint").trim() == hint }
            ?.attr("data-time")
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }

    companion object {
        /** The heading of a series' page: "Цикл «Вперед в прошлое»". */
        private val TITLE = Regex("Цикл\\s*«(.*)»")

        /** A series' address: "/work/series/30377". */
        val SERIES = Regex("/work/series/(\\d+)/?")

        /** A work's address: "/work/300411". */
        private val WORK_LINK = Regex("/work/(\\d+)/?")

        /** The number of a work in its series: "#9". */
        private val NUMBER = Regex("#(\\d+)")

        private fun workId(href: String): String? =
            WORK_LINK.matchEntire(href.substringBefore('?'))?.groupValues?.get(1)

        /** The standard status of a series by the site's word: "завершен", "не завершен". */
        private fun status(text: String): String? =
            when (text.trim().lowercase()) {
                "завершен" -> Books.Status.FINISHED.key
                "не завершен" -> Books.Status.IN_PROGRESS.key
                else -> null
            }
    }
}
