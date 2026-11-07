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
     * The items nearest to the query's vector [q] by meaning: the [NEAREST] passages nearest — of descriptions, of
     * notes, of chapters — an item by its nearest passage.
     */
    fun nearest(searcher: IndexSearcher, q: FloatArray): Map<ItemKey, Nearest> {
        val out = HashMap<ItemKey, Nearest>()
        val stored = searcher.storedFields()
        val hits =
            try {
                searcher
                    .search(KnnFloatVectorQuery(LuceneIndex.VECTOR, LuceneIndex.unit(q), NEAREST), NEAREST)
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
     * The field and the phrases of the [passage] of the work [doc] nearest by meaning; null of a work found by no
     * passage, or of a passage of no text. A passage of no phrase long enough — a list of tags, of titles — is given as
     * it is: a work found is never shown without what it was found by.
     */
    fun phrasesOf(passage: SearchPassage?, doc: SearchDocument): Pair<SearchField, List<String>>? {
        val field = passage?.let { p -> doc.fields.firstOrNull { it.key == p.field } }
        val text = passage?.text(doc)?.trim().orEmpty()
        val phrases =
            Phrases.split(text)
                .filter { it.length >= MIN_PHRASE }
                .ifEmpty { listOfNotNull(text.takeIf { it.isNotEmpty() }) }
        return if (field == null || phrases.isEmpty()) null else field to phrases
    }

    /** Of every work's phrases [byMeaning], the one nearest to the line's vector [q] as its match. */
    fun nearestPhrases(
        q: FloatArray,
        byMeaning: Map<ItemKey, Pair<SearchField, List<String>>>,
        context: SearchContext,
    ): Map<ItemKey, SearchMatch> {
        val vectors = context.phraseVectors(byMeaning.values.flatMap { it.second }.toSet())
        return byMeaning.mapValues { (_, value) ->
            val (field, phrases) = value
            val best = phrases.maxBy { phrase ->
                vectors[phrase]?.let { v -> q.indices.sumOf { k -> q[k].toDouble() * v[k] } } ?: -1.0
            }
            SearchMatch(field.key, true, best.take(MEANING_SNIPPET), emptyList())
        }
    }

    /** The items nearest by meaning that are asked for; the farther ones are no matches by meaning. */
    private const val NEAREST = 2000

    /** How much of a text nearest by meaning is shown. */
    private const val MEANING_SNIPPET = 200

    /** Shorter phrases — "Download", a date — say nothing of what an item is about. */
    private const val MIN_PHRASE = 20

    /** The few items above the top percent are not told apart further than this. */
    const val MAX_CLOSENESS = 1.5
}
