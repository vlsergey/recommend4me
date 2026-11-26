package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import java.util.IdentityHashMap

/**
 * THE VALUE SIDE OF A PASS OVER A FACET ([TaskPortions.values]), alike for every portion of its works: the [values]
 * chosen from — every value the site, the drafts, the site's examples or the user give any work, and every value the
 * dictionary knows besides — with the vectors of their names ([names]), their [groupings], the names they go by as
 * texts ([hints], [hintTexts]); the columns of the context ([contextKeys]: of a value more than one work has alone);
 * the fingerprint of the whole facet ([basis]). Made of a walk over every work ([ValueCount]) before the portions are
 * read for the fitting.
 */
internal class ValueSide(
    val values: List<String>,
    val names: Matrix,
    val groupings: List<ValueGrouping>,
    val hints: List<NameHint>,
    val hintTexts: Matrix,
    val contextKeys: List<String>,
    val basis: Long,
) {
    val index = ValueIndex(values)

    /** The column of every key of the context. */
    private val columns: Map<String, Int> = contextKeys.withIndex().associate { (i, k) -> k to i }

    /** Of every work's keys of its context ([rows]), its columns: a key no column is of left out. */
    fun columnsOf(rows: List<List<String>>): List<IntArray> = rows.map { row ->
        row.mapNotNull(columns::get).distinct().toIntArray()
    }

    /** The value side as a task of no item ([TaskPortions.values]). */
    fun task(): SuggestionTask =
        SuggestionTask(
            itemIds = LongArray(0),
            inputs = emptyList(),
            values = names,
            valueKeys = values,
            assigned = emptyList(),
            confirmed = emptyList(),
            rejected = emptyList(),
            context = emptyList(),
            contextKeys = contextKeys,
            groupings = groupings,
            // The user's word alone is learnt: a value said nothing of weighs nought, a draft is an input
            lackWeight = 0.0,
            hints = hints,
            hintTexts = hintTexts,
            draftWeight = 0.0,
        )

    companion object {
        /**
         * The value side of the [facet] of what the walk over every work [counted], of the dictionary's word on its
         * values — those known with the text of their names, their groups, the names they go by ([vocabulary], of the
         * value side alone) — and of every answer of the source ([answers]): the names the user gave values among them.
         * The names encoded where missing; [hierarchy]: the facet's tags under others, part of its basis.
         */
        fun of(
            store: SourceStore,
            facet: FacetDef,
            encoder: TextEncoder,
            counted: ValueCount,
            vocabulary: Vocabulary?,
            answers: List<FacetCorrection>,
            hierarchy: TagHierarchy?,
        ): ValueSide {
            val known = vocabulary?.values.orEmpty()
            val values = (counted.values + known.keys).distinct().sorted()
            val index = ValueIndex(values)
            val names = ValueNames.matrix(store, facet.key, values, index, answers, known, encoder, asKept = false)
            val groupings = vocabulary?.groupings?.invoke(values).orEmpty()
            val hintNames = vocabulary?.hintNames.orEmpty()
            val (hints, hintTexts) = hints(store, facet, encoder, index, vocabulary)
            return ValueSide(
                values,
                names,
                groupings,
                hints,
                hintTexts,
                counted.contextKeys(),
                FacetBasis.of(counted.print, values, groupings, hintNames, hierarchy, counted.castSignature()),
            )
        }

        /**
         * The texts naming the values and the values each is judged among ([NameHint]): what is learnt from. The names
         * of values of the task: every one, but of a dictionary's value the task lacks.
         */
        private fun hints(
            store: SourceStore,
            facet: FacetDef,
            encoder: TextEncoder,
            index: ValueIndex,
            vocabulary: Vocabulary?,
        ): Pair<List<NameHint>, Matrix> {
            if (vocabulary == null) return emptyList<NameHint>() to Matrix(0, 0)
            val (hintValues, hintTexts) = ValueNames.texts(store, facet.key, vocabulary.hintNames, encoder)
            val named = hintValues.indices.filter { index[hintValues[it]] != null }
            // Every collection of values the names are judged among made indices once, one array
            // shared by all its names: of a universe's characters thousands of names, each of
            // thousands of rivals
            val made = IdentityHashMap<Collection<String>, IntArray>()
            val hints = named.map { k ->
                val key = hintValues[k]
                val v = index[key]!!
                val of = vocabulary.hintAmong(key)
                val among = made.getOrPut(of) { index.of(of).also { it.sort() } }
                NameHint(v, if (among.binarySearch(v) >= 0) among else (among + v).also { it.sort() })
            }
            return hints to if (named.size == hintValues.size) hintTexts else hintTexts.gather(named.toIntArray())
        }
    }
}

/**
 * WHAT A WALK OVER EVERY WORK OF A SOURCE TELLS OF THE VALUE SIDE OF A FACET ([ValueSide]), a portion after another in
 * the order of the works' ids ([add]), no work kept: the values the works have or were answered of ([values]); how many
 * works have each key of the context, in the order the works first have them; the texts of the lines with vectors; the
 * pairs of characters every work may have, of a facet of pairings — its values; how many works there are; the works'
 * fingerprints ([print]). Its size is of the values, never of the works.
 */
internal class ValueCount(facet: String) {
    val values = HashSet<String>()
    private val context = ContextCount()
    private val castTexts = HashSet<String>()

    /** The reader of the lines, by its id, of a facet that reads them. */
    private var castReader: String? = null

    /** The values every work may have of a facet of pairings ([Vocabulary.paired]): of every work, its pairs. */
    val pairings = HashSet<String>()

    val print = WorksPrint(facet)

    var works = 0
        private set

    /** The works of a portion [read], what they tell of the facet [counted], the dictionary's word on them. */
    fun add(read: WorksRead, counted: PortionCount, vocabulary: Vocabulary?, castReader: String?) {
        works += read.ids.size
        values += counted.values
        context.add(counted.context)
        castTexts += counted.castTexts
        this.castReader = castReader
        if (vocabulary?.paired == true)
            vocabulary.allowed?.let { of -> read.ids.forEach { pairings += of(it).orEmpty() } }
        print.add(read)
    }

    /** The columns of the context ([ContextCount.keys]). */
    fun contextKeys(): List<String> = context.keys()

    /**
     * How the site's line was read ([TaskCast.signature][io.github.vlsergey.recommend4me.cast.TaskCast.signature]): the
     * way, and how many texts of every work's line it read with vectors; null of a facet that reads none.
     */
    fun castSignature(): String? = castReader?.let { "$it:${castTexts.size}" }
}

/**
 * HOW MANY WORKS HAVE EACH KEY OF THE CONTEXT, of every work a portion after another ([add]), the keys in the order the
 * works first have them: of the keys, never of the works.
 */
internal class ContextCount {
    private val counts = LinkedHashMap<String, IntArray>()

    /** The keys of the context of every work of a portion, a row of each. */
    fun add(rows: List<List<String>>) = rows.forEach { row ->
        row.distinct().forEach { counts.getOrPut(it) { IntArray(1) }[0]++ }
    }

    /**
     * The columns of the context: a value of the context only one work has teaches nothing of the others — of a
     * catalogue of games, the developers of one game each, the operating systems and languages written by hand once.
     */
    fun keys(): List<String> = counts.entries.filter { it.value[0] > 1 }.map { it.key }
}
