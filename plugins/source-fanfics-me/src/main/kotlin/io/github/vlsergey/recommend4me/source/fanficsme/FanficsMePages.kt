package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ANNOTATION
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.BASE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.COMMENTS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.NOTES
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ORIGINAL_AUTHOR
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ORIGINAL_LANGUAGE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ORIGINAL_LINK
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.ORIGINAL_TITLE
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.PARTS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.READERS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.RECOMMENDATIONS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.VIEWS
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMeFields.Companion.titled
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMeFields.Companion.updated
import java.net.URI
import java.time.Instant
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** The text as people read it: the non-breaking spaces of the site plain ones. */
internal fun clean(text: String): String = text.replace(' ', ' ').trim()

/**
 * Reads the pages of fanfics.me and writes what they say: the cards of a list (the search of every work, a fandom's, a
 * character's, an author's), a work's header with its table of contents and the notes of its author, the text in the
 * reader — a chapter or the whole — the comments ([FanficsMeComments]), and what the site's genres, warnings and events
 * mean, by its help and the encyclopedia of a fandom ([FanficsMeTagMeanings]).
 */
class FanficsMePages(context: SourceContext) {

    private val items = context.items
    private val fields = FanficsMeFields(items)
    private val comments = FanficsMeComments(items)
    private val meanings = FanficsMeTagMeanings(items)

    fun read(page: CapturedPage): List<String> {
        val uri = runCatching { URI(page.url) }.getOrNull()
        val path = uri?.path.orEmpty()
        val query = uri?.rawQuery.orEmpty()
        val doc = Jsoup.parse(page.html, page.url)
        if (path == "/html" && query.split('&').any { it in HELP_ARTICLES }) meanings.help(doc)
        if (KEYWORDS_PAGE.matches(path) || (FANDOM_PAGE.matches(path) && "soaction=keywords" in query)) {
            meanings.keywords(doc)
        }
        val touched = LinkedHashSet<String>()
        touched += cards(doc, page.capturedAt)
        // The header of a work: on its own page, and on the reader of a work the site shows a guest not
        head(doc, page.capturedAt)?.let { touched += it }
        val work = WORK.matchEntire(path)?.groupValues?.get(1)
        val read = if (path == READER) READER_ID.find(query)?.groupValues?.get(1) else null
        read?.let { id -> if (chapters(doc, id)) touched += id }
        work?.let { id -> if (comments.read(doc, id, page.capturedAt)) touched += id }
        return touched.toList()
    }

    // --- Cards of a list ---

