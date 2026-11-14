package io.github.vlsergey.recommend4me.manner

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.sqrt

/**
 * THE WORDS A MANNER IS MEASURED BY, of a content type: the [words] most frequent in the texts of its works — every
 * text weighing alike, a long one no more than a short one ([WordTally]) — with the mean and the spread of every word's
 * share over the texts, fitted on [texts] of them. The manner of a work ([of]) is the share of every word in its text,
 * standardised by them: a vector of a number a word — "словно" twice as often as the average text, "вдруг" seldom —,
 * whose distance to another is Burrows' Delta. Nothing of it is told as a feature of its own: the model of the grades
 * and the nearness of two works read the vector whole.
 */
class MannerWords(
    val id: Long,
    val words: List<String>,
    private val means: FloatArray,
    private val spreads: FloatArray,
    val texts: Int,
) {
    private val index: Map<String, Int> = words.withIndex().associate { (i, w) -> w to i }

    /** The share of every word in the [texts] of one work, its parts, not standardised; null of a text of no word. */
    fun shares(texts: List<String>): FloatArray? {
        val counts = IntArray(words.size)
        var total = 0
        texts.forEach { text ->
            TextWords.forEach(text) { word, _ ->
                total++
                index[word]?.let { counts[it]++ }
            }
        }
        if (total == 0) return null
        return FloatArray(words.size) { counts[it].toFloat() / total }
    }

    /** The manner of a work of the [texts], its parts: the shares of the words, standardised; null of no word. */
    fun of(texts: List<String>): FloatArray? = shares(texts)?.let(::standardised)

    /** The [shares] of the words in a text standardised by their mean and spread over the texts. */
    fun standardised(shares: FloatArray): FloatArray = FloatArray(words.size) { (shares[it] - means[it]) / spreads[it] }

    /** The words with their means and spreads, as kept. */
    fun pack(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(words.size)
            words.forEach(out::writeUTF)
            means.forEach(out::writeFloat)
            spreads.forEach(out::writeFloat)
        }
        return bytes.toByteArray()
    }

    companion object {
        /**
         * How many words a manner is measured by: of 100, 300 and 1000 tried on the user's books (1418 works with a
         * text), 1000 told the author of a work the best — its nearest work by the same author in 65 times of 100 — and
         * the user's grade.
         */
        const val SIZE = 1000

        /** The least spread of a word's share: of a word of the same share in every text, a division by it. */
        private const val LEAST_SPREAD = 1e-9f

        fun unpack(id: Long, texts: Int, bytes: ByteArray): MannerWords =
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val n = input.readInt()
                val words = List(n) { input.readUTF() }
                val means = FloatArray(n) { input.readFloat() }
                val spreads = FloatArray(n) { input.readFloat() }
                MannerWords(id, words, means, spreads, texts)
            }

        /**
         * The words [words] with the mean and the spread of their [shares] in every text that has a word ([shares] of a
         * model of no means yet), under the id [id], fitted on [texts] items with a text.
         */
        fun fitted(id: Long, words: List<String>, shares: Collection<FloatArray>, texts: Int): MannerWords {
            val n = words.size
            val means = FloatArray(n)
            val spreads = FloatArray(n)
            if (shares.isEmpty()) return MannerWords(id, words, means, FloatArray(n) { 1f }, texts)
            for (k in 0 until n) {
                var sum = 0.0
                var square = 0.0
                shares.forEach { s ->
                    sum += s[k]
                    square += s[k].toDouble() * s[k]
                }
                val mean = sum / shares.size
                means[k] = mean.toFloat()
                spreads[k] =
                    sqrt((square / shares.size - mean * mean).coerceAtLeast(0.0)).toFloat().coerceAtLeast(LEAST_SPREAD)
            }
            return MannerWords(id, words, means, spreads, texts)
        }
    }
}

/**
 * THE TALLY OF THE WORDS OF EVERY TEXT a manner's words are chosen from, one text at a time: of every word its share in
 * every text summed — a text weighing alike, whatever its length — how often it was met and how often with a capital.
 * The chosen ones ([chosen]) are the most frequent of the words written with a capital no more than half the time: a
 * word mostly capitalised is a name, of a character or a place, of what a work is about rather than of how it is
 * written.
 */
internal class WordTally {
    private class Word {
        var share = 0.0
        var seen = 0
        var capital = 0
    }

    private val tally = HashMap<String, Word>()

    /** The [texts] of one work, its parts, counted. */
    fun add(texts: List<String>) {
        val own = HashMap<String, Int>()
        var total = 0
        texts.forEach { text ->
            TextWords.forEach(text) { word, capital ->
                total++
                own.merge(word, 1, Int::plus)
                val w = tally.getOrPut(word) { Word() }
                w.seen++
                if (capital) w.capital++
            }
        }
        if (total == 0) return
        own.forEach { (word, n) -> tally.getValue(word).share += n.toDouble() / total }
    }

    /** The [size] most frequent words, names left out, the most frequent first. */
    fun chosen(size: Int): List<String> =
        tally.entries
            .filter { (_, w) -> w.capital * 2 <= w.seen }
            .sortedWith(compareByDescending<Map.Entry<String, Word>> { it.value.share }.thenBy { it.key })
            .take(size)
            .map { it.key }
}
