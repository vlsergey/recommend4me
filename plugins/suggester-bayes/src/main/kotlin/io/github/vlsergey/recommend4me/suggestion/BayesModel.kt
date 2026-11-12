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
 * way), the two settings chosen, and what every work fitted on added to the counts, by its id ([works]) — taken out of
 * them when it is scored: a work is not its own witness. A work asked is scored of its own other values alone ([on]):
 * no other work of the source read.
 */
internal class BayesModel(
    val alpha: Double,
    val scale: Double,
    val contextKeys: List<String>,
    val counts: BayesCounts,
    val works: Map<String, BayesWork>,
) {
    /** The chances of the works of [task] by the counts kept; null when the task is not of the model's values. */
    fun on(task: SuggestionTask): SuggestionScores? {
        if (task.valueCount != counts.m) return null
        val column = contextKeys.withIndex().associate { (c, key) -> key to c }
        // The task's own numbers of the other facets' values as the counts' — a value the counts never saw left out
        val own = IntArray(task.contextCount) { column[task.contextKeys[it]] ?: -1 }
        return SuggestionScores { rows ->
            rows.map { i ->
                val contexts = task.context[i].map { own[it] }.filter { it >= 0 }.distinct().sorted().toIntArray()
                counts.chances(contexts, works[task.itemIds[i]], alpha, scale)
            }
        }
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
            out.writeInt(works.size)
            works.forEach { (id, w) ->
                out.writeUTF(id)
                w.write(out)
            }
        }
        return bytes.toByteArray()
    }

    companion object {
        /** The mark of a model of naive Bayes, and of its format. */
        private const val MAGIC = 0x4E424D31

        private const val BUFFER = 1 shl 16

        /** The model of the works of [task] fitted on, with the settings chosen. */
        fun of(task: SuggestionTask, alpha: Double, scale: Double): BayesModel {
            val works = BayesWork.of(task, SuggestionPairs(task))
            val counts = BayesCounts.of(task.valueCount, task.contextCount, works) { true }
            return BayesModel(alpha, scale, task.contextKeys, counts, task.itemIds.zip(works).toMap())
        }

        /** The model kept; null of bytes not of its making. */
        fun read(bytes: ByteArray): BayesModel? =
            try {
                DataInputStream(BufferedInputStream(GZIPInputStream(ByteArrayInputStream(bytes)), BUFFER)).use { input
                    ->
                    if (input.readInt() != MAGIC) return null
                    val alpha = input.readDouble()
                    val scale = input.readDouble()
                    val keys = List(input.readInt()) { input.readUTF() }
                    val counts = BayesCounts.read(input)
                    val works = HashMap<String, BayesWork>()
                    repeat(input.readInt()) { works[input.readUTF()] = BayesWork.read(input) }
                    BayesModel(alpha, scale, keys, counts, works)
                }
            } catch (_: IOException) {
                null
            }
    }
}
