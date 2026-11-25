package io.github.vlsergey.recommend4me.fulltext

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.part.PieceRepository
import io.github.vlsergey.recommend4me.search.SearchDocuments
import io.github.vlsergey.recommend4me.search.SearchNearest
import io.github.vlsergey.recommend4me.search.SearchPassage
import io.github.vlsergey.recommend4me.source.TypeStore
import java.util.PriorityQueue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(FullTextNearest::class.java)

/**
 * THE ITEMS NEAREST TO A LINE BY MEANING, of the full-text vectors of every window of every chapter and text searched
 * by meaning of a type ([FullTextVectors]) read whole, one source after another in the order of its store — no graph
 * and no index of them: one pass over the files of the store, what the disk of the data reads well; the vectors of the
 * windows in a graph of Lucene's were minutes of random reads a search (2026-11-25). The [PASSAGES] windows of the best
 * scores, the nearest first, and every item by its best.
 */
@Component
class FullTextNearest(private val centres: FullTextCentres) {

    /** The windows of the [type] nearest to the line's vector [q], and its items by them; none of no centre learnt. */
    fun nearest(type: TypeStore, q: FloatArray): FullTextFound {
        val centre =
            centres.of(type)?.takeIf { it.centre.size == q.size } ?: return FullTextFound(emptyMap(), emptyList())
        val started = System.currentTimeMillis()
        val query = FullTextVectors.query(q, centre)
        val best = PriorityQueue<NearWindow>(compareBy { it.score })
        var windows = 0L
        var unread = 0
        type.sources.forEach { s ->
            s.parts.fullText.forEach { item, text, packed ->
                if (!FullTextVectors.readable(packed, query)) {
                    unread++
                    return@forEach
                }
                FullTextVectors.forEachWindow(packed) { from, to, at ->
                    windows++
                    val score = query.score(packed, at)
                    if (best.size < PASSAGES || score > best.peek().score) {
                        if (best.size == PASSAGES) best.poll()
                        best += NearWindow(ItemKey(s.id, item), text, from, to, score)
                    }
                }
            }
        }
        // Of a type grown to twice what its centre was learnt with, one learnt anew
        centres.read(type, windows)
        val nearest = best.sortedByDescending { it.score }
        val out = LinkedHashMap<ItemKey, SearchNearest>()
        nearest.forEach { w -> out.putIfAbsent(w.key, SearchNearest(w.score.toDouble(), w.passage)) }
        log.info(
            "{}: {} windows read by their full-text vectors ({} texts of another format left till made anew), " +
                "{} items nearest, in {} ms",
            type.id,
            windows,
            unread,
            out.size,
            System.currentTimeMillis() - started,
        )
        return FullTextFound(out, nearest)
    }

    companion object {
        /**
         * The windows kept of a pass: of the 2 000 items the closeness of the search reaches, four windows each — a
         * window half a page, overlapping the next by half, the nearest often of one chapter.
         */
        const val PASSAGES = 8_000

        /** The field of a document of the text [text] of the store: a text field by its key, a chapter by its part. */
        fun fieldOf(text: String): String =
            if (text.startsWith(PieceRepository.textKey(""))) text.removePrefix(PieceRepository.textKey(""))
            else SearchDocuments.PART + text
    }
}

/** What a line found by the full-text vectors: the items by their nearest windows, the windows the nearest first. */
class FullTextFound(val items: Map<ItemKey, SearchNearest>, val windows: List<NearWindow>)

/**
 * A window found near a line: its item, the text of the store it is of ([text]: a chapter by its part, a text by
 * [PieceRepository.textKey]), its characters in that text, its score.
 */
class NearWindow(val key: ItemKey, val text: String, val from: Int, val to: Int, val score: Float) {
    /** The window as a passage of the item's document. */
    val passage
        get() = SearchPassage(FullTextNearest.fieldOf(text), from, to)
}
