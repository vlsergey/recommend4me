package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.CHARS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.DIRECTION
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.NOTES
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ORIGINALS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ORIGINAL_AUTHOR
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.PAIRING
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.PAIRINGS_LINE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.SERIES
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.SIZE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.STATUS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.TAG
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.WORDS
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import java.time.Instant
import org.jsoup.nodes.Element

/**
 * What a card of a list and a work's header say alike, written to the work's item: the direction after the title, and
 * the lines by their labels — the author, the fandoms, the characters, the rating, the genres, the size, the status,
 * the series, the notes of the author.
 */
internal class FanficsMeFields(private val items: ItemStore) {
    /** The series of the works, and their numbers there. */
    private val series = FanficsMeSeries(items)

    /** The direction by the label after the title: "(гет)", "(джен)", "(слэш)", "(фемслэш)". */
    fun direction(id: String, heading: Element) {
        val text = heading.selectFirst("span.small")?.let { clean(it.text()) }?.trim('(', ')', ' ') ?: return
        items.setFacet(
            id,
            DIRECTION,
            listOfNotNull(
                Books.DIRECTION.values
                    ?.firstOrNull { it.modelName.equals(text, ignoreCase = true) }
                    ?.let { FacetValue(it.key, it.modelName) }
            ),
        )
    }

    /**
     * The lines of a card or a header by their labels: the author, the fandoms, the characters, the genres, the size.
     * The author of a work is who put it on the site: of a translation its translator, the writer of the original
     * apart.
     */
    fun described(id: String, info: Map<String, Element>) {
        authors(id, info)
        (info["Фандом"] ?: info["Фандомы"])?.let { fandoms(id, it) }
        info["Персонажи"]?.let { characters(id, it) }
        info["Рейтинг"]?.let { rating(id, it) }
        (info["Жанр"] ?: info["Жанры"])?.let { tags(id, it, info) }
        info["Размер"]?.let { size(id, it) }
        info["Статус"]?.let { status(id, it) }
        info["Серия"]?.let { line ->
            line.selectFirst("a[href^=/serie]")?.let { a ->
                SERIE_LINK.matchEntire(a.attr("href"))?.groupValues?.get(1)?.let {
                    items.setFacet(id, SERIES, listOf(FacetValue(it, clean(a.text()))))
                    series.place(id, it, line)
                }
            }
        }
        info["Примечания автора"]?.let { items.setTexts(id, mapOf(NOTES to PageText.of(it))) }
    }

    /**
     * The authors of the lines [info] has: who put the work on the site — of a translation its translators — and the
     * writers of the original apart.
     */
    private fun authors(id: String, info: Map<String, Element>) {
        val writers = (info["Автор"] ?: info["Авторы"])?.let(::members).orEmpty()
        val translators = (info["Переводчик"] ?: info["Переводчики"])?.let(::members).orEmpty()
        translators.ifEmpty { writers }.takeIf { it.isNotEmpty() }?.let { items.setFacet(id, AUTHOR, it) }
        if (translators.isNotEmpty() && writers.isNotEmpty()) items.setFacet(id, ORIGINAL_AUTHOR, writers)
    }

