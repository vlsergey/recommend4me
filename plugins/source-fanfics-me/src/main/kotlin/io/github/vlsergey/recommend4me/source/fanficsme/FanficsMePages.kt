package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.AUTHOR
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.BASE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.CHARS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.COMMENTS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.COMMENT_PREFIX
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.DIRECTION
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.NOTES
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ORIGINALS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ORIGINAL_LANGUAGE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ORIGINAL_LINK
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ORIGINAL_TITLE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.PAIRING
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.PAIRINGS_LINE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.PARTS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.READERS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.RECOMMENDATIONS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.SERIES
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.SIZE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.STATUS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.TAG
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.TRANSLATOR
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.VIEWS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.WORDS
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Reads the pages of fanfics.me and writes what they say: the cards of a list (the search of every
 * work, a fandom's, a character's, an author's), a work's header with its table of contents and the
 * notes of its author, the text in the reader — a chapter or the whole — the comments, and what the
 * site's genres, warnings and events mean, by its help and the encyclopedia of a fandom.
 */
class FanficsMePages(private val context: SourceContext) {

    private val items = context.items

    fun read(page: CapturedPage): List<String> {
        val uri = runCatching { URI(page.url) }.getOrNull()
        val path = uri?.path.orEmpty()
        val query = uri?.rawQuery.orEmpty()
        val doc = Jsoup.parse(page.html, page.url)
        if (path == "/html" && query.split('&').any { it in HELP_ARTICLES }) help(doc)
        if (KEYWORDS_PAGE.matches(path) || (FANDOM_PAGE.matches(path) && "soaction=keywords" in query)) keywords(doc)
        val touched = LinkedHashSet<String>()
        touched += cards(doc, page.capturedAt)
        // The header of a work: on its own page, and on the reader of a work the site shows a guest not
        head(doc, page.capturedAt)?.let { touched += it }
        val work = WORK.matchEntire(path)?.groupValues?.get(1)
        val read = if (path == READER) READER_ID.find(query)?.groupValues?.get(1) else null
        read?.let { id -> if (chapters(doc, id)) touched += id }
        work?.let { id -> if (comments(doc, id, page.capturedAt)) touched += id }
        return touched.toList()
    }

    // --- Cards of a list ---

    private fun cards(doc: Document, now: Instant): List<String> = doc.select("div.FicTable[id^=fic_]").mapNotNull { card ->
        val id = card.id().removePrefix("fic_").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return@mapNotNull null
        val heading = card.selectFirst(".FicTable_Title h4") ?: return@mapNotNull null
        val title = heading.selectFirst("a[href^=/fic]")?.let { clean(it.text()) }?.ifEmpty { null } ?: return@mapNotNull null
        val stat = card.selectFirst(".FicTable_Stat")
        items.upsert(ItemHead(id, "$BASE/fic$id", title, updatedAt = updated(stat)), now)
        direction(id, heading)
        stat?.let { s ->
            items.setNumbers(
                id,
                listOfNotNull(
                    titled(s, ".Views")?.let { VIEWS to it },
                    titled(s, ".ReadersCount")?.let { READERS to it },
                ).toMap(),
            )
        }
        card.selectFirst("td.FicTbl_cover img[src]")?.absUrl("src")?.takeIf { it.isNotBlank() }?.let { items.setPictures(id, listOf(it)) }
        card.selectFirst("td.FicTbl_sammary")?.let { items.setTexts(id, mapOf(ANNOTATION to PageText.of(it))) }
        card.selectFirst("td.FicTbl_meta")?.let { described(id, labelled(it)) }
        id
    }

    /** The lines of a card's details: each label ("Автор:") in bold to what follows it till the next label. */
    private fun labelled(meta: Element): Map<String, Element> {
        val out = LinkedHashMap<String, Element>()
        var current: Element? = null
        meta.childNodes().forEach { node ->
            val label = (node as? Element)?.takeIf { it.tagName() == "b" }?.let { clean(it.text()) }?.takeIf { it.endsWith(":") }
            if (label != null) current = Element("span").also { out[label.removeSuffix(":")] = it }
            else current?.appendChild(node.clone())
        }
        return out
    }

    // --- A work's header ---

    /**
     * The header of a work ("FicHead"), its lines by their labels, and the other blocks of its page:
     * the description, the notes of its author, the table of contents. A page without the header —
     * an error page, a changed markup — says nothing of the work, and nothing is written.
     */
    private fun head(doc: Document, now: Instant): String? {
        val hat = doc.selectFirst("div.FicHead:has(#fic_id)") ?: return null
        val id = hat.selectFirst("#fic_id")?.text()?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return null
        val heading = hat.selectFirst("h1") ?: return null
        val title = clean(heading.ownText()).ifEmpty { return null }
        val rows = doc.select("div.FicHead div.tr").associate { row ->
            clean(row.selectFirst("> .title")?.text().orEmpty()).removeSuffix(":") to row.selectFirst("> .content")
        }.filterKeys { it.isNotEmpty() }.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
        items.upsert(ItemHead(id, "$BASE/fic$id", title, updatedAt = updated(hat)), now)
        direction(id, heading)
        hat.selectFirst("div.center.small")?.let { s ->
            items.setNumbers(
                id,
                listOfNotNull(
                    titled(s, ".Views")?.let { VIEWS to it },
                    titled(s, ".ReadersCount")?.let { READERS to it },
                    titled(s, ".Comments")?.let { COMMENTS to it },
                    s.selectFirst(".RecommendsCount")?.text()?.let(PageText::number)?.let { RECOMMENDATIONS to it.toDouble() },
                ).toMap(),
            )
        }
        hat.selectFirst(".FicHead_cover img[src]")?.absUrl("src")?.takeIf { it.isNotBlank() }?.let { items.setPictures(id, listOf(it)) }
        described(id, rows)
        original(id, rows["Оригинал"])
        items.setTexts(
            id,
            mapOf(
                ANNOTATION to doc.selectFirst("div[id=summary_$id]")?.let(PageText::of),
                NOTES to rows["От автора"]?.let { it.selectFirst("div[id^=author_comment_]") ?: it }?.let { PageText.of(it, ".small_link, script, style") },
            ),
        )
        contents(doc, id)
        return id
    }

    /** What a translation's line "Оригинал" says of the original: its title, author, address and language. */
    private fun original(id: String, block: Element?) {
        val info = block?.select("table.translation_info tr")?.associate { tr ->
            clean(tr.selectFirst("td.first")?.text().orEmpty()).removeSuffix(":") to tr.selectFirst("td.second")
        }.orEmpty()
        info["Автор"]?.let { clean(it.text()) }?.takeIf { it.isNotEmpty() }?.let { items.setFacet(id, AUTHOR, listOf(FacetValue(it.lowercase(), it))) }
        items.setFacet(id, ORIGINAL_LANGUAGE, listOfNotNull(info["Язык"]?.let { clean(it.text()) }?.takeIf { it.isNotEmpty() }?.let { FacetValue(it.lowercase(), it) }))
        items.setTexts(
            id,
            mapOf(
                ORIGINAL_TITLE to info["Название"]?.let { clean(it.text()) },
                ORIGINAL_LINK to info["Ссылка"]?.let { clean(it.text()) },
            ),
        )
    }

    /**
     * The table of contents: every chapter by its number in the reader, with its date — the page
     * lists the first and the last chapters apart from the rest, some of them twice. A work without
     * one is of one chapter, read whole by the link "ЧИТАТЬ".
     */
    private fun contents(doc: Document, id: String) {
        val parts = doc.select("ul.FicContents li").mapNotNull { li ->
            val a = li.selectFirst(".FicContentsChapterName a[href*=chapter=]") ?: return@mapNotNull null
            val number = CHAPTER.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val date = li.selectFirst(".small_light")?.text()?.substringBefore('/')?.let { PageText.russianDate(it) }
            PartData(number.toString(), number, clean(a.text()).ifEmpty { null }, null, date)
        }.distinctBy { it.id }
        val whole = doc.selectFirst("#FicReadLink a[href^=/read.php]")?.attr("href")?.let { "chapter=" !in it } ?: false
        when {
            parts.isNotEmpty() -> {
                items.saveParts(id, parts)
                items.setNumbers(id, mapOf(PARTS to parts.size.toDouble()))
            }
            whole -> {
                items.saveParts(id, listOf(PartData(ONLY_CHAPTER, 0, null, null)))
                items.setNumbers(id, mapOf(PARTS to 1.0))
            }
        }
    }

    // --- What a card and a header say alike ---

    /** The direction by the label after the title: "(гет)", "(джен)", "(слэш)", "(фемслэш)". */
    private fun direction(id: String, heading: Element) {
        val text = heading.selectFirst("span.small")?.let { clean(it.text()) }?.trim('(', ')', ' ') ?: return
        items.setFacet(id, DIRECTION, listOfNotNull(Books.DIRECTION.values?.firstOrNull { it.modelName.equals(text, ignoreCase = true) }?.let { FacetValue(it.key, it.modelName) }))
    }

    /** The lines of a card or a header by their labels: the author, the fandoms, the characters, the genres, the size. */
    private fun described(id: String, info: Map<String, Element>) {
        (info["Автор"] ?: info["Авторы"])?.let(::members)?.takeIf { it.isNotEmpty() }?.let { items.setFacet(id, AUTHOR, it) }
        (info["Переводчик"] ?: info["Переводчики"])?.let(::members)?.takeIf { it.isNotEmpty() }?.let { items.setFacet(id, FanficsMe.TRANSLATOR, it) }
        (info["Фандом"] ?: info["Фандомы"])?.let { content ->
            val fandoms = content.select("a[href^=/fandom]").mapNotNull { a ->
                FANDOM_LINK.matchEntire(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, clean(a.text())) }
            }
            if (fandoms.isNotEmpty()) {
                items.setFacet(id, FANDOM, fandoms)
                // A work of the fandom "Ориджиналы" is an original one; of any other, fan fiction
                val original = fandoms.all { it.key == ORIGINALS }
                items.setFacet(id, UniverseFacets.KIND, listOf(FacetValue(if (original) UniverseFacets.ORIGINAL else UniverseFacets.FANFICTION)))
            }
        }
        info["Персонажи"]?.let { content ->
            val entries = content.select("a[href^=/character], a[href^=/paring]").mapNotNull(::entry).distinctBy { it.key }
            items.setFacet(id, PAIRING, entries)
            // The line as the site shows it: what the application works the pairings and characters out from
            items.setTexts(id, mapOf(PAIRINGS_LINE to entries.joinToString(", ") { it.name ?: it.key }))
        }
        info["Рейтинг"]?.let { clean(it.text()).substringBefore(' ').lowercase() }?.let { r ->
            AgeRating.write(items, id, listOfNotNull(AgeRating.VALUES.firstOrNull { it.key == (if (r == "general") AgeRating.G.key else r) }))
        }
        val genres = (info["Жанр"] ?: info["Жанры"])?.let { listed(it.text()) }
        if (genres != null) {
            val warnings = info["Предупреждения"]?.let { listed(it.text()) }.orEmpty()
            val events = info["События"]?.select("a[href*=keyword=]")?.map { clean(it.text()) }?.filter { it.isNotEmpty() }.orEmpty()
            // The genres, the warnings and the events: the site's tags, one of a name however many lists name it
            items.setFacet(id, TAG, (genres + warnings + events).map { FacetValue(it.lowercase(), it) }.distinctBy { it.key })
        }
        info["Размер"]?.let { content ->
            val shown = content.selectFirst(".ModalLink_value")?.text() ?: content.text()
            clean(shown).substringBefore('|').trim().takeIf { it.isNotEmpty() }?.let { items.setFacet(id, SIZE, listOf(FacetValue(it.lowercase(), it))) }
            val text = clean(content.text())
            items.setNumbers(
                id,
                listOfNotNull(
                    PageText.numberBefore(text, "знак")?.let { CHARS to it.toDouble() },
                    PageText.numberBefore(text, "слов")?.let { WORDS to it.toDouble() },
                ).toMap(),
            )
        }
        info["Статус"]?.let { content ->
            // A translation's line goes on with the original's status: the first is the work's own
            val text = clean(content.selectFirst("span")?.text() ?: content.text().substringBefore('|')).ifEmpty { null } ?: return@let
            val status = STATUSES[text.lowercase()]
            items.setFacet(id, STATUS, listOf(status?.let { FacetValue(it.key, it.modelName) } ?: FacetValue(text.lowercase(), text)))
        }
        info["Серия"]?.selectFirst("a[href^=/serie]")?.let { a ->
            SERIE_LINK.matchEntire(a.attr("href"))?.groupValues?.get(1)?.let { items.setFacet(id, SERIES, listOf(FacetValue(it, clean(a.text())))) }
        }
        info["Примечания автора"]?.let { items.setTexts(id, mapOf(NOTES to PageText.of(it))) }
    }

    /** The members of the site a line names, by their ids. */
    private fun members(content: Element): List<FacetValue> = content.select("a[href^=/user]").mapNotNull { a ->
        USER_LINK.matchEntire(a.attr("href"))?.groupValues?.get(1)?.let { FacetValue(it, clean(a.text())) }
    }.distinctBy { it.key }

    /**
     * An entry of the line of characters: a character of the site's encyclopedia by its id
     * ("/character12" → "12"), a pairing by the ids of its characters in their order ("/paring1_2_3" → "1/2/3").
     */
    private fun entry(a: Element): FacetValue? {
        val name = clean(a.text()).ifEmpty { return null }
        val href = a.attr("href")
        CHARACTER_LINK.matchEntire(href)?.let { return FacetValue(it.groupValues[1], name) }
        val ids = PAIRING_LINK.matchEntire(href)?.groupValues?.get(1)?.split('_')?.mapNotNull(String::toLongOrNull) ?: return null
        return FacetValue(ids.sorted().joinToString("/"), name)
    }

    /** A number the title of an element tells: "Количество просмотров 2 612 +588 за сегодня" → 2612. */
    private fun titled(at: Element, selector: String): Double? = at.selectFirst(selector)?.attr("title")?.let(PageText::number)?.toDouble()

    /** When the work changed last: "Опубликовано 07.07.2026, изменено 28.10.2026" in the title of its date. */
    private fun updated(at: Element?): Instant? = at?.selectFirst(".DateUpdate[title]")?.attr("title")?.substringAfter("изменено", "")?.let { PageText.russianDate(it) }

    // --- The reader ---

    /**
     * The text in the reader: one chapter, or the whole of the work — each chapter (div "c<number>")
     * after its title and before the date it was published. Of a work not known yet, nothing.
     */
    private fun chapters(doc: Document, id: String): Boolean {
        if (items.find(id) == null) return false
        val parts = doc.select("div.ReadContent div.chapter[id~=^c\\d+$]").mapNotNull { div ->
            val number = div.id().removePrefix("c").toIntOrNull() ?: return@mapNotNull null
            val text = PageText.of(div).ifBlank { return@mapNotNull null }
            val title = div.previousElementSibling()?.takeIf { it.tagName() == "h2" }?.let { clean(it.text()) }?.ifEmpty { null }
            val date = div.nextElementSibling()?.takeIf { it.hasClass("tright") }?.text()?.let { PageText.russianDate(it) }
            PartData(number.toString(), number, title, text, date)
        }
        if (parts.isEmpty()) return false
        items.saveParts(id, parts)
        return true
    }

    // --- Comments ---

    /**
     * The comments the page shows, on the work's own page and its page of comments: a quote of
     * another comment left out, and the name of the one answered that opens it.
     */
    private fun comments(doc: Document, id: String, now: Instant): Boolean {
        val list = doc.select("table.MessageBody[id^=$COMMENT_PREFIX]")
        if (list.isEmpty() || items.find(id) == null) return false
        items.saveReviews(id, list.mapNotNull { c ->
            val message = c.selectFirst("div.MessageText")?.clone() ?: return@mapNotNull null
            message.children().firstOrNull()?.takeIf { it.tagName() == "a" && it.hasClass("user") }?.remove()
            val text = PageText.of(message, ".blockquote, script, style").ifBlank { return@mapNotNull null }
            ReviewData(
                c.id().removePrefix(COMMENT_PREFIX),
                c.selectFirst(".MessageRightUser a.user")?.let { clean(it.text()) },
                null,
                text,
                c.selectFirst("a[href^=/comment]")?.text()?.let { commentDate(it, now) },
            )
        })
        return true
    }

    /**
     * When a comment was written, as the site writes it: "сегодня в 17:19", "вчера в 20:10" — of the
     * day the page was seen, in Moscow time — "12 октября 2025 в 10:00", and "12 октября в 10:00" of
     * this year, or of the last when that would be after the page was seen.
     */
    private fun commentDate(text: String, now: Instant): Instant? {
        val t = clean(text).lowercase()
        val today = now.atZone(MOSCOW).toLocalDate()
        RELATIVE_DAY.find(t)?.let { m ->
            val day = if (m.groupValues[1] == "вчера") today.minusDays(1) else today
            return day.atTime(LocalTime.of(m.groupValues[2].toInt(), m.groupValues[3].toInt())).atZone(MOSCOW).toInstant()
        }
        PageText.russianDate(t)?.let { return it }
        val m = DAY_OF_YEAR.find(t) ?: return null
        val (day, month, time) = m.destructured
        val dated = PageText.russianDate("$day $month ${today.year} $time") ?: return null
        return if (dated.isAfter(now)) PageText.russianDate("$day $month ${today.year - 1} $time") else dated
    }

    // --- What the tags mean ---

    /**
     * The site's help on its genres and its warnings: every one a paragraph opening with its name in
     * bold — "<b>Ангст</b> - это сильные переживания…" — the paragraph what the tag of the name means.
     */
    private fun help(doc: Document) {
        val table = doc.selectFirst("div.ContentTable:has(> b)") ?: return
        val described = table.html().split(PARAGRAPHS).mapNotNull { chunk ->
            val paragraph = Jsoup.parseBodyFragment(chunk).body()
            val name = paragraph.selectFirst("b")?.let { clean(it.text()) }?.takeIf { it.isNotEmpty() && !it.endsWith(":") } ?: return@mapNotNull null
            val text = PageText.of(paragraph).ifBlank { return@mapNotNull null }
            name to text
        }
        describe(described)
    }

    /**
     * A fandom's events in its encyclopedia: every one a block with its name — a link to its works —
     * and what it means under it.
     */
    private fun keywords(doc: Document) = describe(doc.select("div.hero_item:has(a[href*=keyword=])").mapNotNull { item ->
        val name = item.selectFirst("a[href*=keyword=]")?.let { clean(it.text()) }?.ifEmpty { null } ?: return@mapNotNull null
        val text = item.select("p.text").drop(1).joinToString("\n\n") { PageText.of(it) }.trim().ifEmpty { return@mapNotNull null }
        name to text
    })

    private fun describe(described: List<Pair<String, String>>) {
        if (described.isEmpty()) return
        items.nameFacetValues(TAG, described.associate { it.first.lowercase() to it.first })
        items.describeFacetValues(TAG, described.associate { it.first.lowercase() to it.second })
    }

    companion object {
        /** A work's page and its page of comments: "/fic123", "/fic123/comments" — the comments of its pictures are not of it. */
        private val WORK = Regex("/fic(\\d+)(?:/comments)?/?")
        private const val READER = "/read.php"
        private val READER_ID = Regex("(?:^|&)id=(\\d+)(?:&|$)")
        private val CHAPTER = Regex("[?&]chapter=(\\d+)")

        /** The events of a fandom in its encyclopedia: "/fandom2/keywords", or "/fandom2?action=encyclopedia&soaction=keywords". */
        private val KEYWORDS_PAGE = Regex("/fandom\\d+/keywords/?")
        private val FANDOM_PAGE = Regex("/fandom\\d+/?")

        /** The site's help on its genres and its warnings, "/html?article=…". */
        private val HELP_ARTICLES = setOf("article=about_genres", "article=about_warnings")

        /** The paragraphs of the site's help: two line breaks between. */
        private val PARAGRAPHS = Regex("<br\\s*/?>\\s*<br\\s*/?>")

        private val USER_LINK = Regex("/user(\\d+)")
        private val FANDOM_LINK = Regex("/fandom(\\d+)")
        private val CHARACTER_LINK = Regex("/character(\\d+)")
        private val PAIRING_LINK = Regex("/paring(\\d+(?:_\\d+)+)")
        private val SERIE_LINK = Regex("/serie(\\d+)")

        private val RELATIVE_DAY = Regex("(сегодня|вчера)\\s+в\\s+(\\d{1,2}):(\\d{2})")
        private val DAY_OF_YEAR = Regex("(\\d{1,2})\\s+([а-яё]+)\\s+в\\s+(\\d{1,2}:\\d{2})")

        private val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

        /** The part of a work of one chapter: the first and only of the reader. */
        const val ONLY_CHAPTER = "0"

        /** The statuses of the site by their text, to the standard ones. */
        private val STATUSES = mapOf(
            "закончен" to Books.Status.FINISHED,
            "в процессе" to Books.Status.IN_PROGRESS,
            "заморожен" to Books.Status.FROZEN,
        )

        /** The text as people read it: the non-breaking spaces of the site plain ones. */
        private fun clean(text: String): String = text.replace(' ', ' ').trim()

        /** The names of a line written as a list: "Приключения, Романтика". */
        private fun listed(text: String): List<String> = clean(text).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}
