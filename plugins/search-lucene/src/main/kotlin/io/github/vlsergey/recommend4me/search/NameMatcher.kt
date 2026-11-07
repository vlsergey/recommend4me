package io.github.vlsergey.recommend4me.search

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.en.EnglishAnalyzer

/**
 * A word of a text as the index has it: its stem, its place among the words, whether written with a capital; [start]
 * and [end] its characters, when read from the text.
 */
internal class NamedWord(
    val stem: String,
    val position: Int,
    val capital: Boolean,
    val start: Int = -1,
    val end: Int = -1,
)

/** A name found: the value it is of, the place of its first word and of its last. */
internal class Found(val value: String, val first: NamedWord, val last: NamedWord)

/**
 * WHERE A WORK'S WORDS NAME EVERY VALUE of the names (value to the names it goes by), the words read as the languages
 * its texts were stemmed as — the rules of the counting of names, the same whether the words are read of the index or
 * of a text:
 *
 * - a name of several words counts whole; one word of it counts by itself only when no other value has that word in a
 *   name — "Гарри" is Harry's alone, "Поттер" and "Уизли" are of a family and tell nobody — and it is no function word
 *   of the language nor of English ("Со" of "Со Геррера");
 * - a name of one word — a whole one or a word of a longer one — counts only where written with a capital: "Пи" of
 *   "Си-Три-Пи-О" names, "пи" of a formula not; "Nova" does, "новый" of the same stem not;
 * - at every place the longest name found counts, once; of two words at one place, either.
 *
 * A name is read as every language of the languages: a work whose description is in Russian and whose long chapter is
 * in English is read as both, each word of it stemmed as one of them.
 *
 * Of the names of a [Base] but the values [without]: the same as a matcher of those names alone, made of the base's — a
 * word of a value left out naming another alone — not of every name again.
 */
internal class NameMatcher private constructor(private val base: Base, private val without: Set<String>) {

    constructor(
        names: Map<String, List<String>>,
        languages: Collection<String>,
        read: NameStems = NameStems(),
    ) : this(Base(names, languages, read), emptySet())

    /** A name as its stems, and the value it is of. */
    data class Pattern(val stems: List<String>, val value: String)

    /**
     * WHAT EVERY MATCHER OF THE SAME NAMES AND LANGUAGES SHARES: of every language its whole names, the values every
     * stem is of and its function words; every name by its first stem. Made once of a reading for every asking of the
     * same names — the characters of one universe — whatever values each leaves out ([without]).
     */
    class Base(names: Map<String, List<String>>, languages: Collection<String>, read: NameStems) {

        /**
         * Of one language: its whole names by their first stem, the values every stem is of, its function words, every
         * value's stems.
         */
        internal class Language(
            val whole: Map<String, List<Pattern>>,
            val owners: Map<String, Set<String>>,
            val common: Set<String>,
            val stemsOf: Map<String, Set<String>>,
        ) {
            /**
             * The single word of a name [stem] counts as, of the values but [without]: of one value alone and no
             * function word.
             */
            fun single(stem: String, without: Set<String>): Pattern? {
                // Two values kept are enough to tell the word is of more than one
                val kept =
                    if (stem in common) emptyList()
                    else owners[stem].orEmpty().asSequence().filter { it !in without }.take(2).toList()
                return kept.singleOrNull()?.let { Pattern(listOf(stem), it) }
            }
        }

        internal val perLanguage: List<Language> =
            languages
                .ifEmpty { listOf(Stemmers.DEFAULT) }
                .distinct()
                .map { language ->
                    val whole =
                        names
                            .flatMap { (value, own) -> own.map { Pattern(read.of(it, language), value) } }
                            .filter { it.stems.isNotEmpty() }
                            .distinct()
                    val owners = LinkedHashMap<String, MutableSet<String>>()
                    whole.forEach { p -> p.stems.forEach { owners.getOrPut(it) { LinkedHashSet() } += p.value } }
                    val stemsOf = HashMap<String, MutableSet<String>>()
                    whole.forEach { p -> stemsOf.getOrPut(p.value) { HashSet() } += p.stems }
                    Language(whole.groupBy { it.stems.first() }, owners, read.common(language), stemsOf)
                }

        /** Every name by its first stem, of every language, the whole ones of a language before its single words. */
        internal val patterns: Map<String, List<Pattern>>

        /** Every stem a name has: the words looked up in the index. */
        val stems: Set<String>

        init {
            val all = perLanguage.flatMap { l ->
                val single = l.owners.keys.mapNotNull { l.single(it, emptySet()) }
                l.whole.values.flatten() + single
            }
            patterns =
                LinkedHashMap<String, MutableList<Pattern>>().also { out ->
                    // A pattern once, in the order made: the whole names of a language, its single words, the next
                    // language
                    LinkedHashSet(all).forEach { out.getOrPut(it.stems.first()) { ArrayList() } += it }
                }
            stems = all.flatMapTo(LinkedHashSet()) { it.stems }
        }

