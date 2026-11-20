package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.contenttype.StandardNumbers
import io.github.vlsergey.recommend4me.duplicate.AUTHOR_FAMILY
import io.github.vlsergey.recommend4me.duplicate.ComparedValues
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.worklist.ListIndex
import kotlin.concurrent.withLock

/** The number of the source's layer that counts a work's words; null when its site states none. */
internal fun SourceStore.wordsKey() = schema.numbers.firstOrNull { it.standard === StandardNumbers.WORDS }?.key

/** The number of the source's layer that counts a work's characters; null when its site states none. */
internal fun SourceStore.charsKey() = schema.numbers.firstOrNull { it.standard === StandardNumbers.CHARS }?.key

/** Whether a site of the type states a work's size, in words or in characters. */
internal fun TypeStore.hasSizes() = sources.any { it.wordsKey() != null || it.charsKey() != null }

/** The words and the characters known of a site item, and whether they are its own. */
private class Known(val words: Double, val chars: Double)

/**
 * THE EXAMPLES THE MODEL OF THE WORDS TO A CHARACTER LEARNS OF, every one a site item that tells both:
 * - [own]: its kept text, counted once ([countTexts]), else its site stating both;
 * - [across]: of an item of no such numbers that states one of them, the other of the most another item of its work
 *   states — a site may hold a part or another version of it, the caller decides whether to trust it.
 *
 * Every example's features read of the index of the list by the items' keys ([featuresOf]); the examples are few — some
 * thousands of the million and a half of the items (2026-11-20) —, and nothing of every item is read but the numbers of
 * a site that states both.
 */
internal class SizeExamples(val own: List<SizeExample>, val across: List<SizeExample>) {
    companion object {
        fun read(type: TypeStore, index: ListIndex, features: SizeFeatures): SizeExamples {
            val known = HashMap<ItemKey, Known>()
            val across = HashMap<ItemKey, Known>()
            type.sources.forEach { s ->
                countTexts(s)
                ownOf(s).forEach { (id, k) -> known[ItemKey(s.id, id)] = k }
            }
            WorkClusters.of(type.links.groups()).linkedWorks().forEach { members ->
                acrossOf(type, members, known).forEach { (key, k) -> across[key] = k }
            }
            fun examples(of: Map<ItemKey, Known>) =
                of.keys.groupBy({ it.source }, { it.id }).flatMap { (source, ids) ->
                    val s = type.source(source) ?: return@flatMap emptyList()
                    val own = featuresOf(s, index, ids, features)
                    ids.map { id ->
                        of.getValue(ItemKey(source, id)).let { SizeExample(own[id]!!, it.words, it.chars) }
                    }
                }
            return SizeExamples(examples(known), examples(across))
        }

        /** Of the items of the source [s], those that tell both by themselves: their kept texts, its site's numbers. */
        private fun ownOf(s: SourceStore): Map<String, Known> {
            val out = HashMap<String, Known>()
            val words = s.wordsKey()
            val chars = s.charsKey()
            // A site that states both, its two numbers of every item
            if (words != null && chars != null) {
                val stated = s.items.number(words)
                s.items.number(chars).forEach { (id, c) ->
                    val w = stated[id]
                    if (w != null && w > 0 && c > 0) out[id] = Known(w, c)
                }
            }
            s.parts.counts.counted().forEach { (id, count) ->
                if (count.first > 0 && count.second > 0) out[id] = Known(count.first, count.second)
            }
            return out
        }

        /**
         * Of a work of several items — its [members] —, of an item of none of [known] that states one of the two, the
         * two: the most words and the most characters its items state.
         */
        private fun acrossOf(type: TypeStore, members: List<ItemKey>, known: Map<ItemKey, Known>): Map<ItemKey, Known> {
            val stated = HashMap<ItemKey, Pair<Double, Double>>()
            members
                .filter { it !in known }
                .groupBy({ it.source }, { it.id })
                .forEach { (source, ids) ->
                    val s = type.source(source) ?: return@forEach
                    val words = s.wordsKey()
                    val chars = s.charsKey()
                    s.items.numbersOf(ids).forEach { (id, own) ->
                        val w = words?.let { own[it] } ?: 0.0
                        val c = chars?.let { own[it] } ?: 0.0
                        if (w > 0 || c > 0) stated[ItemKey(source, id)] = w to c
                    }
                }
            val words = stated.values.maxOfOrNull { it.first } ?: 0.0
            val chars = stated.values.maxOfOrNull { it.second } ?: 0.0
            if (words <= 0 || chars <= 0) return emptyMap()
            return stated.keys.associateWith { Known(words, chars) }
        }

        /**
         * The features of the items [ids] of the source [s] by their numbers in [features]: its site, and the values of
         * the index the search of duplicates compares them by ([ComparedValues]) of the families the size is told by —
         * the tags, the languages, the normalized authors.
         */
        private fun featuresOf(
            s: SourceStore,
            index: ListIndex,
            ids: List<String>,
            features: SizeFeatures,
        ): Map<String, IntArray> {
            val compared = ComparedValues(s, index).of(ids, emptyList())
            val families = s.tagFacets + StandardLanguages.KEY + AUTHOR_FAMILY
            val site = features.id(WordsPerChar.SOURCE + s.id)
            // A value's number looked up once a value, not once an item
            val byValue =
                IntArray(compared.names.size) { v ->
                    compared.names[v]?.takeIf { it.substringBefore(':') in families }?.let(features::id) ?: -1
                }
            return compared.ids.indices.associate { i ->
                val own = compared.numbered[i].map { byValue[it] }.filter { it >= 0 }.distinct()
                compared.ids[i] to (listOf(site) + own).toIntArray()
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
        countTexts(s)
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

/** The parts of the source read and counted at once. */
private const val COUNTED_AT_ONCE = 100

/** Every part whose text is not counted yet counted: one count of a part, kept, however often the model is fitted. */
private fun countTexts(s: SourceStore) {
    while (true) {
        val parts = s.parts.counts.uncounted(COUNTED_AT_ONCE)
        if (parts.isEmpty()) return
        s.writing.withLock {
            parts.forEach {
                s.parts.counts.setCounted(it.itemId, it.partId, it.content.length, WordCount.of(it.content))
            }
        }
    }
}
