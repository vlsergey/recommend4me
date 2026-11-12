package io.github.vlsergey.recommend4me.suggestion

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxGraph
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Neighbours::class.java)

/**
 * THE NEAREST WORKS OF EVERY WORK ASKED, among the works the model knows, by the cosine of what their texts and
 * pictures were encoded as: of every work asked, the rows of its nearest — the nearest first — and their likenesses,
 * [MAX_K] at most, the work itself left out where it is among them. On the card when there is one and the works asked
 * are many (a product of a chunk of them with every work known, and the nearest of each, in one graph), else by
 * OpenBLAS.
 */
internal class Neighbours(val rows: Array<IntArray>, val likeness: Array<FloatArray>, val onCard: Boolean) {
    companion object {
        /** How many nearest works are tried; the most kept of each work. */
        val KS = listOf(5, 10, 20, 40)
        const val MAX_K = 48

        /** The dimensions every work is put down to, and the seed of the projection. */
        const val DIMENSIONS = 256
        private const val SEED = 20261015L

        /** Works asked for at once; fewer asked than this are found on the processor. */
        private const val CHUNK = 512

        /** The standard operators of ONNX 1.12. */
        private const val OPSET = 17

        /**
         * The nearest of every work of [asked] ([count] of them, [DIMENSIONS] numbers each) among the works [known]
         * ([size] of them); [self] — of every work asked, its own row among the known, -1 of one not among them: left
         * out.
         */
        fun among(asked: Floats, count: Int, known: Floats, size: Int, self: IntArray, onnx: Onnx?): Neighbours {
            val wanted = minOf(MAX_K + 1, size)
            val found =
                if (count >= CHUNK && wanted > 0) onnx?.let { onCard(it, asked, count, known, size, wanted) } else null
            val (rows, likeness) = found ?: onProcessor(asked, count, known, size, wanted)
            // The work itself out: the nearest to itself, or anywhere among its equals
            val own = Array(count) { i -> rows[i].indices.filter { rows[i][it] != self[i] }.take(MAX_K) }
            return Neighbours(
                Array(count) { i -> IntArray(own[i].size) { rows[i][own[i][it]] } },
                Array(count) { i -> FloatArray(own[i].size) { likeness[i][own[i][it]] } },
                found != null,
            )
        }

        /**
         * Every work of the [inputs] of [count] works as [DIMENSIONS] numbers of unit length: its blocks of unit length
         * side by side in the order of the [layout] (the name and the width of every block), projected at random (the
         * seed fixed); null when a block of the inputs is of another width than the layout's. A block the inputs lack
         * is nought of every work.
         */
        fun project(inputs: List<InputBlock>, count: Int, layout: List<Pair<String, Int>>): Floats? {
            val byName = inputs.associateBy { it.name }
            if (layout.any { (name, cols) -> byName[name]?.let { it.vectors.cols != cols } == true }) return null
            val width = layout.sumOf { it.second }
            val x = Floats(count.toLong().times(width).toInt().coerceAtLeast(1))
            // A block's rows, each of unit length, at its place of its item's row; nought of an item without it
            var at = 0
            layout.forEach { (name, cols) ->
                val b = byName[name]
                if (b != null) {
                    val part = FloatArray(cols)
                    b.rows.forEachIndexed { k, i ->
                        b.vectors.take(k, part)
                        val norm = sqrt(part.sumOf { (it * it).toDouble() }).toFloat()
                        if (norm > 0f) {
                            for (q in 0 until cols) part[q] /= norm
                            x.put(part, 0, i * width + at, cols)
                        }
                    }
                }
                at += cols
            }
            val random = Random(SEED)
            val r =
                Floats.of(
                    FloatArray(width * DIMENSIONS) { (random.nextGaussian() / sqrt(DIMENSIONS.toDouble())).toFloat() }
                )
            val y = Floats((count * DIMENSIONS).coerceAtLeast(1))
            if (width > 0 && count > 0) Blas.product(x, width, count, width, r, DIMENSIONS, y)
            val out = FloatArray(DIMENSIONS)
            for (i in 0 until count) {
                y.take(out, 0, i * DIMENSIONS, DIMENSIONS)
                val norm = sqrt(out.sumOf { (it * it).toDouble() }).toFloat()
                if (norm > 0f) for (q in 0 until DIMENSIONS) out[q] /= norm
                y.put(out, 0, i * DIMENSIONS, DIMENSIONS)
            }
            return y
        }

        /**
         * The nearest of every work asked by a graph on the card: a chunk of them against every work known, the
         * [wanted] likeliest of each; null without a card.
         */
        private fun onCard(
            onnx: Onnx,
            asked: Floats,
            count: Int,
            known: Floats,
            size: Int,
            wanted: Int,
        ): Pair<Array<IntArray>, Array<FloatArray>>? {
            val f = OnnxGraph.FLOAT
            val graph =
                OnnxGraph("knn-nearest")
                    .input("Q", f, "rows", DIMENSIONS)
                    .supplied("Y", size.toLong(), DIMENSIONS.toLong())
                    .constant("K", longArrayOf(wanted.toLong()), 1)
                    .node("Gemm", listOf("Q", "Y"), listOf("S"), mapOf("transB" to 1))
                    .node(
                        "TopK",
                        listOf("S", "K"),
                        listOf("likeness", "rows_found"),
                        mapOf("axis" to 1L, "largest" to 1L, "sorted" to 1L),
                    )
                    .output("likeness", f, "rows", wanted)
                    .output("rows_found", OnnxGraph.INT64, "rows", wanted)
                    .bytes(OPSET)
            val all =
                OnnxTensor.createTensor(
                    onnx.env,
                    known.buffer(0, size * DIMENSIONS),
                    longArrayOf(size.toLong(), DIMENSIONS.toLong()),
                )
            val session = onnx.onCard(graph, mapOf("Y" to all)) ?: return null.also { all.close() }
            return try {
                byChunksOnCard(onnx, session, asked, count)
            } catch (e: OrtException) {
                log.warn("the nearest works could not be found on the card, the processor finds them: {}", e.message)
                null
            } finally {
                session.close()
                all.close()
            }
        }

        /**
         * Every asked work's nearest by the graph of [session] on the card, [CHUNK] works asked for at once: their rows
         * and likenesses, the nearest first.
         */
        private fun byChunksOnCard(
            onnx: Onnx,
            session: OrtSession,
            asked: Floats,
            count: Int,
        ): Pair<Array<IntArray>, Array<FloatArray>> {
            val rows = Array(count) { IntArray(0) }
            val likeness = Array(count) { FloatArray(0) }
            val chunk = FloatArray(CHUNK * DIMENSIONS)
            var from = 0
            while (from < count) {
                val n = minOf(CHUNK, count - from)
                asked.take(chunk, 0, from * DIMENSIONS, n * DIMENSIONS)
                val buffer =
                    ByteBuffer.allocateDirect(n * DIMENSIONS * Float.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                buffer.put(chunk, 0, n * DIMENSIONS).flip()
                OnnxTensor.createTensor(onnx.env, buffer, longArrayOf(n.toLong(), DIMENSIONS.toLong())).use { q ->
                    session.run(mapOf("Q" to q)).use { result ->
                        @Suppress("UNCHECKED_CAST") val values = result.get(0).value as Array<FloatArray>
                        @Suppress("UNCHECKED_CAST") val indices = result.get(1).value as Array<LongArray>
                        for (r in 0 until n) {
                            likeness[from + r] = values[r]
                            rows[from + r] = IntArray(indices[r].size) { indices[r][it].toInt() }
                        }
                    }
                }
                from += n
            }
            return rows to likeness
        }

        /**
         * The nearest of every work asked by OpenBLAS: a chunk of them against every work known, the [wanted] likeliest
         * of each kept.
         */
        private fun onProcessor(
            asked: Floats,
            count: Int,
            known: Floats,
            size: Int,
            wanted: Int,
        ): Pair<Array<IntArray>, Array<FloatArray>> {
            val rows = Array(count) { IntArray(0) }
            val likeness = Array(count) { FloatArray(0) }
            if (size == 0 || count == 0) return rows to likeness
            // Yᵀ once: a chunk's product is then Q · Yᵀ
            val all = FloatArray(size * DIMENSIONS).also { known.take(it, 0, 0, size * DIMENSIONS) }
            val yt = Floats(DIMENSIONS * size)
            val column = FloatArray(size)
            for (q in 0 until DIMENSIONS) {
                for (j in 0 until size) column[j] = all[j * DIMENSIONS + q]
                yt.put(column, 0, q * size, size)
            }
            val chunk = minOf(CHUNK, count)
            val q = Floats(chunk * DIMENSIONS)
            val s = Floats(chunk * size)
            val line = FloatArray(size)
            val part = FloatArray(chunk * DIMENSIONS)
            var from = 0
            while (from < count) {
                val n = minOf(chunk, count - from)
                asked.take(part, 0, from * DIMENSIONS, n * DIMENSIONS)
                q.put(part, 0, 0, n * DIMENSIONS)
                Blas.product(q, DIMENSIONS, n, DIMENSIONS, yt, size, s)
                for (r in 0 until n) {
                    s.take(line, 0, r * size, size)
                    val top = (0 until size).sortedByDescending { line[it] }.take(wanted)
                    rows[from + r] = top.toIntArray()
                    likeness[from + r] = FloatArray(top.size) { line[top[it]] }
                }
                from += n
            }
            return rows to likeness
        }
    }
}
