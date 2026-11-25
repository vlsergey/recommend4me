package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.part.PieceRepository
import io.github.vlsergey.recommend4me.piece.PieceProjections
import io.github.vlsergey.recommend4me.piece.PieceSpans
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SearchPieces::class.java)

/**
 * THE TEXTS OF THE WORKS FOUND READ BY THEIR PIECES (docs/search.md, "The pieces"): a window's vector is a few hundred
 * pieces squeezed into one, and a phrase of a long chapter is lost in it; the line's every piece held against every
 * piece of the text finds where it is said. Of the works found, the first [READ] by the order so far that were found by
 * meaning: the codes of the pieces of the text of the passage nearest to the line — a chapter, a text field — read of
 * the store of the parts, a text a read; the line's pieces, made by the encoder of the type's projection on the thread
 * of the request (one short line, as its vector), against them: the best span of the text ([PieceSpans]). Its score is
 * the work's feature [PIECES], weighed with the others ([SearchWeights]); the span is what is shown of it
 * ([SearchResult.places]). Of a type with no projection of the pieces, or no encoder of them, nothing.
 */
@Component
class SearchPieces(private val stores: Stores, private val projections: PieceProjections) {

    /** What was [found] in the [type], the pieces of the first [READ] of its texts read against the line's. */
    fun read(type: TypeStore, found: SearchResult): SearchResult {
        val started = System.currentTimeMillis()
        val fitted = projections.of(type)
        val line = fitted?.let { f -> projections.encoder()?.query(found.line, f.projection) }.orEmpty()
        if (fitted == null || line.isEmpty()) return found
        val scores = HashMap<ItemKey, Double>()
        val places = HashMap<ItemKey, SearchPlace>()
        val read = found.keys.filter { it in found.nearest }.take(READ)
        read
            .groupBy { it.source }
            .forEach { (source, keys) ->
                val s = stores.source(source) ?: return@forEach
                keys.forEach { key ->
                    val field = found.nearest.getValue(key).field
                    val text = textOf(field)
                    val span =
                        s.parts.pieces.of(key.id, listOf(text))[text]?.let { codes ->
                            PieceSpans.best(line, codes, fitted.codebook)
                        }
                    if (span != null) {
                        scores[key] = span.score
                        places[key] = SearchPlace(field, span.from, span.to, span.marked)
                    }
                }
            }
        log.info(
            "'{}' in {}: the pieces of {} texts of {} works read in {} ms",
            found.line,
            type.id,
            scores.size,
            read.size,
            System.currentTimeMillis() - started,
        )
        val features =
            found.keys.associateWith { key ->
                found.features[key].orEmpty() + scores[key]?.let { mapOf(PIECES to it) }.orEmpty()
            }
        return SearchResult(found.line, found.keys, found.state, found.nearest, found.prints, features, places)
    }

    companion object {
        /** The feature of a work found: the score of the best span of its text by the pieces. */
        const val PIECES = "pieces"

        /**
         * The works found whose texts' pieces a search reads: a read of the store each, about 10 ms on the disk of the
         * data — 3 s at most. Of 85 retellings the answer was among the nearest 300 passages 67 times of 85.
         */
        const val READ = 300

        /** The text of the pieces of a field of a document: a chapter by its part's id, a text field by its key. */
        fun textOf(field: String): String =
            if (field.startsWith(SearchDocuments.PART)) field.removePrefix(SearchDocuments.PART)
            else PieceRepository.textKey(field)
    }
}
