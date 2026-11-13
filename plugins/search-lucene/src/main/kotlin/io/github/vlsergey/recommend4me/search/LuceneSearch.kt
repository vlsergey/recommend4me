package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Path
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(LuceneSearch::class.java)

/**
 * Search by a line of text over everything known about an item, by meaning ([MeaningSearch]) and by words
 * ([WordSearch]) at once; and the names the works' own words say, counted of the index ([LuceneNames]).
 *
 * Both are asked of one Lucene index per content type ([LuceneIndex]) in `<data-dir>/types/<type>/search-index`.
 */
class LuceneSearch private constructor(private val indexes: LuceneIndexes) :
    SearchProvider, NameCounting by LuceneNames(indexes), AutoCloseable {

    constructor(dataDir: Path) : this(LuceneIndexes(dataDir))

    override val id = "lucene"

    override fun rebuild(contentType: String, documents: Sequence<SearchDocument>) {
        val started = System.currentTimeMillis()
        val count = indexes.of(contentType).rebuild(documents)
        log.info("search index of {}: {} items made in {} ms", contentType, count, System.currentTimeMillis() - started)
    }

    override fun sync(
        contentType: String,
        fingerprints: Map<ItemKey, Long>,
        documents: (Collection<ItemKey>) -> Sequence<SearchDocument>,
    ) {
        val started = System.currentTimeMillis()
        val written = indexes.of(contentType).sync(fingerprints, documents)
        log.info(
            "search index of {}: {} items held against their fingerprints, {} of them written again, in {} ms",
            contentType,
            fingerprints.size,
            written,
            System.currentTimeMillis() - started,
        )
    }

    override fun update(contentType: String, documents: List<SearchDocument>, removed: Collection<ItemKey>) {
        indexes.of(contentType).update(documents, removed)
    }

    /**
     * What [matches] needs of a search: the line's words, the language it is read as, its vector and the passage of
     * every item nearest by meaning.
     */
    private class State(
        val terms: List<String>,
        val language: String,
        val vector: FloatArray?,
        val nearest: Map<ItemKey, SearchPassage>,
    )

    override fun search(contentType: String, line: String, language: String, context: SearchContext): SearchResult {
        val q = line.trim().lowercase()
        // The interface's language, unless the line is written in letters of another ([LineLanguages])
        val read = LineLanguages.of(q, language)
        if (q.isEmpty()) return SearchResult(q, emptyList(), State(emptyList(), read, null, emptyMap()))
        val started = System.currentTimeMillis()
        val index = indexes.of(contentType)
        val scores = HashMap<ItemKey, Double>()
        val features = HashMap<ItemKey, HashMap<String, Double>>()

        val vector = context.queryVector(q)
        // The function words of English and of the line's language
        val stops =
            CharArraySet(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET, true).apply { addAll(Stemmers.stopWords(read)) }
        val terms = q.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.length >= 2 && !stops.contains(it) }.distinct()
        val nearest = index.search { searcher ->
            val fields = index.fields(searcher).searched()
            val nearest = vector?.takeIf { fields.vectors }?.let { MeaningSearch.nearest(searcher, it) }.orEmpty()
            val semantic = MeaningSearch.closeness(nearest, index.items(searcher))
            semantic.forEach { (key, closeness) ->
                if (closeness >= SEMANTIC_FLOOR) scores[key] = SEMANTIC_WEIGHT * closeness
                features.getOrPut(key) { HashMap() }[MEANING] = closeness
            }
            WordSearch.byWords(searcher, fields, q, terms, read).forEach { (key, parts) ->
                // An item found by words still keeps its meaning score, so the two add up
                scores[key] =
                    (scores[key] ?: (SEMANTIC_WEIGHT * (semantic[key] ?: 0.0))) + WORD_WEIGHT * parts.values.sum()
                features.getOrPut(key) { HashMap() } += parts
            }
            nearest
        }
        val ranked = scores.entries.sortedByDescending { it.value }.take(MAX_RESULTS).map { it.key }
        log.info("'{}' in {}: {} items in {} ms", line, contentType, ranked.size, System.currentTimeMillis() - started)
        val passages = nearest.mapValues { it.value.passage }
        val kept = index.search { IndexKept.fingerprints(it, ranked.map { k -> k.toString() }) }
        val prints = ranked.mapNotNull { key -> kept[key.toString()]?.let { key to it } }.toMap()
        return SearchResult(
            q,
            ranked,
            State(terms, read, vector, passages),
            passages,
            prints,
            ranked.associateWith { features[it].orEmpty() },
        )
    }

    /**
     * What to show of every item of [keys]: the piece of its text where the words stand together, or — found by
     * meaning, its words apart or missing — the phrase of its text nearest by meaning that is nearest to the line, or
     * else the piece where a word is. The items' documents are read in one go, the phrases' vectors in one batch.
     */
    override fun matches(
        contentType: String,
        result: SearchResult,
        keys: Collection<ItemKey>,
        context: SearchContext,
    ): Map<ItemKey, SearchMatch> {
        val state = result.state as? State
        val documents = state?.let { context.documents(keys) }.orEmpty()
        val stemmed =
            if (state == null) emptyMap()
            else indexes.of(contentType).search { IndexKept.languages(it, keys.map { k -> k.toString() }) }
        val byWords = HashMap<ItemKey, SearchMatch>()
        val byMeaning = HashMap<ItemKey, Pair<SearchField, List<String>>>()
        if (state != null)
            keys.forEach { key ->
                val doc = documents[key] ?: return@forEach
                val words = WordSearch.snippet(state.terms, state.language, doc, stemmed[key.toString()])
                if (words != null)
                    byWords[key] = SearchMatch(words.first.key, false, words.second.text, words.second.highlights)
                // The words apart did not make the item a match, its meaning did: the phrase of the passage nearest by
                // meaning is shown
                if (words?.third != true) MeaningSearch.phrasesOf(state.nearest[key], doc)?.let { byMeaning[key] = it }
            }
        val q = state?.vector
        return if (q == null) byWords else byWords + MeaningSearch.nearestPhrases(q, byMeaning, context)
    }

    override fun close() = indexes.close()

    companion object {
        private const val MAX_RESULTS = 500

        /** Closer than this to the top percent than to the zero of the scale, or it is not a match by meaning. */
        private const val SEMANTIC_FLOOR = 0.5
        private const val SEMANTIC_WEIGHT = 1.0

        /** The feature of an item found by meaning: how close it is to the line ([MeaningSearch.closeness]). */
        const val MEANING = "meaning"
        private const val WORD_WEIGHT = 0.6
    }
}
