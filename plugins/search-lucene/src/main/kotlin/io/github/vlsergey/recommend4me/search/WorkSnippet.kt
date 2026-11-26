package io.github.vlsergey.recommend4me.search

import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.uhighlight.Passage
import org.apache.lucene.search.uhighlight.PassageFormatter
import org.apache.lucene.search.uhighlight.UnifiedHighlighter

/**
 * The fields of a document whose texts did not come with it ([SearchField.read]): whether the index [holds] what a
 * query asks in one of its fields, of the item, and the [texts] of some of them read now, by key.
 */
class Unread(val holds: (name: String, query: Query) -> Boolean, val texts: (List<SearchField>) -> Map<String, String>)

/**
 * THE PIECE OF A WORK'S TEXT WHERE THE WORDS OF A LINE ARE FOUND, the words marked ([found]): where they all stand
 * together, from the weightiest field that has them so — the place that made the item a match — or else from the
 * weightiest field that holds one. The [terms] are of the [language]. [stemmed]: the languages the document's texts
 * were stemmed as in the index ([IndexKept.languages]) — of one alone, every text of it is read as that one, none told
 * again; else a text is read as its field says it is written ([TextLanguages.of]).
 *
 * The fields of the document named alone ([unread]: the chapters of a card) are looked into last, as the least weighty,
 * and only where the index holds the words of the item in them: their texts read [UNREAD_AT_ONCE] at a time, till one
 * has them — a work's chapters are megabytes. The words apart are looked for in them only when [apartInUnread]: a work
 * found by meaning is shown with its passage, not with its words apart.
 */
internal class WorkSnippet(
    terms: List<String>,
    language: String,
    private val doc: SearchDocument,
    private val stemmed: List<String>? = null,
    private val unread: Unread? = null,
    private val apartInUnread: Boolean = true,
) {
    private val parts = terms.flatMap { WordSearch.words(it, language) }.distinct()
    private val all = terms.flatMap { LuceneIndex.tokens(it, language) }.distinct()

    /** The fields whose texts came with the document, the weightiest first, each as one text. */
    private val contents =
        doc.fields
            .sortedByDescending { it.weight }
            .mapNotNull { field ->
                field.values
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { field to it.joinToString(if (field.list) ", " else "\n\n") }
            }

    /** The language of every field read, told once. */
    private val languages = HashMap<String, String>()

    /** The field, the piece of it and whether the words all stand together there; null when none is found. */
    fun found(): Triple<SearchField, Snippet, Boolean>? {
        if (parts.isEmpty()) return null
        val together =
            if (all.size <= 1) null else (inRead(::near) ?: inUnread(::near))?.let { (f, s) -> Triple(f, s, true) }
        val apart = inRead(::apart) ?: if (apartInUnread) inUnread(::apart) else null
        return together ?: apart?.let { (f, s) -> Triple(f, s, all.size <= 1) }
    }

    /** The words all within [WordSearch.NEAR] of each other in the field of the index [name]. */
    private fun near(name: String): Query =
        PhraseQuery.Builder().setSlop(WordSearch.NEAR).apply { all.forEach { add(Term(name, it)) } }.build()

    /** Any of the words in the field of the index [name]. */
    private fun apart(name: String): Query =
        BooleanQuery.Builder()
            .apply { parts.forEach { part -> add(WordSearch.wordQuery(name, part), BooleanClause.Occur.SHOULD) } }
            .build()

    /** The first field read whose text has what the [query] of its field of the index asks. */
    private fun inRead(query: (String) -> Query): Pair<SearchField, Snippet>? =
        contents.firstNotNullOfOrNull { (field, content) ->
            val name = LuceneIndex.fieldName(field)
            marked(field, content, name, query(name))?.let { field to it }
        }

    /** The first field named alone whose text has what the [query] of its field asks: the index asked first. */
    private fun inUnread(query: (String) -> Query): Pair<SearchField, Snippet>? {
        val asked = unread ?: return null
        return doc.fields
            .filter { !it.read }
            .groupBy { LuceneIndex.fieldName(it) }
            .entries
            .firstNotNullOfOrNull { (name, fields) ->
                val q = query(name)
                if (!asked.holds(name, q)) null
                else
                    fields.chunked(UNREAD_AT_ONCE).firstNotNullOfOrNull { chunk ->
                        val texts = asked.texts(chunk)
                        chunk.firstNotNullOfOrNull { f ->
                            texts[f.key]?.let { t -> marked(f, t, name, q)?.let { f to it } }
                        }
                    }
            }
    }

    /** The [content] of the [field] with what the [query] asks marked, read as it was put in the index. */
    private fun marked(field: SearchField, content: String, name: String, query: Query): Snippet? =
        highlighter(stemmed?.singleOrNull() ?: languages.getOrPut(field.key) { TextLanguages.of(content, field, doc) })
            .highlightWithoutSearcher(name, query, content, 1) as Snippet?

    private companion object {
        /** The texts of the fields named alone read at once as the words of a line are looked for in them. */
        const val UNREAD_AT_ONCE = 8

        const val MAX_TEXT = 1_000_000

        /**
         * Marks the words of a query in a text given to it, without the index — the texts are not stored in it — the
         * text read as its [language], as it was put in the index.
         */
        fun highlighter(language: String) =
            UnifiedHighlighter.builderWithoutSearcher(Stemmers.analyzer(language))
                .withFormatter(SnippetFormatter())
                .withMaxNoHighlightPassages(0)
                // Not only the first 10 000 characters, the default: a changelog is longer
                .withMaxLength(MAX_TEXT)
                .build()
    }
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
