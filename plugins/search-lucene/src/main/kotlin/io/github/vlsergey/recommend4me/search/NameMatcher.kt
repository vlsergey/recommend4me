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
                if (stem in common) return null
                val of = owners[stem] ?: return null
                var one: String? = null
                for (v in of) {
                    if (v in without) continue
                    if (one != null) return null
                    one = v
                }
                return one?.let { Pattern(listOf(stem), it) }
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
        val sorted = words.sortedWith(compareBy<NamedWord> { it.position }.thenBy { it.stem })
        val positions = IntArray(sorted.size) { sorted[it].position }
        /** The word of the [stem] at the place [at], of the places sorted. */
        fun wordAt(at: Int, stem: String): NamedWord? {
            var k = positions.binarySearch(at)
            if (k < 0) return null
            while (k > 0 && positions[k - 1] == at) k--
            while (k < positions.size && positions[k] == at) {
                if (sorted[k].stem == stem) return sorted[k]
                k++
            }
            return null
        }
        val out = ArrayList<Found>()
        var next = Int.MIN_VALUE
        var k = 0
        while (k < sorted.size) {
            val at = sorted[k].position
            var end = k
            while (end < sorted.size && sorted[end].position == at) end++
            if (at >= next) {
                var best: Pattern? = null
                var first: NamedWord? = null
                for (w in k until end) {
                    val word = sorted[w]
                    patternsOf(word.stem)?.forEach { p ->
                        if (p.stems.size == 1 && !word.capital) return@forEach
                        if (best != null && p.stems.size <= best!!.stems.size) return@forEach
                        if ((1 until p.stems.size).all { wordAt(at + it, p.stems[it]) != null }) {
                            best = p
                            first = word
                        }
                    }
                }
                best?.let { p ->
                    out += Found(p.value, first!!, wordAt(at + p.stems.size - 1, p.stems.last())!!)
                    next = at + p.stems.size
                }
            }
            k = end
        }
        return out
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
