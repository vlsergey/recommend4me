package io.github.vlsergey.recommend4me.onnx

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.OrtCUDAProviderOptions
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sin
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * THE MEMORY A RUN OF A GRAPH TAKES OF THE CARD, MEASURED: the least `gpu_mem_limit` a session opened anew and a run of
 * a shape go through, found by halving between what fails and what goes, the arena growing by what is asked
 * (`kSameAsRequested`), as the application's sessions run ([Onnx]). Another process on the card changes nothing of it:
 * the limit is the runtime's own, Windows lends the card the main memory beyond what it has. The inputs are made of
 * what the graph asks for: ids, a mask of ones, numbers for the rest; every dimension but the batch and the length is
 * the graph's own.
 */
object RunMemoryMeasure {
    private val env = OrtEnvironment.getEnvironment()

    /**
     * The graph of the [model] file measured of every one of [shapes], and the record of [graph] written anew
     * ([RunMemoryRecord.file]); without a card, the test skipped.
     */
    fun record(graph: String, model: Path, shapes: List<RunShape>): RunMemoryRecord {
        assumeTrue(Files.isRegularFile(model), "$model is not in the models folder")
        val onnx = Onnx(model.parent, gpu = true)
        assumeTrue(onnx.cudaLibraries() != null, "no CUDA libraries")
        assumeTrue(runs(model, RunShape(1, LEAST), HIGHEST), "no card to run $model on")
        val weights = least(model, RunShape(1, LEAST))
        val measured = shapes.associateWith { least(model, it) - weights }
        val record = RunMemoryRecord(RunMemoryRecord.runtime(), RunMemoryRecord.printOf(model), weights, measured)
        val file = RunMemoryRecord.file(graph)
        Files.createDirectories(file.parent)
        Files.writeString(file, record.text())
        return record
    }

    /** The least limit of the card's memory a session of [model] and a run of [shape] go through, to a [STEP]. */
    private fun least(model: Path, shape: RunShape): Long {
        var low = 0L
        var high = HIGHEST
        check(runs(model, shape, high)) { "$model of $shape does not run in ${high shr 20} MB" }
        while (high - low > STEP) {
            val middle = (low + high) / 2
            if (runs(model, shape, middle)) high = middle else low = middle
        }
        return high
    }

    /** Whether a session of [model] opens and runs [shape] within [limit] bytes of the card. */
    private fun runs(model: Path, shape: RunShape, limit: Long): Boolean =
        OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL)
            options.addCUDA(
                OrtCUDAProviderOptions(0).apply {
                    add("gpu_mem_limit", limit.toString())
                    add("arena_extend_strategy", "kSameAsRequested")
                }
            )
            try {
                env.createSession(model.toString(), options).use { session ->
                    val inputs = inputsOf(session, shape)
                    try {
                        session.run(inputs).close()
                    } finally {
                        inputs.values.forEach { it.close() }
                    }
                    true
                }
            } catch (_: OrtException) {
                // The limit refused: what the halving looks for
                false
            }
        }

    /** The inputs of [session] of a batch of [shape]: ids, a mask of ones, numbers for the rest. */
    private fun inputsOf(session: OrtSession, shape: RunShape): Map<String, OnnxTensor> =
        session.inputInfo.mapValues { (name, node) ->
            val info = node.info as TensorInfo
            val dims = info.shape.clone()
            dims[0] = shape.rows.toLong()
            if (dims.size > 1) dims[1] = shape.length.toLong()
            check(dims.all { it > 0 }) { "$name of a dimension the graph does not tell: ${info.shape.toList()}" }
            val n = dims.fold(1L) { a, d -> a * d }.toInt()
            when {
                info.type != OnnxJavaType.INT64 ->
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(n) { sin(it.toFloat()) }), dims)
                "mask" in name -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { 1 }), dims)
                "ids" in name ->
                    OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { FIRST_ID + it % IDS }), dims)
                else -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n)), dims)
            }
        }

    /** The precision of a measure: the halving stops this near. */
    private const val STEP = 8L shl 20

    /** The most a measure tries: below the card's 16 GB. */
    private const val HIGHEST = 15L shl 30

    /** The pieces of the run the weights are measured with. */
    private const val LEAST = 8

    /** The ids of the pieces run: ordinary ones, past the special ones. */
    private const val FIRST_ID = 5L
    private const val IDS = 20_000
}
