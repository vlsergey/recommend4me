package io.github.vlsergey.recommend4me.source.authortoday

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.SourceContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * The user's own marks of a work as the site shows them to the user logged in: the shelf of the library — on a card and
 * a work's page — and, on a work's page, the like, the reactions and the work hidden.
 */
internal class AuthorTodayMarks(private val context: SourceContext) {

    /** The shelf the library button [button] of the work [id] shows, if it shows one. */
    fun library(id: String, button: Element?) {
        button?.attr("params")?.let { LIBRARY_STATE.find(it)?.groupValues?.get(1) }?.let { shelf(id, it) }
    }

    /**
     * The marks of the work [id] its page shows: the shelf, the like ([like], the page's like button as found in its
     * [html]), the reactions and the work hidden.
     */
    fun ofWork(id: String, doc: Document, html: String, like: MatchResult?) {
        library(id, doc.selectFirst(".book-action-panel library-button, library-button"))
        like?.let {
            context.signals.set(id, Books.LIKED.key, Books.YES.key.takeIf { _ -> it.groupValues[2] != "null" })
        }
        MARKS.find(html)?.groupValues?.get(1)?.let { marks ->
            val values =
                Regex("\"?([A-Za-z]+)\"?").findAll(marks).map { it.groupValues[1] }.filter { it != "null" }.toList()
            context.signals.set(id, AuthorToday.SIGNAL_REACTION, values.sorted().joinToString(",").ifEmpty { null })
        }
        HIDDEN.find(html)?.groupValues?.get(1)?.let {
            context.signals.set(id, AuthorToday.SIGNAL_HIDDEN, if (it == "true") "yes" else null)
        }
    }

    /**
     * The shelf of the user's library the work is on — "None" when on none — as the standard one (a shelf the standard
     * does not know as the site names it), and of the shelf of the finished the standard "read".
     */
    private fun shelf(id: String, shelf: String) {
        val standard = SHELVES[shelf]
        context.signals.set(id, Books.SHELF.key, standard?.key ?: shelf.takeIf { it != "None" })
        context.signals.set(id, Books.READ.key, Books.YES.key.takeIf { standard == Books.Shelf.FINISHED })
    }

    private companion object {
        val LIBRARY_STATE = Regex("state:\\s*'([A-Za-z]+)'")
        val MARKS = Regex("\"workMarks\":(\\[[^\\]]*]|null)")
        val HIDDEN = Regex("\"isDisliked\":(true|false)")

        /** The shelves of the user's library as the site names them, to the standard ones. */
        val SHELVES =
            mapOf(
                "Reading" to Books.Shelf.READING,
                "Saved" to Books.Shelf.SAVED,
                "Finished" to Books.Shelf.FINISHED,
                "Disliked" to Books.Shelf.DISLIKED,
                "Purchased" to Books.Shelf.PURCHASED,
            )
    }
}
