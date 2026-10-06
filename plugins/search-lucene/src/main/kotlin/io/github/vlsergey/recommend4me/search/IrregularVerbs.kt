package io.github.vlsergey.recommend4me.search

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.en.PorterStemFilter
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.KeywordAttribute

/**
 * The past and the past participle of English irregular verbs read as their base — "stolen" as
 * "steal" — after the Porter stemmer, which does regular endings only ("steals", "stealing").
 * Both sides are taken as the stemmer leaves them, so a form meets its base wherever the stemmer
 * would have brought it. The verbs are those of `search/irregular-verbs.txt`.
 */
object IrregularVerbs {

    /** A stemmed irregular form to the stemmed base. */
    private val bases: Map<String, String> by lazy {
        val stemmer = object : Analyzer() {
            override fun createComponents(fieldName: String): TokenStreamComponents {
                val source = StandardTokenizer()
                return TokenStreamComponents(source, PorterStemFilter(LowerCaseFilter(source)))
            }
        }
        fun stem(word: String): String = stemmer.tokenStream("", word).use { stream ->
            val term = stream.addAttribute(CharTermAttribute::class.java)
            stream.reset()
            val out = if (stream.incrementToken()) term.toString() else word
            stream.end()
            out
        }
        val text = IrregularVerbs::class.java.getResourceAsStream("/search/irregular-verbs.txt")!!.bufferedReader().readText()
        buildMap {
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
                val words = line.split(Regex("\\s+"))
                val base = stem(words.first())
                words.drop(1).map(::stem).filter { it != base }.forEach { put(it, base) }
            }
        }
    }

    /** The filter to put after the Porter stemmer. */
    fun filter(input: TokenStream): TokenStream = Filter(input, bases)

    private class Filter(input: TokenStream, private val bases: Map<String, String>) : TokenFilter(input) {
        private val term = addAttribute(CharTermAttribute::class.java)
        private val keyword = addAttribute(KeywordAttribute::class.java)

        override fun incrementToken(): Boolean {
            if (!input.incrementToken()) return false
            if (!keyword.isKeyword) bases[term.toString()]?.let { term.setEmpty().append(it) }
            return true
        }
    }
}
