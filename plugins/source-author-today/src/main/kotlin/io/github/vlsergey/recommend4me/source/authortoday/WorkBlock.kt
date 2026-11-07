package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.PageText
import java.time.Instant
import org.jsoup.nodes.Element

/**
 * The block of a page that shows a work — its card in a list, or the panel of its own page — read: both show the work's
 * authors, status, series, size, counters and day in the same markup.
 */
internal class WorkBlock(private val e: Element) {

    /** The authors the links of [selector] name, by their logins. */
    fun authors(selector: String): List<FacetValue> =
        e.select(selector).mapNotNull { a ->
            val login = AUTHOR_LOGIN.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            FacetValue(login, a.text().trim())
        }

    fun status(): FacetValue? {
        val text =
            e.selectFirst(".book-status-icon")?.parent()?.text()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                ?: return null
        // The standard status; another as the site writes it, for the application to tell of
        return STATUSES[text]?.let { FacetValue(it.key, it.modelName) }
            ?: FacetValue(text, text.replaceFirstChar { it.uppercase() })
    }

    fun series(): FacetValue? =
        e.selectFirst("a[href^=/work/series/]")?.let { a ->
            FacetValue(a.attr("href").removePrefix("/work/series/").substringBefore('?'), a.text().trim())
        }

    /** The number of characters: "95 898 зн.". */
    fun size(): Double? = e.selectFirst("[data-hint^=Размер]")?.text()?.let(PageText::number)?.toDouble()

    /** A counter of the card: "Просмотры · 14 811". */
    fun stat(name: String): Double? =
        e.selectFirst("[data-hint^=$name]")?.attr("data-hint")?.substringAfter('·')?.let(PageText::number)?.toDouble()

    /** When the work was updated or finished: the first moment the card names. */
    fun updatedAt(): Instant? =
        e.select("[data-time]")
            .firstOrNull { el ->
                val hint = el.attr("data-hint").lowercase()
                "обновлен" in hint || "заверш" in hint
            }
            ?.attr("data-time")
            ?.let(AuthorTodayPages::instant)

    private companion object {
        val AUTHOR_LOGIN = Regex("^/u/([^/?#]+)")

        /** The statuses of the site by their text, to the standard ones. */
        val STATUSES =
            mapOf(
                "в процессе" to Books.Status.IN_PROGRESS,
                "весь текст" to Books.Status.FINISHED,
                "заморожен" to Books.Status.FROZEN,
            )
    }
}
