package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(LuceneSearch::class.java)

/**
 * Search by a line of text over everything known about an item, by meaning ([MeaningSearch]) and by words
 * ([WordSearch]) at once; and the names the works' own words say, counted of the index ([NameReading]).
 *
 * Both are asked of one Lucene index per content type ([LuceneIndex]) in `<data-dir>/types/<type>/search-index`.
 */
class LuceneSearch(private val dataDir: Path) : SearchProvider, AutoCloseable {

    override val id = "lucene"

    /** The open indexes — files, not data: a writer is opened once and held. */
    private val indexes = ConcurrentHashMap<String, LuceneIndex>()

    private fun index(contentType: String): LuceneIndex =
        indexes.computeIfAbsent(contentType) {
            LuceneIndex(dataDir.resolve("types").resolve(it).resolve("search-index"))
        }

    override fun rebuild(contentType: String, documents: Sequence<SearchDocument>) {
        val started = System.currentTimeMillis()
        val count = index(contentType).rebuild(documents)
        log.info("search index of {}: {} items made in {} ms", contentType, count, System.currentTimeMillis() - started)
    }

    override fun sync(contentType: String, documents: Sequence<SearchDocument>) {
        val started = System.currentTimeMillis()
        var read = 0
        val written = index(contentType).sync(documents.onEach { read++ })
        log.info(
            "search index of {}: {} items read, {} of them written again, in {} ms",
            contentType,
            read,
            written,
            System.currentTimeMillis() - started,
        )
    }