    private fun cards(doc: Document, now: Instant): List<String> =
        doc.select("div.FicTable[id^=fic_]").mapNotNull { card ->
            val id =
                card.id().removePrefix("fic_").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                    ?: return@mapNotNull null
            val heading = card.selectFirst(".FicTable_Title h4") ?: return@mapNotNull null
            val title =
                heading.selectFirst("a[href^=/fic]")?.let { clean(it.text()) }?.ifEmpty { null }
                    ?: return@mapNotNull null
            val stat = card.selectFirst(".FicTable_Stat")
            items.upsert(ItemHead(id, "$BASE/fic$id", title, updatedAt = updated(stat)), now)
            fields.direction(id, heading)
            stat?.let { s ->
                items.setNumbers(
                    id,
                    listOfNotNull(
                            titled(s, ".Views")?.let { VIEWS to it },
                            titled(s, ".ReadersCount")?.let { READERS to it },
                        )
                        .toMap(),
                )
            }
            card
                .selectFirst("td.FicTbl_cover img[src]")
                ?.absUrl("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { items.setPictures(id, listOf(it)) }
            card.selectFirst("td.FicTbl_sammary")?.let { items.setTexts(id, mapOf(ANNOTATION to PageText.of(it))) }
            card.selectFirst("td.FicTbl_meta")?.let { fields.described(id, labelled(it)) }
            id
        }

    /** The lines of a card's details: each label ("Автор:") in bold to what follows it till the next label. */
    private fun labelled(meta: Element): Map<String, Element> {
        val out = LinkedHashMap<String, Element>()
        var current: Element? = null
        meta.childNodes().forEach { node ->
            val label =
                (node as? Element)
                    ?.takeIf { it.tagName() == "b" }
                    ?.let { clean(it.text()) }
                    ?.takeIf { it.endsWith(":") }
            if (label != null) current = Element("span").also { out[label.removeSuffix(":")] = it }
            else current?.appendChild(node.clone())
        }
        return out
    }

    // --- A work's header ---

    /**
     * The header of a work ("FicHead"), its lines by their labels, and the other blocks of its page: the description,
     * the notes of its author, the table of contents. A page without the header — an error page, a changed markup —
     * says nothing of the work, and nothing is written.
     */
    private fun head(doc: Document, now: Instant): String? {
        val hat = doc.selectFirst("div.FicHead:has(#fic_id)")
        val id = hat?.selectFirst("#fic_id")?.text()?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val headed = hat?.selectFirst("h1")?.let { h -> clean(h.ownText()).ifEmpty { null }?.let { h to it } }
        if (hat == null || id == null || headed == null) return null
        val (heading, title) = headed
        val rows = rows(doc)
        items.upsert(ItemHead(id, "$BASE/fic$id", title, updatedAt = updated(hat)), now)
        fields.direction(id, heading)
        hat.selectFirst("div.center.small")?.let { headNumbers(id, it) }
        hat.selectFirst(".FicHead_cover img[src]")
            ?.absUrl("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { items.setPictures(id, listOf(it)) }
        fields.described(id, rows)
        original(id, rows["Оригинал"])
        items.setTexts(
            id,
            mapOf(
                ANNOTATION to doc.selectFirst("div[id=summary_$id]")?.let(PageText::of),
                NOTES to
                    rows["От автора"]
                        ?.let { it.selectFirst("div[id^=author_comment_]") ?: it }
                        ?.let { PageText.of(it, ".small_link, script, style") },
            ),
        )
        contents(doc, id)
        return id
    }

    /** The numbers of the header's counters [s]: the views, the readers, the comments, the recommendations. */
    private fun headNumbers(id: String, s: Element) {
        items.setNumbers(
            id,
            listOfNotNull(
                    titled(s, ".Views")?.let { VIEWS to it },
                    titled(s, ".ReadersCount")?.let { READERS to it },
                    titled(s, ".Comments")?.let { COMMENTS to it },
                    s.selectFirst(".RecommendsCount")?.text()?.let(PageText::number)?.let {
                        RECOMMENDATIONS to it.toDouble()
                    },
                )
                .toMap(),
        )
    }

    /** What a translation's line "Оригинал" says of the original: its title, author, address and language. */
    private fun original(id: String, block: Element?) {
        val info =
            block
                ?.select("table.translation_info tr")
                ?.associate { tr ->
                    clean(tr.selectFirst("td.first")?.text().orEmpty()).removeSuffix(":") to tr.selectFirst("td.second")
                }
                .orEmpty()
        info["Автор"]
            ?.let { clean(it.text()) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { items.setFacet(id, ORIGINAL_AUTHOR, listOf(FacetValue(it.lowercase(), it))) }
        items.setFacet(
            id,
            ORIGINAL_LANGUAGE,
            listOfNotNull(
                info["Язык"]
                    ?.let { clean(it.text()) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { FacetValue(it.lowercase(), it) }
            ),
        )
        items.setTexts(
            id,
            mapOf(
                ORIGINAL_TITLE to info["Название"]?.let { clean(it.text()) },
                ORIGINAL_LINK to info["Ссылка"]?.let { clean(it.text()) },
            ),
        )
    }

    /**
     * The table of contents: every chapter by its number in the reader, with its date — the page lists the first and
     * the last chapters apart from the rest, some of them twice. A work without one is of one chapter, read whole by
     * the link "ЧИТАТЬ".
     */
    private fun contents(doc: Document, id: String) {
        val parts =
            doc.select("ul.FicContents li")
                .mapNotNull { li ->
                    val a = li.selectFirst(".FicContentsChapterName a[href*=chapter=]") ?: return@mapNotNull null
                    val number =
                        CHAPTER.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                    val date =
                        li.selectFirst(".small_light")?.text()?.substringBefore('/')?.let { PageText.russianDate(it) }
                    PartData(number.toString(), number, clean(a.text()).ifEmpty { null }, null, date)
                }
                .distinctBy { it.id }
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

    // --- The reader ---

    /**
     * The text in the reader: one chapter, or the whole of the work — each chapter (div "c<number>") after its title
     * and before the date it was published. Of a work not known yet, nothing.
     */
    private fun chapters(doc: Document, id: String): Boolean {
        val parts =
            if (items.find(id) == null) emptyList()
            else
                doc.select("div.ReadContent div.chapter[id~=^c\\d+$]").mapNotNull { div ->
                    val number = div.id().removePrefix("c").toIntOrNull() ?: return@mapNotNull null
                    val text =
                        PageText.of(div).ifBlank {
                            return@mapNotNull null
                        }
                    val title =
                        div.previousElementSibling()
                            ?.takeIf { it.tagName() == "h2" }
                            ?.let { clean(it.text()) }
                            ?.ifEmpty { null }
                    val date =
                        div.nextElementSibling()
                            ?.takeIf { it.hasClass("tright") }
                            ?.text()
                            ?.let { PageText.russianDate(it) }
                    PartData(number.toString(), number, title, text, date)
                }
        if (parts.isNotEmpty()) items.saveParts(id, parts)
        return parts.isNotEmpty()
    }

    companion object {
        /**
         * A work's page and its page of comments: "/fic123", "/fic123/comments" — the comments of its pictures are not
         * of it.
         */
        private val WORK = Regex("/fic(\\d+)(?:/comments)?/?")
        private const val READER = "/read.php"
        private val READER_ID = Regex("(?:^|&)id=(\\d+)(?:&|$)")
        private val CHAPTER = Regex("[?&]chapter=(\\d+)")

        /**
         * The events of a fandom in its encyclopedia: "/fandom2/keywords", or
         * "/fandom2?action=encyclopedia&soaction=keywords".
         */
        private val KEYWORDS_PAGE = Regex("/fandom\\d+/keywords/?")
        private val FANDOM_PAGE = Regex("/fandom\\d+/?")

        /** The site's help on its genres and its warnings, "/html?article=…". */
        private val HELP_ARTICLES = setOf("article=about_genres", "article=about_warnings")

        /** The part of a work of one chapter: the first and only of the reader. */
        const val ONLY_CHAPTER = "0"

        /** The lines of a header by their labels: each line's title, without its colon, to its content. */
        private fun rows(doc: Document): Map<String, Element> =
            doc.select("div.FicHead div.tr")
                .associate { row ->
                    clean(row.selectFirst("> .title")?.text().orEmpty()).removeSuffix(":") to
                        row.selectFirst("> .content")
                }
                .filterKeys { it.isNotEmpty() }
                .mapNotNull { (k, v) -> v?.let { k to it } }
                .toMap()
    }
}
