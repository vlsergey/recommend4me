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
