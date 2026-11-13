package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.ConstantScoreQuery
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.uhighlight.Passage
import org.apache.lucene.search.uhighlight.PassageFormatter
import org.apache.lucene.search.uhighlight.UnifiedHighlighter

/**
 * THE SEARCH BY WORDS: the words of the line found — as words, in any of their forms, or as the beginnings of words —
 * in the title, the facets and the texts, a field weighing what a match there says; with a bonus when a naming field
 * (the title, the author) holds the whole line. A name the encoder never saw — a developer, a character — is found this
 * way. And the piece of a work's text where the words are found, the words marked.
 */
internal object WordSearch {

    /**
     * Every item matching the [terms] by words, with its score. An item matches when it has all the words, anywhere in
     * it — of a long query all but a few ([required]): one word of two is a coincidence ("Stolen Memories" for "stolen
     * clothes"), and such an item is left to the search by meaning. Its score: for every term found — in any of its
     * forms, or a name typed in part — the weight of the best field it is found in, averaged over the terms, and of
     * several terms only [APART] of that; plus [TOGETHER] times the weight of the best field where they all stand
     * together — within [NEAR] words of each other, in any order; plus [NAME_BONUS] when the whole [line] stands as a
     * phrase in a naming field — or, a single word typed in part, begins a word of it.
     */
    fun byWords(
        searcher: IndexSearcher,
        fields: LuceneIndex.Fields,
        line: String,
        terms: List<String>,
        language: String,
    ): Map<ItemKey, Double> {
        val query =
            if (fields.text.isEmpty()) null
            else query(fields, line, terms, language).takeIf { it.clauses().isNotEmpty() }
        return query?.let { q ->
            val hits = searcher.search(q, searcher.indexReader.maxDoc().coerceAtLeast(1))
            val stored = searcher.storedFields()
            hits.scoreDocs.associate { hit ->
                ItemKey.parse(stored.document(hit.doc).get(LuceneIndex.ID)) to hit.score.toDouble()
            }
        } ?: emptyMap()
    }

    /** The query of [byWords]: the terms apart, the words together, the whole line in a naming field. */
    private fun query(fields: LuceneIndex.Fields, line: String, terms: List<String>, language: String): BooleanQuery {
        val query = BooleanQuery.Builder()
        val termQueries = terms.mapNotNull { termQuery(fields, it, language) }
        if (termQueries.isNotEmpty()) {
            val byWords = BooleanQuery.Builder().setMinimumNumberShouldMatch(required(termQueries.size))
            termQueries.forEach { byWords.add(BoostQuery(it, 1f / termQueries.size), BooleanClause.Occur.SHOULD) }
            query.add(BoostQuery(byWords.build(), if (termQueries.size > 1) APART else 1f), BooleanClause.Occur.SHOULD)
        }
        val all = terms.flatMap { LuceneIndex.tokens(it, language) }.distinct()
        if (all.size > 1) {
            val together =
                fields.text.map { field ->
                    val near =
                        PhraseQuery.Builder().setSlop(NEAR).apply { all.forEach { add(Term(field.name, it)) } }.build()
                    BoostQuery(ConstantScoreQuery(near), field.weight)
                }
            query.add(BoostQuery(DisjunctionMaxQuery(together, 0f), TOGETHER), BooleanClause.Occur.SHOULD)
        }
        val words = words(line, language)
        val naming = fields.text.filter { it.naming }
        if (words.isNotEmpty() && naming.isNotEmpty()) {
            val inName = naming.map { field ->
                val name =
                    if (words.size == 1) wordQuery(field.name, words.single())
                    else PhraseQuery(field.name, *words.map { it.token }.toTypedArray())
                ConstantScoreQuery(name)
            }
            query.add(BoostQuery(DisjunctionMaxQuery(inName, 0f), NAME_BONUS), BooleanClause.Occur.SHOULD)
        }
        return query.build()
    }

