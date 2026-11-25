package io.github.vlsergey.recommend4me.onnx

import ai.onnxruntime.OnnxTensor

/**
 * Runs a graph with named inputs and returns its first output, a [rows, width] tensor of floats or of half ones, as
 * rows of floats, read as one buffer. The tensors are closed here.
 */
fun Onnx.rows(name: String, inputs: Map<String, OnnxTensor>): Array<FloatArray> {
    val session = session(name)
    try {
        session.run(inputs).use { result ->
            val tensor = result[0] as OnnxTensor
            val (rows, width) = tensor.info.shape.let { it[0].toInt() to it[1].toInt() }
            val all = tensor.floatBuffer
            return Array(rows) { r -> FloatArray(width).also { all.get(r * width, it) } }
        }
    } finally {
        inputs.values.forEach { it.close() }
    }
}

/**
 * Runs a graph with named inputs whose outputs are [first] — [rows, width] — and [pieces] — [rows, pieces, out] — of
 * floats or of half ones, and returns both as floats, each read as one buffer. The tensors are closed here.
 */
fun Onnx.rowsAndPieces(
    name: String,
    inputs: Map<String, OnnxTensor>,
    first: String,
    pieces: String,
): Pair<Array<FloatArray>, Array<Array<FloatArray>>> {
    val session = session(name)
    try {
        session.run(inputs).use { result ->
            val rows = result.get(first).get() as OnnxTensor
            val (n, width) = rows.info.shape.let { it[0].toInt() to it[1].toInt() }
            val all = rows.floatBuffer
            val firsts = Array(n) { r -> FloatArray(width).also { all.get(r * width, it) } }
            val each = result.get(pieces).get() as OnnxTensor
            val (b, t, out) = each.info.shape.let { Triple(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
            val values = each.floatBuffer
            val made = Array(b) { r -> Array(t) { p -> FloatArray(out).also { values.get((r * t + p) * out, it) } } }
            return firsts to made
        }
    } finally {
        inputs.values.forEach { it.close() }
    }
}
