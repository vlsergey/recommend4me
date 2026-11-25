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
 * scores, an item by its best.
 */
@Component
class FullTextNearest(private val centres: FullTextCentres) {

    /** The items of the [type] nearest to the line's vector [q], their windows nearest; none of no centre learnt. */
    fun nearest(type: TypeStore, q: FloatArray): Map<ItemKey, SearchNearest> {
        val centre = centres.of(type)?.centre?.takeIf { it.size == q.size } ?: return emptyMap()
        val started = System.currentTimeMillis()
        val query = FullTextVectors.query(q, centre)
        val best = PriorityQueue<Found>(compareBy { it.score })
        var windows = 0L
        type.sources.forEach { s ->
            s.parts.fullText.forEach { item, text, packed ->
                FullTextVectors.forEachWindow(packed) { from, to, at ->
                    windows++
                    val score = query.score(packed, at)
                    if (best.size < PASSAGES || score > best.peek().score) {
                        if (best.size == PASSAGES) best.poll()
                        best += Found(ItemKey(s.id, item), text, from, to, score)
                    }
                }
            }
        }
        val out = LinkedHashMap<ItemKey, SearchNearest>()
        best
            .sortedByDescending { it.score }
            .forEach { f ->
                out.putIfAbsent(f.key, SearchNearest(f.score.toDouble(), SearchPassage(fieldOf(f.text), f.from, f.to)))
            }
        log.info(
            "{}: {} windows read by their full-text vectors, {} items nearest, in {} ms",
            type.id,
            windows,
            out.size,
            System.currentTimeMillis() - started,
        )
        return out
    }

    /** A window found: its item, its text, its characters, its score. */
    private class Found(val key: ItemKey, val text: String, val from: Int, val to: Int, val score: Float)

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
