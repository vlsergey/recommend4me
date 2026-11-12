package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.onnx.Onnx
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * THE MODEL OF THE NEAREST WORKS, kept by the application ([FittedSuggester.model]): the works fitted on — their ids,
 * where each lies ([vectors], [Neighbours.DIMENSIONS] numbers of unit length of each, in half precision as kept), what
 * each says of the values ([labels]) — the share of every value among them all ([share]), the [k] chosen, and the
 * [layout] of the blocks the works were projected of. A work asked is projected the same way of its own inputs alone
 * and scored by its nearest among the works kept ([on]): no other work of the source read.
 */
internal class KnnModel(
    val k: Int,
    val layout: List<Pair<String, Int>>,
    val ids: List<String>,
    val vectors: Floats,
    val labels: Labels,
    val share: DoubleArray,
) {
    /**
     * The chances of the works of [task] by their nearest among the works kept; null when the task is not of the
     * model's values or of its blocks. A work kept is not its own neighbour.
     */
    fun on(task: SuggestionTask, onnx: Onnx?): SuggestionScores? {
        val asked =
            Neighbours.project(task.inputs, task.itemCount, layout).takeIf { task.valueCount == labels.m }
                ?: return null
        val row = ids.withIndex().associate { (i, id) -> id to i }
        val self = IntArray(task.itemCount) { row[task.itemIds[it]] ?: -1 }
        val near = Neighbours.among(asked, task.itemCount, vectors, ids.size, self, onnx)
        return SuggestionScores { rows ->
            rows.map { i -> labels.chances(near.rows[i], near.likeness[i], share, k) { true } }
        }
    }

    fun pack(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(BufferedOutputStream(bytes, BUFFER)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(k)
            out.writeInt(layout.size)
            layout.forEach { (name, cols) ->
                out.writeUTF(name)
                out.writeInt(cols)
            }
            out.writeInt(ids.size)
            ids.forEach(out::writeUTF)
            val count = ids.size * Neighbours.DIMENSIONS
            val halves = ByteBuffer.allocate(count * HALF).order(ByteOrder.LITTLE_ENDIAN)
            for (q in 0 until count) halves.putShort(java.lang.Float.floatToFloat16(vectors[q]))
            out.write(halves.array())
            labels.write(out)
            share.forEach(out::writeDouble)
        }
        return bytes.toByteArray()
    }

    companion object {
        /** The mark of a model of the nearest works, and of its format. */
        private const val MAGIC = 0x4B4E4E31

        /** The bytes of a number in half precision. */
        private const val HALF = 2

        private const val BUFFER = 1 shl 16

        /** The model of the works of [task] fitted on, with the [k] chosen. */
        fun of(task: SuggestionTask, k: Int): KnnModel {
            val layout = task.inputs.map { it.name to it.dim }
            val vectors = Neighbours.project(task.inputs, task.itemCount, layout)!!
            val labels = Labels.of(SuggestionPairs(task))
            return KnnModel(k, layout, task.itemIds, vectors, labels, labels.share { true })
        }

        /** The model kept; null of bytes not of its making. */
        fun read(bytes: ByteArray): KnnModel? =
            try {
                DataInputStream(BufferedInputStream(ByteArrayInputStream(bytes), BUFFER)).use { input ->
                    if (input.readInt() != MAGIC) return null
                    val k = input.readInt()
                    val layout = List(input.readInt()) { input.readUTF() to input.readInt() }
                    val ids = List(input.readInt()) { input.readUTF() }
                    val count = ids.size * Neighbours.DIMENSIONS
                    val raw = ByteArray(count * HALF).also(input::readFully)
                    val halves = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                    val floats = FloatArray(count) { java.lang.Float.float16ToFloat(halves.getShort()) }
                    val vectors = Floats((count).coerceAtLeast(1)).also { it.put(floats, 0, 0, count) }
                    val labels = Labels.read(input)
                    val share = DoubleArray(labels.m) { input.readDouble() }
                    KnnModel(k, layout, ids, vectors, labels, share)
                }
            } catch (_: IOException) {
                null
            }
    }
}
