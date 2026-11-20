package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.contenttype.StandardNumber
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.duplicate.AUTHOR_FAMILY
import io.github.vlsergey.recommend4me.duplicate.ComparedValues
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.part.PartCounts
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.worklist.ListIndex

/** The number of the source's layer that counts a work's words; null when its site states none. */
internal fun SourceStore.wordsKey() = schema.numbers.firstOrNull { it.standard === StandardNumbers.WORDS }?.key

/** The number of the source's layer that counts a work's characters; null when its site states none. */
internal fun SourceStore.charsKey() = schema.numbers.firstOrNull { it.standard === StandardNumbers.CHARS }?.key

/** Whether a site of the type states a work's size, in words or in characters. */
internal fun TypeStore.hasSizes() = sources.any { it.wordsKey() != null || it.charsKey() != null }

/** What an item of a work of several states of its size alone, one of the two at least, and its features. */
private class Stated(val words: Double, val chars: Double, val features: IntArray)

/** The ids the index keeps the numbers of a source's sizes by: its site's words and characters, its texts'. */
private class SourceNumbers(s: SourceStore) {
    private fun numberOf(s: SourceStore, standard: StandardNumber) =
        s.schema.numbers.firstOrNull { it.standard === standard }?.let { FeatureNames.numberId(s.source, it) }

    private val wordsId = numberOf(s, StandardNumbers.WORDS)
    private val charsId = numberOf(s, StandardNumbers.CHARS)
    val ids = listOfNotNull(wordsId, charsId, PartCounts.WORDS_ID, PartCounts.CHARS_ID)

    /** The numbers of the items [compared], by their places; nought of one not known. */
    fun of(compared: ComparedValues.Compared): ItemSizes {
        val none = DoubleArray(compared.ids.size)
        fun number(id: String?) = id?.let { compared.numbers[it] } ?: none
        return ItemSizes(number(PartCounts.WORDS_ID), number(PartCounts.CHARS_ID), number(wordsId), number(charsId))
    }
}

/** The words and the characters of the items by their places: of their kept texts, and as their site states them. */
private class ItemSizes(
    private val textWords: DoubleArray,
    private val textChars: DoubleArray,
    val words: DoubleArray,
    val chars: DoubleArray,
) {
    /** The two the item [i] tells by itself: of its text, else of its site stating both; none of neither. */
    fun own(i: Int): Pair<Double, Double>? =
        when {
            textWords[i] > 0 && textChars[i] > 0 -> textWords[i] to textChars[i]
            words[i] > 0 && chars[i] > 0 -> words[i] to chars[i]
            else -> null
        }

    /** Whether its site states one of the two. */
    fun states(i: Int) = words[i] > 0 || chars[i] > 0
}

/**
 * THE EXAMPLES THE MODEL OF THE WORDS TO A CHARACTER LEARNS OF, every one a site item that tells both:
 * - [own]: its kept text, as counted as it was written ([PartCounts]), else its site stating both;
 * - [across]: of an item of no such numbers that states one of them, the other of the most another item of its work
 *   states — a site may hold a part or another version of it, the caller decides whether to trust it.
 *
 * Read of the index of the list alone (owner, 2026-11-20: "read the works' data of our Lucene index; a work of not
 * enough data, pass it over"), a source in one pass: every item's numbers — its site's and its texts' — and its
 * features as the search of duplicates compares them ([ComparedValues]); an item that tells neither is no example.
 */
internal class SizeExamples(val own: List<SizeExample>, val across: List<SizeExample>) {
    companion object {
        fun read(type: TypeStore, index: ListIndex, features: SizeFeatures): SizeExamples {
            val own = ArrayList<SizeExample>()
            val stated = HashMap<ItemKey, Stated>()
            val clusters = WorkClusters.of(type.links.groups())
            type.sources.forEach { s -> readSource(s, index, features, clusters, own, stated) }
            val across = ArrayList<SizeExample>()
            clusters.linkedWorks().forEach { members ->
                val of = members.mapNotNull { stated[it] }
                val words = of.maxOfOrNull { it.words } ?: 0.0
                val chars = of.maxOfOrNull { it.chars } ?: 0.0
                if (words > 0 && chars > 0) of.forEach { across += SizeExample(it.features, words, chars) }
            }
            return SizeExamples(own, across)
        }

        /**
         * Of the source [s], every item of a kept text or of both numbers stated an example of [own], every item of a
         * work of several stating one of them put in [stated]; the rest passed over.
         */
        private fun readSource(
            s: SourceStore,
            index: ListIndex,
            features: SizeFeatures,
            clusters: WorkClusters,
            own: MutableList<SizeExample>,
            stated: MutableMap<ItemKey, Stated>,
        ) {
            val numbers = SourceNumbers(s)
            val compared = ComparedValues(s, index).every(numbers.ids)
            val featuresOf = featuresOf(s, compared, features)
            val sizes = numbers.of(compared)
            for (i in compared.ids.indices) {
                val known = sizes.own(i)
                if (known != null) own += SizeExample(featuresOf(i), known.first, known.second)
                else if (sizes.states(i)) {
                    val key = ItemKey(s.id, compared.ids[i])
                    if (clusters.linked(key)) stated[key] = Stated(sizes.words[i], sizes.chars[i], featuresOf(i))
                }
            }
        }

        /**
         * Of the items [compared] of the source [s], by their places, their features by their numbers in [features]:
         * its site, and the values of the families the size is told by — the tags, the languages, the normalized
         * authors.
         */
        private fun featuresOf(
            s: SourceStore,
            compared: ComparedValues.Compared,
            features: SizeFeatures,
        ): (Int) -> IntArray {
            val families = s.tagFacets + StandardLanguages.KEY + AUTHOR_FAMILY
            val site = features.id(WordsPerChar.SOURCE + s.id)
            // A value's number looked up once a value, not once an item
            val byValue =
                IntArray(compared.names.size) { v ->
                    compared.names[v]?.takeIf { it.substringBefore(':') in families }?.let(features::id) ?: -1
                }
            return { i ->
                (listOf(site) + compared.numbered[i].map { byValue[it] }.filter { it >= 0 }.distinct()).toIntArray()
            }
        }
    }
}

/**
 * Whether any of the items [keys] gives the model something to learn of: one with a kept text, one its site states both
 * of, or one stating one of the two, of a work of several items.
 */
internal fun givesExamples(type: TypeStore, keys: Collection<ItemKey>): Boolean {
    val clusters = WorkClusters.of(type.links.groups())
    return keys.groupBy({ it.source }, { it.id }).any { (source, ids) ->
        val s = type.source(source) ?: return@any false
        val words = s.wordsKey()
        val chars = s.charsKey()
        s.parts.counts.counted(ids).isNotEmpty() ||
            s.items.numbersOf(ids).any { (id, own) ->
                val w = words?.let { own[it] } ?: 0.0
                val c = chars?.let { own[it] } ?: 0.0
                (w > 0 && c > 0) || ((w > 0 || c > 0) && clusters.linked(ItemKey(source, id)))
            }
    }
}