    override fun update(contentType: String, documents: List<SearchDocument>, removed: Collection<ItemKey>) {
        index(contentType).update(documents, removed)
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
        val index = index(contentType)
        val scores = HashMap<ItemKey, Double>()

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
            }
            WordSearch.byWords(searcher, fields, q, terms, read).forEach { (key, score) ->
                // An item found by words still keeps its meaning score, so the two add up
                scores[key] = (scores[key] ?: (SEMANTIC_WEIGHT * (semantic[key] ?: 0.0))) + WORD_WEIGHT * score
            }
            nearest
        }
        val ranked = scores.entries.sortedByDescending { it.value }.take(MAX_RESULTS).map { it.key }
        log.info("'{}' in {}: {} items in {} ms", line, contentType, ranked.size, System.currentTimeMillis() - started)
        val passages = nearest.mapValues { it.value.passage }
        return SearchResult(q, ranked, State(terms, read, vector, passages), passages)
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
        val byWords = HashMap<ItemKey, SearchMatch>()
        val byMeaning = HashMap<ItemKey, Pair<SearchField, List<String>>>()
        if (state != null)
            keys.forEach { key ->
                val doc = documents[key] ?: return@forEach
                val words = WordSearch.snippet(state.terms, state.language, doc)
                if (words != null)
                    byWords[key] = SearchMatch(words.first.key, false, words.second.text, words.second.highlights)
                // The words apart did not make the item a match, its meaning did: the phrase of the passage nearest by
                // meaning is shown
                if (words?.third != true) MeaningSearch.phrasesOf(state.nearest[key], doc)?.let { byMeaning[key] = it }
            }
        val q = state?.vector
        return if (q == null) byWords else byWords + MeaningSearch.nearestPhrases(q, byMeaning, context)
    }

    /** The names counted of the index of [contentType] for every asking of [asked] ([NameReading]). */
    private fun named(contentType: String, asked: List<NamesAsked>): List<Map<ItemKey, List<FoundIn>>> {
        val index = index(contentType)
        return index.search { searcher -> NameReading(index, searcher, asked).found() }
    }

    override fun mentions(
        contentType: String,
        items: Collection<ItemKey>?,
        names: Map<String, List<String>>,
    ): Map<ItemKey, Map<String, Int>> = mentions(contentType, listOf(NamesAsked(items, names))).single()

    override fun mentions(contentType: String, asked: List<NamesAsked>): List<Map<ItemKey, Map<String, Int>>> =
        named(contentType, asked).map { byItem ->
            byItem.mapValues { (_, found) -> found.groupingBy { it.value }.eachCount() }
        }

    override fun together(
        contentType: String,
        items: Collection<ItemKey>?,
        names: Map<String, List<String>>,
    ): Map<ItemKey, Map<Pair<String, String>, Int>> = together(contentType, listOf(NamesAsked(items, names))).single()

    override fun together(
        contentType: String,
        asked: List<NamesAsked>,
    ): List<Map<ItemKey, Map<Pair<String, String>, Int>>> =
        named(contentType, asked).map { byItem ->
            byItem
                .mapValues { (_, found) ->
                    val out = HashMap<Pair<String, String>, Int>()
                    found
                        .groupBy { it.field to it.paragraph }
                        .values
                        .forEach { paragraph ->
                            val each = paragraph.map { it.value }.toSortedSet().toList()
                            each.forEachIndexed { k, a ->
                                each.drop(k + 1).forEach { b -> out.merge(a to b, 1, Int::plus) }
                            }
                        }
                    out
                }
                .filterValues { it.isNotEmpty() }
        }

    /**
     * The places of the values [of] in the document's own words, found by the rules of [mentions] over its texts read
     * again as they were put in the index.
     */
    override fun places(
        document: SearchDocument,
        names: Map<String, List<String>>,
        of: Collection<String>,
    ): List<NamePlace> {
        val wanted = of.toHashSet()
        val matcher = NameMatcher(names, LuceneIndex.languagesOf(document))
        return document.fields
            .filter { it.counted }
            .flatMap { field ->
                field.values
                    .filter { it.isNotBlank() }
                    .mapNotNull { text ->
                        val ranges =
                            foundIn(text, document, matcher)
                                .filter { it.value in wanted }
                                .map { it.first.start until it.last.end }
                        if (ranges.isEmpty()) null else NamePlace(field.key, text, ranges)
                    }
            }
    }

    /**
     * The places of the two values [a] and [b] named in one paragraph of the document's own words, as [together] counts
     * them: of every value of a field, the names of both in the paragraphs naming both.
     */
    override fun placesTogether(
        document: SearchDocument,
        names: Map<String, List<String>>,
        a: String,
        b: String,
    ): List<NamePlace> {
        val matcher = NameMatcher(names, LuceneIndex.languagesOf(document))
        return document.fields
            .filter { it.counted }
            .flatMap { field ->
                field.values
                    .filter { it.isNotBlank() }
                    .mapNotNull { text ->
                        // A paragraph ends at a line break, as the marks of the index have it
                        val found = foundIn(text, document, matcher).filter { it.value == a || it.value == b }
                        val breaks = text.indices.filter { text[it] == '\n' }
                        fun paragraphOf(at: Int) = breaks.binarySearch(at).let { if (it >= 0) it else -it - 1 }
                        val ranges =
                            found
                                .groupBy { paragraphOf(it.first.start) }
                                .values
                                .filter { inOne -> inOne.any { it.value == a } && inOne.any { it.value == b } }
                                .flatten()
                                .map { it.first.start until it.last.end }
                        if (ranges.isEmpty()) null else NamePlace(field.key, text, ranges.sortedBy { it.first })
                    }
            }
    }

    override fun close() {
        indexes.values.forEach { it.close() }
    }

    companion object {
        private const val MAX_RESULTS = 500

        /** Closer than this to the top percent than to the zero of the scale, or it is not a match by meaning. */
        private const val SEMANTIC_FLOOR = 0.5
        private const val SEMANTIC_WEIGHT = 1.0
        private const val WORD_WEIGHT = 0.6

        /**
         * The names of a [text] of the document found again, read as it was put in the index: its words with their
         * characters.
         */
        private fun foundIn(text: String, document: SearchDocument, matcher: NameMatcher): List<Found> {
            val words = ArrayList<NamedWord>()
            Stemmers.analyzer(TextLanguages.of(text, document.language)).tokenStream("", text).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class.java)
                val step = stream.addAttribute(PositionIncrementAttribute::class.java)
                val payload = stream.addAttribute(PayloadAttribute::class.java)
                val offset = stream.addAttribute(OffsetAttribute::class.java)
                stream.reset()
                var position = -1
                while (stream.incrementToken()) {
                    position += step.positionIncrement
                    words +=
                        NamedWord(
                            term.toString(),
                            position,
                            payload.payload == Stemmers.CAPITAL,
                            offset.startOffset(),
                            offset.endOffset(),
                        )
                }
                stream.end()
            }
            return matcher.found(words)
        }
    }
}