        /** The matcher of these names but those of the values [without]. */
        fun without(without: Set<String>): NameMatcher = NameMatcher(this, without)
    }

    /**
     * Of the values left out, every stem of their names: its names made again of the values kept — a word of one left
     * out may now be of one value alone — the others as the base has them.
     */
    private val changed: Map<String, List<Pattern>> =
        if (without.isEmpty()) emptyMap()
        else {
            val affected = LinkedHashSet<String>()
            base.perLanguage.forEach { l -> without.forEach { v -> l.stemsOf[v]?.let(affected::addAll) } }
            affected.associateWith { stem ->
                val own = LinkedHashSet<Pattern>()
                base.perLanguage.forEach { l ->
                    l.whole[stem]?.forEach { if (it.value !in without) own += it }
                    l.single(stem, without)?.let(own::add)
                }
                own.toList()
            }
        }

    /** The names of the first [stem]. */
    private fun patternsOf(stem: String): List<Pattern>? = changed[stem] ?: base.patterns[stem]

    /**
     * Every stem a name has: the words looked up in the index, in their order — a word of the index read of them in it.
     * Of a matcher without some values, the base's: a word of a name left out alone starts no name and is in none.
     */
    val stems: Set<String>
        get() = base.stems

    /**
     * The names found among the [words] of one text, in the order of their places: the longest name at a place, places
     * not overlapping.
     */
    fun found(words: List<NamedWord>): List<Found> {
        val text = PlacedWords(words)
        val out = ArrayList<Found>()
        var next = Int.MIN_VALUE
        var k = 0
        while (k < text.size) {
            val at = text.words[k].position
            val end = text.endOfPlace(k)
            val longest = if (at >= next) longestAt(text, k, end) else null
            if (longest != null) {
                val (p, first) = longest
                out += Found(p.value, first, text.wordAt(at + p.stems.size - 1, p.stems.last())!!)
                next = at + p.stems.size
            }
            k = end
        }
        return out
    }

    /**
     * The longest name beginning at the place of the words [from] until [end] of [text]: its pattern and its first
     * word; null of none.
     */
    private fun longestAt(text: PlacedWords, from: Int, end: Int): Pair<Pattern, NamedWord>? {
        val at = text.words[from].position
        var best: Pattern? = null
        var first: NamedWord? = null
        for (w in from until end) {
            val word = text.words[w]
            patternsOf(word.stem)?.forEach { p ->
                if (longer(p, word, best) && (1 until p.stems.size).all { text.wordAt(at + it, p.stems[it]) != null }) {
                    best = p
                    first = word
                }
            }
        }
        return best?.let { it to first!! }
    }

    /**
     * Whether the name [p] beginning with [word] may count over the [best] found at its place: a name of one word only
     * where written with a capital, and only a longer one than the best.
     */
    private fun longer(p: Pattern, word: NamedWord, best: Pattern?): Boolean =
        (p.stems.size != 1 || word.capital) && (best == null || p.stems.size > best.stems.size)
}

/** The words of one text in the order of their places, then of their stems. */
private class PlacedWords(words: List<NamedWord>) {
    val words = words.sortedWith(compareBy<NamedWord> { it.position }.thenBy { it.stem })
    private val positions = IntArray(this.words.size) { this.words[it].position }

    val size: Int
        get() = words.size

    /** The end of the place of the word [k]: the first word after it at another place. */
    fun endOfPlace(k: Int): Int {
        var end = k
        while (end < words.size && words[end].position == words[k].position) end++
        return end
    }

    /** The word of the [stem] at the place [at]; null of none. */
    fun wordAt(at: Int, stem: String): NamedWord? {
        var k = positions.binarySearch(at)
        var found: NamedWord? = null
        if (k >= 0) {
            while (k > 0 && positions[k - 1] == at) k--
            while (found == null && k < positions.size && positions[k] == at) {
                if (words[k].stem == stem) found = words[k]
                k++
            }
        }
        return found
    }
}

/**
 * The stems of names as the analyzer of each language reads them, each name read once for every matcher made with them:
 * one reading of the index makes hundreds of matchers of the same thousands of names. Held for that reading alone; and
 * every language's function words, stemmed once.
 */
internal class NameStems {
    private val analyzers = HashMap<String, Analyzer>()
    private val read = HashMap<Pair<String, String>, List<String>>()
    private val commons = HashMap<String, Set<String>>()

    fun of(text: String, language: String): List<String> =
        read.getOrPut(language to text) {
            LuceneIndex.tokens(text, analyzers.getOrPut(language) { Stemmers.analyzer(language) })
        }

    /** The stems of the function words of the [language] and of English: no word of a name counts alone that is one. */
    fun common(language: String): Set<String> =
        commons.getOrPut(language) {
            CharArraySet(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET, true)
                .apply { addAll(Stemmers.stopWords(language)) }
                .flatMap { of(String(it as CharArray), language) }
                .toSet()
        }
}
