package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.authortoday.AuthorToday.Companion.BASE
import java.time.Instant
import org.jsoup.nodes.Document

/** The reader of the site: the text of a chapter, and the work it is of when the work is not known yet. */
internal class AuthorTodayReader(private val items: ItemStore) {

    /**
     * The text of a chapter of the work the site names [workId] as the reader shows it: the work's number. Nothing
     * while the reader has not loaded it yet.
     */
    fun chapter(doc: Document, workId: String, chapterId: String, now: Instant): Long? {
        val container = doc.selectFirst("#text-container")
        val heading = container?.selectFirst("h1")?.text()?.trim()
        val text = container?.let { PageText.of(it, "h1, script, style") }
        val id = if (text.isNullOrBlank()) null else known(doc, workId, heading, now)
        if (text.isNullOrBlank() || id == null) return null
        items.saveParts(id, listOf(PartData(chapterId, -1, heading, text)))
        return id
    }

    /**
     * The number of the work if it is known — or made known by the reader's page, opened before the work's: its title
     * is the page's. No title, no work.
     */
    private fun known(doc: Document, workId: String, heading: String?, now: Instant): Long? {
        items.idOf(workId)?.let {
            return it
        }
        // The reader before the work's page: the title is the page's, "Книга <title>, <chapter>, <author> читать
        // онлайн"
        val title =
            doc.title()
                .removePrefix("Книга ")
                .substringBefore(", ${heading ?: "\u0000"}")
                .substringBefore(" читать онлайн")
                .trim()
        return if (title.isEmpty()) null else items.upsert(ItemHead(workId, "$BASE/work/$workId", title), now).id
    }
}
