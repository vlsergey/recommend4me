package io.github.vlsergey.recommend4me.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession

/**
 * A RUN OF A GRAPH ON THE CARD OR ON THE PROCESSOR, of one batch ([Onnx.inBatches]): its session and, on the card, the
 * options that give the memory the runtime took for the run back to the card as it ends — the runtime keeps what a run
 * took for the next one else, the most any run of the session ever took, while the session lives (a reranker's search
 * held 14 GB of the card's 16 so, 2026-11-27).
 */
class GraphRun internal constructor(private val session: OrtSession, private val options: OrtSession.RunOptions?) {
    /** The input names the graph asks for. */
    val inputNames: Set<String>
        get() = session.inputNames

    /** The outputs of the graph of [inputs], read by [read] while they are open. The inputs are closed here. */
    fun <T> run(inputs: Map<String, OnnxTensor>, read: (OrtSession.Result) -> T): T =
        try {
            (if (options == null) session.run(inputs) else session.run(inputs, options)).use(read)
        } finally {
            inputs.values.forEach { it.close() }
        }
}

/** The first output of a run as rows of floats. The tensors are closed here. */
fun GraphRun.floats(inputs: Map<String, OnnxTensor>): Array<FloatArray> =
    run(inputs) { result ->
        @Suppress("UNCHECKED_CAST")
        when (val v = result[0].value) {
            is Array<*> -> v as Array<FloatArray>
            is FloatArray -> arrayOf(v)
            else -> error("Unexpected output ${v.javaClass}")
        }
    }

/**
 * The first output of a run as a [batch, tokens, width] array — the hidden states of a text encoder. The tensors are
 * closed here.
 */
fun GraphRun.sequence(inputs: Map<String, OnnxTensor>): Array<Array<FloatArray>> =
    run(inputs) { result ->
        @Suppress("UNCHECKED_CAST")
        result[0].value as Array<Array<FloatArray>>
    }

/**
 * The first output of a run, a [rows, width] tensor of floats or of half ones, as rows of floats, read as one buffer.
 * The tensors are closed here.
 */
fun GraphRun.rows(inputs: Map<String, OnnxTensor>): Array<FloatArray> =
    run(inputs) { result ->
        val tensor = result[0] as OnnxTensor
        val (rows, width) = tensor.info.shape.let { it[0].toInt() to it[1].toInt() }
        val all = tensor.floatBuffer
        Array(rows) { r -> FloatArray(width).also { all.get(r * width, it) } }
    }

/**
 * The outputs of a run [first] — [rows, width] — and [pieces] — [rows, pieces, out] — of floats or of half ones, both
 * as floats, each read as one buffer. The tensors are closed here.
 */
fun GraphRun.rowsAndPieces(
    inputs: Map<String, OnnxTensor>,
    first: String,
    pieces: String,
): Pair<Array<FloatArray>, Array<Array<FloatArray>>> =
    run(inputs) { result ->
        val rows = result.get(first).get() as OnnxTensor
        val (n, width) = rows.info.shape.let { it[0].toInt() to it[1].toInt() }
        val all = rows.floatBuffer
        val firsts = Array(n) { r -> FloatArray(width).also { all.get(r * width, it) } }
        val each = result.get(pieces).get() as OnnxTensor
        val (b, t, out) = each.info.shape.let { Triple(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
        val values = each.floatBuffer
        val made = Array(b) { r -> Array(t) { p -> FloatArray(out).also { values.get((r * t + p) * out, it) } } }
        firsts to made
    }
