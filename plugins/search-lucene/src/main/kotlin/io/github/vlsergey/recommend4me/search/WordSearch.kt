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

/**
 * THE SEARCH BY WORDS: the words of the line found — as words, in any of their forms, or as the beginnings of words —
 * in the title, the facets and the texts, a field weighing what a match there says; with a bonus when a naming field
 * (the title, the author) holds the whole line. A name the encoder never saw — a developer, a character — is found this
 * way. The piece of a work's text where the words are found is [WorkSnippet]'s.
 */
internal object WordSearch {

    /**
     * Every item matching the [terms] by words, with the parts of its score, by their names ([queries]); its score
     * their sum. An item matches when it has all the words, anywhere in it — of a long query all but a few
     * ([required]): one word of two is a coincidence ("Stolen Memories" for "stolen clothes"), and such an item is left
     * to the search by meaning. Its score: for every term found — in any of its forms, or a name typed in part — the
     * weight of the best field it is found in, averaged over the terms, and of several terms only [APART] of that; plus
     * [TOGETHER] times the weight of the best field where they all stand together — within [NEAR] words of each other,
     * in any order; plus [NAME_BONUS] when the whole [line] stands as a phrase in a naming field — or, a single word
     * typed in part, begins a word of it.
     */
    fun byWords(
        searcher: IndexSearcher,
        fields: LuceneIndex.Fields,
        line: String,
        terms: List<String>,
        language: String,
    ): Map<ItemKey, Map<String, Double>> {
        if (fields.text.isEmpty()) return emptyMap()
        val out = HashMap<ItemKey, HashMap<String, Double>>()
        val stored = searcher.storedFields()
        queries(fields, line, terms, language).forEach { (part, q) ->
            searcher.search(q, searcher.indexReader.maxDoc().coerceAtLeast(1)).scoreDocs.forEach { hit ->
                val key = ItemKey.parse(stored.document(hit.doc).get(LuceneIndex.ID))
                out.getOrPut(key) { HashMap() }[part] = hit.score.toDouble()
            }
        }
        return out
    }

    /**
     * The queries of [byWords], each a part of what an item is found by — its score of one, a feature of it
     * ([io.github.vlsergey.recommend4me.search.SearchResult.features]): the terms apart ([APART_PART]), the words
     * together ([TOGETHER_PART]), the whole line in a naming field ([NAME_PART]). Their sum is the item's score by
     * words.
     */
    private fun queries(
        fields: LuceneIndex.Fields,
        line: String,
        terms: List<String>,
        language: String,
    ): Map<String, Query> {
        val out = LinkedHashMap<String, Query>()
        val termQueries = terms.mapNotNull { termQuery(fields, it, language) }
        if (termQueries.isNotEmpty()) {
            val byWords = BooleanQuery.Builder().setMinimumNumberShouldMatch(required(termQueries.size))
            termQueries.forEach { byWords.add(BoostQuery(it, 1f / termQueries.size), BooleanClause.Occur.SHOULD) }
            out[APART_PART] = BoostQuery(byWords.build(), if (termQueries.size > 1) APART else 1f)
        }
        val all = terms.flatMap { LuceneIndex.tokens(it, language) }.distinct()
        if (all.size > 1) {
            val together =
                fields.text.map { field ->
                    val near =
                        PhraseQuery.Builder().setSlop(NEAR).apply { all.forEach { add(Term(field.name, it)) } }.build()
                    BoostQuery(ConstantScoreQuery(near), field.weight)
                }
            out[TOGETHER_PART] = BoostQuery(DisjunctionMaxQuery(together, 0f), TOGETHER)
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
            out[NAME_PART] = BoostQuery(DisjunctionMaxQuery(inName, 0f), NAME_BONUS)
        }
        return out
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

    fun wordQuery(field: String, word: Word): Query {
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
     * The whole line in a naming field: what is looked for, ahead of any likeness by meaning — WORD_WEIGHT · (1 +
     * NAME_BONUS) = 1.8 beats MAX_CLOSENESS = 1.5 ([LuceneSearch]). At 1.0 a developer's name, a word the encoder never
     * saw, lost to the items its vector happened to lie near.
     */
    private const val NAME_BONUS = 2.0f

    /** A word of the query left as typed finds the words it begins from this length on. */
    private const val MIN_PREFIX = 3

    /** The parts of an item's score by words, its features by them: the terms apart, together, the line in a name. */
    const val APART_PART = "words"
    const val TOGETHER_PART = "together"
    const val NAME_PART = "name"

    /** How far apart, in words, the words of a query still stand together. */
    const val NEAR = 6

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
