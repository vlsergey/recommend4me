package io.github.vlsergey.recommend4me.suggestion

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * THE MODEL OF NAIVE BAYES, kept by the application ([FittedSuggester.model]): the counts of every work fitted on
 * ([counts]) under the values of the other facets ([contextKeys], by their keys — a later task numbers them its own
 * way) and the two settings chosen — as large as the values and their contexts, no work in it. A work asked is scored
 * of its own other values alone ([on]): no other work of the source read.
 *
 * A WORK IS NOT ITS OWN WITNESS: every work of the source was counted, and a work asked has its own pairs, as its
 * portion tells them, taken out of the counts — the model knows no work by its id, a million ids being no model's to
 * keep. A work new since the fitting, or answered on since, is judged by counts short of its own word by as much, never
 * by its own word.
 */
internal class BayesModel(
    val alpha: Double,
    val scale: Double,
    val contextKeys: List<String>,
    val counts: BayesCounts,
) {
    /** The chances of the works of [task] by the counts kept; null when the task is not of the model's values. */
    fun on(task: SuggestionTask): SuggestionScores? {
        if (task.valueCount != counts.m) return null
        val column = contextKeys.withIndex().associate { (c, key) -> key to c }
        // The task's own numbers of the other facets' values as the counts' — a value the counts never saw left out
        val own = IntArray(task.contextCount) { column[task.contextKeys[it]] ?: -1 }
        val works = BayesWork.of(task, SuggestionPairs(task), own)
        return SuggestionScores { rows -> rows.map { i -> counts.chances(works[i].contexts, works[i], alpha, scale) } }
    }

    /** Every value's share labelled 1 among the pairs of the works counted. */
    fun shares(): DoubleArray =
        DoubleArray(counts.m) { v ->
            val all = counts.zero[v] + counts.one[v]
            if (all > 0) counts.one[v] / all else 0.0
        }

    fun pack(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(bytes), BUFFER)).use { out ->
            out.writeInt(MAGIC)
            out.writeDouble(alpha)
            out.writeDouble(scale)
            out.writeInt(contextKeys.size)
            contextKeys.forEach(out::writeUTF)
            counts.write(out)
        }
        return bytes.toByteArray()
    }

    companion object {
        /** The mark of a model of naive Bayes, and of its format: of the counts alone, no work. */
        private const val MAGIC = 0x4E424D32

        private const val BUFFER = 1 shl 16

        /** The model kept; null of bytes not of its making. */
        fun read(bytes: ByteArray): BayesModel? =
            try {
                DataInputStream(BufferedInputStream(GZIPInputStream(ByteArrayInputStream(bytes)), BUFFER)).use { input
                    ->
                    if (input.readInt() != MAGIC) return null
                    val alpha = input.readDouble()
                    val scale = input.readDouble()
                    val keys = List(input.readInt()) { input.readUTF() }
                    BayesModel(alpha, scale, keys, BayesCounts.read(input))
                }
            } catch (_: IOException) {
                null
            }
    }
}
