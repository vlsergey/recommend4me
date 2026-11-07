package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.COMMENT_PREFIX
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.jsoup.nodes.Document

/** The comments of a work as its pages show them, each with the day it was written as the site tells it. */
internal class FanficsMeComments(private val items: ItemStore) {

    /**
     * The comments the page shows, on the work's own page and its page of comments: a quote of another comment left
     * out, and the name of the one answered that opens it.
     */
    fun read(doc: Document, id: String, now: Instant): Boolean {
        val list = doc.select("table.MessageBody[id^=$COMMENT_PREFIX]")
        if (list.isEmpty() || items.find(id) == null) return false
        items.saveReviews(
            id,
            list.mapNotNull { c ->
                val message = c.selectFirst("div.MessageText")?.clone() ?: return@mapNotNull null
                message.children().firstOrNull()?.takeIf { it.tagName() == "a" && it.hasClass("user") }?.remove()
                val text =
                    PageText.of(message, ".blockquote, script, style").ifBlank {
                        return@mapNotNull null
                    }
                ReviewData(
                    c.id().removePrefix(COMMENT_PREFIX),
                    c.selectFirst(".MessageRightUser a.user")?.let { clean(it.text()) },
                    null,
                    text,
                    c.selectFirst("a[href^=/comment]")?.text()?.let { commentDate(it, now) },
                )
            },
        )
        return true
    }

    private companion object {
        val RELATIVE_DAY = Regex("(сегодня|вчера)\\s+в\\s+(\\d{1,2}):(\\d{2})")
        val DAY_OF_YEAR = Regex("(\\d{1,2})\\s+([а-яё]+)\\s+в\\s+(\\d{1,2}:\\d{2})")

        val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

        /**
         * When a comment was written, as the site writes it: "сегодня в 17:19", "вчера в 20:10" — of the day the page
         * was seen, in Moscow time — "12 октября 2025 в 10:00", and "12 октября в 10:00" of this year, or of the last
         * when that would be after the page was seen.
         */
        fun commentDate(text: String, now: Instant): Instant? {
            val t = clean(text).lowercase()
            val today = now.atZone(MOSCOW).toLocalDate()
            return RELATIVE_DAY.find(t)?.let { m ->
                val (word, hour, minute) = m.destructured
                val day = if (word == "вчера") today.minusDays(1) else today
                day.atTime(LocalTime.of(hour.toInt(), minute.toInt())).atZone(MOSCOW).toInstant()
            } ?: PageText.russianDate(t) ?: DAY_OF_YEAR.find(t)?.let { ofThisYear(it, today, now) }
        }

        /**
         * "12 октября в 10:00" ([m]): of this year ([today]'s), or of the last when that would be after the page was
         * seen ([now]).
         */
        fun ofThisYear(m: MatchResult, today: LocalDate, now: Instant): Instant? {
            val (day, month, time) = m.destructured
            val dated = PageText.russianDate("$day $month ${today.year} $time") ?: return null
            return if (dated.isAfter(now)) PageText.russianDate("$day $month ${today.year - 1} $time") else dated
        }
    }
}