    /** The fandoms of the line [content], and the kind of the work they tell. */
    private fun fandoms(id: String, content: Element) {
        val fandoms =
            content.select("a[href^=/fandom]").mapNotNull { a ->
                FANDOM_LINK.matchEntire(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, clean(a.text())) }
            }
        if (fandoms.isNotEmpty()) {
            items.setFacet(id, FANDOM, fandoms)
            // A work of the fandom "Ориджиналы" is an original one; of any other, fan fiction
            val original = fandoms.all { it.key == ORIGINALS }
            items.setFacet(
                id,
                UniverseFacets.KIND,
                listOf(FacetValue(if (original) UniverseFacets.ORIGINAL else UniverseFacets.FANFICTION)),
            )
        }
    }

    /** The characters and the pairings of the line [content], and the line itself. */
    private fun characters(id: String, content: Element) {
        val entries = content.select("a[href^=/character], a[href^=/paring]").mapNotNull(::entry).distinctBy { it.key }
        items.setFacet(id, PAIRING, entries)
        // The line as the site shows it: what the application works the pairings and characters out from
        items.setTexts(id, mapOf(PAIRINGS_LINE to entries.joinToString(", ") { it.name ?: it.key }))
    }

    /** The rating of the line [content] on the age rating's scale: "General" is G. */
    private fun rating(id: String, content: Element) {
        val r = clean(content.text()).substringBefore(' ').lowercase()
        AgeRating.write(
            items,
            id,
            listOfNotNull(AgeRating.VALUES.firstOrNull { it.key == (if (r == "general") AgeRating.G.key else r) }),
        )
    }

    /**
     * The genres of the line [genres], the warnings and the events of the lines [info] has: the site's tags, one of a
     * name however many lists name it.
     */
    private fun tags(id: String, genres: Element, info: Map<String, Element>) {
        val warnings = info["Предупреждения"]?.let { listed(it.text()) }.orEmpty()
        val events =
            info["События"]?.select("a[href*=keyword=]")?.map { clean(it.text()) }?.filter { it.isNotEmpty() }.orEmpty()
        items.setFacet(
            id,
            TAG,
            (listed(genres.text()) + warnings + events).map { FacetValue(it.lowercase(), it) }.distinctBy { it.key },
        )
    }

    /** The size of the line [content]: as the site names it, and its characters and words. */
    private fun size(id: String, content: Element) {
        val shown = content.selectFirst(".ModalLink_value")?.text() ?: content.text()
        clean(shown)
            .substringBefore('|')
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { items.setFacet(id, SIZE, listOf(FacetValue(it.lowercase(), it))) }
        val text = clean(content.text())
        items.setNumbers(
            id,
            listOfNotNull(
                    PageText.numberBefore(text, "знак")?.let { CHARS to it.toDouble() },
                    PageText.numberBefore(text, "слов")?.let { WORDS to it.toDouble() },
                )
                .toMap(),
        )
    }

    /** The status of the line [content]: the standard one, another as the site writes it. */
    private fun status(id: String, content: Element) {
        // A translation's line goes on with the original's status: the first is the work's own
        val text = clean(content.selectFirst("span")?.text() ?: content.text().substringBefore('|')).ifEmpty { null }
        if (text == null) return
        val status = STATUSES[text.lowercase()]
        items.setFacet(
            id,
            STATUS,
            listOf(status?.let { FacetValue(it.key, it.modelName) } ?: FacetValue(text.lowercase(), text)),
        )
    }

    companion object {
        private val USER_LINK = Regex("/user(\\d+)")
        private val FANDOM_LINK = Regex("/fandom(\\d+)")
        private val CHARACTER_LINK = Regex("/character(\\d+)")
        private val PAIRING_LINK = Regex("/paring(\\d+(?:_\\d+)+)")
        private val SERIE_LINK = Regex("/serie(\\d+)")

        /** The statuses of the site by their text, to the standard ones. */
        private val STATUSES =
            mapOf(
                "закончен" to Books.Status.FINISHED,
                "в процессе" to Books.Status.IN_PROGRESS,
                "заморожен" to Books.Status.FROZEN,
            )

        /** A number the title of an element tells: "Количество просмотров 2 612 +588 за сегодня" → 2612. */
        fun titled(at: Element, selector: String): Double? =
            at.selectFirst(selector)?.attr("title")?.let(PageText::number)?.toDouble()

        /** When the work changed last: "Опубликовано 07.07.2026, изменено 28.10.2026" in the title of its date. */
        fun updated(at: Element?): Instant? =
            at?.selectFirst(".DateUpdate[title]")?.attr("title")?.substringAfter("изменено", "")?.let {
                PageText.russianDate(it)
            }

        /** The members of the site a line names, by their ids. */
        private fun members(content: Element): List<FacetValue> =
            content
                .select("a[href^=/user]")
                .mapNotNull { a ->
                    USER_LINK.matchEntire(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, clean(a.text())) }
                }
                .distinctBy { it.key }

        /**
         * An entry of the line of characters: a character of the site's encyclopedia by its id ("/character12" → "12"),
         * a pairing by the ids of its characters in their order ("/paring1_2_3" → "1/2/3").
         */
        private fun entry(a: Element): FacetValue? {
            val name = clean(a.text()).ifEmpty { null }
            val href = a.attr("href")
            val key =
                CHARACTER_LINK.matchEntire(href)?.groupValues?.get(1)
                    ?: PAIRING_LINK.matchEntire(href)
                        ?.groupValues
                        ?.get(1)
                        ?.split('_')
                        ?.mapNotNull(String::toLongOrNull)
                        ?.sorted()
                        ?.joinToString("/")
            return if (name == null || key == null) null else FacetValue(key, name)
        }

        /** The names of a line written as a list: "Приключения, Романтика". */
        private fun listed(text: String): List<String> =
            clean(text).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
