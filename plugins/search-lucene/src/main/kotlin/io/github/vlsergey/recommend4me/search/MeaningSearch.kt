package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.KnnFloatVectorQuery
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(MeaningSearch::class.java)

/** The passage of an item nearest to the query by meaning, and its cosine. */
internal class Nearest(val cosine: Double, val passage: SearchPassage)

/**
 * THE SEARCH BY MEANING: the line a vector of the text encoder — in any language, a Russian line finds English
 * overviews — compared with the vector of every passage of every item; an item counts with its closest passage.
 * "corruption of a nun" finds a game whose overview never says "corruption".
 */
internal object MeaningSearch {

    /**
     * The items nearest to the query's vector [q] by meaning: the [PASSAGES] passages nearest — of descriptions, of
     * notes, of chapters — an item by its nearest passage.
     */
    fun nearest(searcher: IndexSearcher, q: FloatArray): Map<ItemKey, Nearest> {
        val out = HashMap<ItemKey, Nearest>()
        val stored = searcher.storedFields()
        val hits =
            try {
                searcher
                    .search(KnnFloatVectorQuery(LuceneIndex.VECTOR, LuceneIndex.unit(q), PASSAGES), PASSAGES)
                    .scoreDocs
            } catch (e: IllegalArgumentException) {
                // Vectors of another dimension: an encoder changed and the index is not made again yet
                log.debug("passages are not searched: {}", e.message)
                return out
            }
        hits.forEach { hit ->
            // The score of a dot product is (1 + cos) / 2
            val cosine = 2.0 * hit.score - 1.0
            val doc = stored.document(hit.doc)
            val key = ItemKey.parse(doc.get(LuceneIndex.ID))
            if ((out[key]?.cosine ?: Double.NEGATIVE_INFINITY) < cosine) {
                val passage =
                    SearchPassage(
                        doc.get(LuceneIndex.PASSAGE_FIELD),
                        doc.getField(LuceneIndex.PASSAGE_FROM).numericValue().toInt(),
                        doc.getField(LuceneIndex.PASSAGE_TO).numericValue().toInt(),
                    )
                out[key] = Nearest(cosine, passage)
            }
        }
        return out
    }

    /**
     * How close the items nearest to the query by meaning are, ON THE QUERY'S OWN SCALE: 0 at the [NEAREST]th nearest
     * item — or at the median one of a smaller catalogue — 1 at the top percent of the catalogue of [size] items; the
     * items farther than that are not matches by meaning. Raw cosines are no scale at all — e5 puts nearly every pair
     * of texts between 0.7 and 0.9 — and a fixed threshold either lets everything through or nothing.
     */
    fun closeness(found: Map<ItemKey, Nearest>, size: Int): Map<ItemKey, Double> {
        val nearest = found.mapValues { it.value.cosine }
        if (nearest.size < 2) return nearest
        val sorted = nearest.values.sortedDescending()
        val floor = sorted[minOf(NEAREST, size / 2, sorted.size).coerceAtLeast(1) - 1]
        val top = sorted[(size / 100).coerceIn(0, sorted.size - 1)]
        val span = (top - floor).coerceAtLeast(1e-6)
        return nearest.mapValues { (_, cos) -> ((cos - floor) / span).coerceIn(0.0, MAX_CLOSENESS) }
    }

    /**
     * What the work [doc] was found by, by meaning: the [passage] of it nearest to the line, whole — the text whose
     * vector was near, not a sentence of it picked apart, which may say less of the line than the whole. Null of a work
     * found by no passage, or of a passage of no text.
     */
    fun passageOf(passage: SearchPassage?, doc: SearchDocument): SearchMatch? {
        val text = passage?.text(doc)?.trim().orEmpty()
        return if (passage == null || text.isEmpty() || doc.fields.none { it.key == passage.field }) null
        else SearchMatch(passage.field, true, text, emptyList())
    }

    /** The items nearest by meaning that are asked for; the farther ones are no matches by meaning. */
    private const val NEAREST = 2000

    /**
     * The passages asked for, of the [NEAREST] items: a passage of a scene, half as long as a page and overlapping the
     * next by half, four of them stand where one page did — and the nearest are often of one chapter.
     */
    private const val PASSAGES = 4 * NEAREST

    /** The few items above the top percent are not told apart further than this. */
    const val MAX_CLOSENESS = 1.5
}
