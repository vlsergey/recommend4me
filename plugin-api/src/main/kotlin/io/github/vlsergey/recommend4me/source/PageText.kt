package io.github.vlsergey.recommend4me.source

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** What the sources read off pages the same way: the text of an element, numbers and dates written for people. */
object PageText {

    private val BLOCKS = setOf("div", "p", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section", "article", "tr")

    /**
     * The text of an element with its paragraphs: a block or a line break starts a new line,
     * blank lines are kept one at most, spacing within a line evened out. [skip] leaves out the
     * elements it selects (quotes of someone else, scripts).
     */
    fun of(element: Element, skip: String? = "script, style"): String {
        val root = element.clone()
        skip?.let { root.select(it).remove() }
        val out = StringBuilder()
        fun walk(node: Node) {
            when (node) {
                is TextNode -> out.append(node.wholeText.replace(' ', ' '))
                is Element -> {
                    if (node.tagName() == "br") out.append('\n')
                    val block = node.tagName() in BLOCKS
                    if (block) out.append('\n')
                    node.childNodes().forEach(::walk)
                    if (block) out.append('\n')
                }
            }
        }
        root.childNodes().forEach(::walk)
        return out.toString().lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /** The first whole number in a text written with spaces between thousands: "68 967 слов" → 68967. */
    fun number(text: String?): Long? =
        text?.replace(' ', ' ')?.let { Regex("\\d[\\d ]*").find(it)?.value?.replace(" ", "")?.toLongOrNull() }

    /** The number before [word] in a text: "180 страниц, 68 967 слов" and "слов" → 68967. */
    fun numberBefore(text: String?, word: String): Long? =
        text?.replace(' ', ' ')?.let { Regex("(\\d[\\d ]*)\\s*$word").find(it)?.groupValues?.get(1)?.replace(" ", "")?.toLongOrNull() }

    private val MONTHS = listOf("январ", "феврал", "март", "апрел", "ма", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")

    /**
     * A date written for a Russian reader — "2 августа 2026 г., 17:55", "04.08.2026" — as a moment in
     * Moscow time; null when it is neither.
     */
    fun russianDate(text: String?, zone: ZoneId = ZoneId.of("Europe/Moscow")): Instant? {
        val t = text?.trim()?.lowercase() ?: return null
        Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})").find(t)?.let { m ->
            val (d, mo, y) = m.destructured
            return LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atStartOfDay(zone).toInstant()
        }
        val m = Regex("(\\d{1,2})\\s+([а-яё]+)\\s+(\\d{4})(?:\\D+(\\d{1,2}):(\\d{2}))?").find(t) ?: return null
        val month = m.groupValues[2]
        // "ма" stands for "мая": tried last, as "март" begins the same way
        val index = MONTHS.withIndex().filter { it.value != "ма" }.firstOrNull { month.startsWith(it.value) }?.index
            ?: if (month.startsWith("ма")) 4 else return null
        val hour = m.groupValues[4].toIntOrNull() ?: 0
        val minute = m.groupValues[5].toIntOrNull() ?: 0
        return LocalDateTime.of(m.groupValues[3].toInt(), index + 1, m.groupValues[1].toInt(), hour, minute).atZone(zone).toInstant()
    }
}