    /**
     * How many of [terms] words of a query an item must have: all of one or two, of a longer query all but every third.
     */
    private fun required(terms: Int): Int = if (terms <= 2) terms else terms - terms / EVERY_THIRD

    /** A word of the query as the index has it, and whether it may also find the words it begins. */
    data class Word(val token: String, val prefix: Boolean)

    /**
     * The words of a term of the query. A word finds the words it begins — a name typed in part, "herand" finds
     * "herandu" — only when the analyzer left it as it is, from [MIN_PREFIX] letters on: a word the stemmer changed is
     * a word of the language, whose other forms the stemmer brings to it already, and as a beginning it finds other
     * words — "stolen", read as "steal", found "stealth".
     */
    fun words(term: String, language: String): List<Word> {
        val parts = LuceneIndex.tokens(term, language)
        val asTyped = parts.size == 1 && parts.single() == term.lowercase() && term.length >= MIN_PREFIX
        return parts.map { Word(it, asTyped) }
    }

    private fun wordQuery(field: String, word: Word): Query {
        val exact = TermQuery(Term(field, word.token))
        return if (!word.prefix) exact
        else
            BooleanQuery.Builder()
                .add(exact, BooleanClause.Occur.SHOULD)
                .add(PrefixQuery(Term(field, word.token)), BooleanClause.Occur.SHOULD)
                .build()
    }

    /**
     * A term of the query, scored by the weight of the best field it is found in; a term the analyzer splits needs
     * every part in that field.
     */
    private fun termQuery(fields: LuceneIndex.Fields, term: String, language: String): Query? {
        val parts = words(term, language)
        if (parts.isEmpty()) return null
        val perField =
            fields.text.map { field ->
                val each = parts.map { part -> wordQuery(field.name, part) }
                val inField =
                    if (each.size == 1) each.single()
                    else BooleanQuery.Builder().apply { each.forEach { add(it, BooleanClause.Occur.MUST) } }.build()
                BoostQuery(ConstantScoreQuery(inField), field.weight)
            }
        // The best field alone counts: a term in the title and in the changelog is still one term found
        return DisjunctionMaxQuery(perField, 0f)
    }

    /**
     * Marks the words of a query in a text given to it, without the index — the texts are not stored in it — the text
     * read as its [language], as it was put in the index.
     */
    private fun highlighter(language: String) =
        UnifiedHighlighter.builderWithoutSearcher(Stemmers.analyzer(language))
            .withFormatter(SnippetFormatter())
            .withMaxNoHighlightPassages(0)
            // Not only the first 10 000 characters, the default: a changelog is longer
            .withMaxLength(MAX_TEXT)
            .build()

    /**
     * The piece of the item's text where the [terms] are found, with the words found marked: where they all stand
     * together, from the weightiest field that has them so — the place that made the item a match — or else from the
     * weightiest field that holds one; null when none is found. The third value: whether the words all stand together
     * there. The [terms] are of the [language]. [stemmed]: the languages the document's texts were stemmed as in the
     * index ([LuceneIndex.keptLanguages]) — of one alone, every text of it is read as that one, none told again.
     */
    fun snippet(
        terms: List<String>,
        language: String,
        doc: SearchDocument,
        stemmed: List<String>? = null,
    ): Triple<SearchField, Snippet, Boolean>? {
        val parts = terms.flatMap { words(it, language) }.distinct()
        if (parts.isEmpty()) return null
        val contents =
            doc.fields
                .sortedByDescending { it.weight }
                .mapNotNull { field ->
                    field.values
                        .filter { it.isNotBlank() }
                        .takeIf { it.isNotEmpty() }
                        ?.let { field to it.joinToString(if (field.list) ", " else "\n\n") }
                }
        val all = terms.flatMap { LuceneIndex.tokens(it, language) }.distinct()
        // A text read as it was put in the index: as its own language, or the work's — of a document stemmed as one
        // language, that one; else told once of a field, the words together and apart looked for in it as that one
        val languages = HashMap<String, String>()
        fun marked(field: SearchField, content: String, name: String, query: Query) =
            highlighter(
                    stemmed?.singleOrNull() ?: languages.getOrPut(field.key) { TextLanguages.of(content, doc.language) }
                )
                .highlightWithoutSearcher(name, query, content, 1) as Snippet?
        val together =
            if (all.size <= 1) null
            else
                contents.firstNotNullOfOrNull { (field, content) ->
                    val name = LuceneIndex.fieldName(field)
                    val near = PhraseQuery.Builder().setSlop(NEAR).apply { all.forEach { add(Term(name, it)) } }.build()
                    marked(field, content, name, near)?.let { Triple(field, it, true) }
                }
        return together
            ?: contents.firstNotNullOfOrNull { (field, content) ->
                val name = LuceneIndex.fieldName(field)
                val query =
                    BooleanQuery.Builder()
                        .apply { parts.forEach { part -> add(wordQuery(name, part), BooleanClause.Occur.SHOULD) } }
                        .build()
                marked(field, content, name, query)?.let { Triple(field, it, all.size <= 1) }
            }
    }

