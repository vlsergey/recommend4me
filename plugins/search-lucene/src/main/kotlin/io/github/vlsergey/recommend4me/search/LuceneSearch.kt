package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Path
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.TermQuery
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

    override fun current(contentType: String): Boolean = indexes.of(contentType).current()

    override fun update(
        contentType: String,
        fingerprints: Map<ItemKey, Long>,
        documents: (Collection<ItemKey>) -> List<SearchDocument>,
        removed: Collection<ItemKey>,
    ): Int = indexes.of(contentType).update(fingerprints, documents, removed)

    /**
     * What [matches] needs of a search: the line's words, the language it is read as and the passage of every item
     * nearest by meaning.
     */
    private class State(val terms: List<String>, val language: String, val nearest: Map<ItemKey, SearchPassage>)

    override fun search(contentType: String, line: String, language: String, context: SearchContext): SearchResult {
        val q = line.trim().lowercase()
        // The interface's language, unless the line is written in letters of another ([LineLanguages])
        val read = LineLanguages.of(q, language)
        if (q.isEmpty()) return SearchResult(q, emptyList(), State(emptyList(), read, emptyMap()))
        val started = System.currentTimeMillis()
        val index = indexes.of(contentType)
        val scores = HashMap<ItemKey, Double>()
        val features = HashMap<ItemKey, HashMap<String, Double>>()

        // The application's, of its windows' full-text vectors read whole: the line's vector against every window's
        val nearest = context.nearest(q)
        // The function words of English and of the line's language
        val stops =
            CharArraySet(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET, true).apply { addAll(Stemmers.stopWords(read)) }
        val terms = q.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.length >= 2 && !stops.contains(it) }.distinct()
        index.search { searcher ->
            val fields = index.fields(searcher).searched()
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
        }
        val ranked = scores.entries.sortedByDescending { it.value }.take(MAX_RESULTS).map { it.key }
        log.info("'{}' in {}: {} items in {} ms", line, contentType, ranked.size, System.currentTimeMillis() - started)
        val passages = nearest.mapValues { it.value.passage }
        val kept = index.search { IndexKept.fingerprints(it, ranked.map { k -> k.toString() }) }
        val prints = ranked.mapNotNull { key -> kept[key.toString()]?.let { key to it } }.toMap()
        return SearchResult(
            q,
            ranked,
            State(terms, read, passages),
            passages,
            prints,
            ranked.associateWith { features[it].orEmpty() },
        )
    }

    /**
     * What to show of every item of [keys]: the piece of its text where the words stand together, or — found by
     * meaning, its words apart or missing — the passage of it nearest by meaning, whole ([MeaningSearch.passageOf]), or
     * else the piece where a word is. The items' documents are read in one go.
     */
    override fun matches(
        contentType: String,
        result: SearchResult,
        keys: Collection<ItemKey>,
        context: SearchContext,
    ): Map<ItemKey, SearchMatch> {
        val state = result.state as? State ?: return emptyMap()
        val documents = context.documents(keys)
        val byWords = HashMap<ItemKey, SearchMatch>()
        val byMeaning = HashMap<ItemKey, SearchMatch>()
        indexes.of(contentType).search { searcher ->
            val stemmed = IndexKept.languages(searcher, keys.map { k -> k.toString() })
            keys.forEach { key ->
                val doc = documents[key] ?: return@forEach
                // The chapters the document named alone: looked into where the index holds the words of the item
                val unread =
                    Unread(
                        holds = { name, query ->
                            val ofItem =
                                BooleanQuery.Builder()
                                    .add(TermQuery(Term(LuceneIndex.ID, key.toString())), BooleanClause.Occur.FILTER)
                                    .add(query, BooleanClause.Occur.MUST)
                                    .build()
                            searcher.count(ofItem) > 0
                        },
                        texts = { fields -> context.texts(key, fields.map { it.key }) },
                    )
                val passage = MeaningSearch.passageOf(state.nearest[key], doc)
                val words =
                    WorkSnippet(state.terms, state.language, doc, stemmed[key.toString()], unread, passage == null)
                        .found()
                if (words != null)
                    byWords[key] = SearchMatch(words.first.key, false, words.second.text, words.second.highlights)
                // The words apart did not make the item a match, its meaning did: the passage nearest by meaning is
                // shown
                if (words?.third != true && passage != null) byMeaning[key] = passage
            }
        }
        return byWords + byMeaning
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
