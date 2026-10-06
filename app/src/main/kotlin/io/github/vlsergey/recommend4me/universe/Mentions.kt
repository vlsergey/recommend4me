package io.github.vlsergey.recommend4me.universe

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.ru.RussianLightStemFilter
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute

/**
 * HOW OFTEN A WORK'S TEXTS NAME EACH CHARACTER: its description, its notes, the chapters read, the
 * site's line of its characters — by every name the character goes by, however it is declined
 * ("Гермиона", "Гермионы", "Гермионе" are one stem).
 *
 * A name of several words counts whole; one word of it counts by itself only when no other
 * character of the [characters] has that word in a name — "Гарри" is Harry's alone, "Поттер" and
 * "Уизли" are of a family and tell nobody. At every place the longest name found counts, once.
 */
class Mentions(characters: Map<String, List<String>>) {

    /** Every name as its stems, with the character it is of. */
    private val patterns: Map<String, List<Pair<List<String>, String>>>

    init {
        val whole = characters.flatMap { (value, names) -> names.map { stems(it) to value } }.filter { it.first.isNotEmpty() }.distinct()
        // A word of a name is a name by itself when it is of one character only
        val owners = HashMap<String, MutableSet<String>>()
        whole.forEach { (words, value) -> words.forEach { owners.getOrPut(it) { HashSet() } += value } }
        val single = owners.filter { it.value.size == 1 }.map { listOf(it.key) to it.value.first() }
        patterns = (whole + single).distinct().groupBy { it.first.first() }
    }

    /** How many times the [texts] name each character; only the named ones. */
    fun count(texts: Sequence<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        texts.forEach { text -> found(stems(text)).forEach { out.merge(it, 1, Int::plus) } }
        return out
    }

    /** In how many paragraphs of the [texts] each two characters are named together, the pair in the order of their values. */
    fun together(texts: Sequence<String>): Map<Pair<String, String>, Int> {
        val out = HashMap<Pair<String, String>, Int>()
        texts.flatMap { it.split(PARAGRAPH) }.forEach { paragraph ->
            val named = found(stems(paragraph)).toSortedSet().toList()
            named.forEachIndexed { k, a -> named.drop(k + 1).forEach { b -> out.merge(a to b, 1, Int::plus) } }
        }
        return out
    }

    /** The characters named at every place of the [words], the longest name at a place, places not overlapping. */
    private fun found(words: List<String>): List<String> {
        val out = ArrayList<String>()
        var at = 0
        while (at < words.size) {
            val match = patterns[words[at]]
                ?.filter { (name, _) -> at + name.size <= words.size && name.indices.all { words[at + it] == name[it] } }
                ?.maxByOrNull { it.first.size }
            if (match == null) at++ else {
                out += match.second
                at += match.first.size
            }
        }
        return out
    }

    companion object {
        private val PARAGRAPH = Regex("\\n\\s*\\n|\\n")

        /** The words of a text, lower-cased, stemmed as Russian: the chain the search reads texts by. */
        private val analyzer = object : Analyzer() {
            override fun createComponents(fieldName: String): TokenStreamComponents {
                val tokenizer = StandardTokenizer()
                return TokenStreamComponents(tokenizer, RussianLightStemFilter(LowerCaseFilter(tokenizer)))
            }
        }

        /** The stems of the words of a text, in order. */
        fun stems(text: String): List<String> {
            val out = ArrayList<String>()
            analyzer.tokenStream("", text.replace('ё', 'е').replace('Ё', 'Е')).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class.java)
                stream.reset()
                while (stream.incrementToken()) out += term.toString()
                stream.end()
            }
            return out
        }
    }
}