    private const val MAX_TEXT = 1_000_000

    /**
     * The whole line in a naming field: what is looked for, ahead of any likeness by meaning — WORD_WEIGHT · (1 +
     * NAME_BONUS) = 1.8 beats MAX_CLOSENESS = 1.5 ([LuceneSearch]). At 1.0 a developer's name, a word the encoder never
     * saw, lost to the items its vector happened to lie near.
     */
    private const val NAME_BONUS = 2.0f

    /** A word of the query left as typed finds the words it begins from this length on. */
    private const val MIN_PREFIX = 3

    /** How far apart, in words, the words of a query still stand together. */
    private const val NEAR = 6

    /** What the words standing together weigh against the words found apart. */
    private const val TOGETHER = 2f

    /**
     * What the words of a query found apart weigh: WORD_WEIGHT · APART = 0.15 is below the least likeness by meaning
     * that counts, so such items come after those alike by meaning.
     */
    private const val APART = 0.25f

    /** Of a query of more than two words, every third may be missing. */
    private const val EVERY_THIRD = 3
}

/** A piece of a found text, and where in it the words of the query stand (start, end). */
class Snippet(val text: String, val highlights: List<Pair<Int, Int>>)

/**
 * The best passage of a text as a [Snippet]: the passage — a sentence — cut to its part around the first word found
 * when it is long, the words found as ranges in it; null without a word found.
 */
private class SnippetFormatter : PassageFormatter() {
    override fun format(passages: Array<Passage>, content: String): Any? {
        val passage = passages.firstOrNull { it.numMatches > 0 } ?: return null
        var from = passage.startOffset
        var to = passage.endOffset
        val first = passage.matchStarts[0]
        if (to - from > MAX) {
            from = maxOf(from, first - MAX / BEFORE_FIRST)
            to = minOf(to, from + MAX)
        }
        // A word boundary, not the middle of a word
        while (from > passage.startOffset && !content[from - 1].isWhitespace()) from--
        val raw = content.substring(from, to)
        val base = from + (raw.length - raw.trimStart().length)
        val text = raw.trim()
        val prefix = if (from > passage.startOffset) "…" else ""
        val suffix = if (to < passage.endOffset) "…" else ""
        val highlights =
            (0 until passage.numMatches)
                .map { passage.matchStarts[it] - base to passage.matchEnds[it] - base }
                .filter { (start, end) -> start >= 0 && end <= text.length }
                // A word found both as a word and as the beginning of one is one word
                .distinct()
                .map { (start, end) -> start + prefix.length to end + prefix.length }
        return Snippet(prefix + text + suffix, highlights)
    }

    companion object {
        /** How long a piece of text is shown at most, around the first word found. */
        private const val MAX = 220

        /** Of a long piece, a third of it is shown before the first word found: [MAX] divided by this. */
        private const val BEFORE_FIRST = 3
    }
}
