package io.github.vlsergey.recommend4me.onnx

import ai.onnxruntime.OrtEnvironment
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A batch a graph runs: [rows] rows of [length] pieces each. */
data class RunShape(val rows: Int, val length: Int) {
    override fun toString() = "${rows}x$length"
}

/**
 * WHAT A RUN OF A GRAPH WAS MEASURED TO TAKE OF THE CARD, kept beside the tests of its plugin ([file]): of which
 * runtime ([runtime], the version of ONNX Runtime) and which model ([model], its file's name, size and the print of its
 * ends), the least memory its weights and a run of one row of 8 pieces go through ([weights]), and of every shape the
 * least a run goes through beyond them ([measured]). Written by the measure on the card ([RunMemoryMeasure]); read by
 * the check of every build ([checkTold]), which tells when the measure has grown stale — another runtime, another
 * model, a shape not measured, a memory told that no longer bounds what was measured.
 */
class RunMemoryRecord(
    val runtime: String,
    val model: String,
    val weights: Long,
    val measured: Map<RunShape, Long>,
) {
    /** The record as its file holds it. */
    fun text(): String = buildString {
        appendLine("# What a run of the graph takes of the card, measured by RunMemoryMeasure: do not edit by hand")
        appendLine("runtime $runtime")
        appendLine("model $model")
        appendLine("weights ${megabytes(weights)} MB")
        measured.forEach { (shape, bytes) -> appendLine("$shape ${megabytes(bytes)} MB") }
    }

    /** [bytes] in megabytes, rounded up: a record of a measure tells no less than it. */
    private fun megabytes(bytes: Long) = (bytes + (1L shl MB_SHIFT) - 1) shr MB_SHIFT

    companion object {
        /** A megabyte's shift: the record is kept in megabytes, what the measure is made in steps of. */
        private const val MB_SHIFT = 20

        /** The record of the plugin's tests' folder: one file of every graph measured. */
        fun file(graph: String): Path = Path.of("src", "test", "resources", "run-memory", "$graph.txt")

        /** The record of [graph], or null when it was never measured. */
        fun read(graph: String): RunMemoryRecord? =
            file(graph)
                .takeIf { Files.isRegularFile(it) }
                ?.let {
                    parse(Files.readString(it))
                }

        /** The record its file's [text] holds ([text]). */
        fun parse(text: String): RunMemoryRecord {
            val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            fun value(key: String) = lines.first { it.startsWith("$key ") }.removePrefix("$key ")
            fun megabytes(text: String) = text.removeSuffix(" MB").trim().toLong() shl MB_SHIFT
            val shapes =
                lines
                    .filter { it.first().isDigit() }
                    .associate { line ->
                        val (shape, bytes) = line.split(" ", limit = 2)
                        val (rows, length) = shape.split("x").map(String::toInt)
                        RunShape(rows, length) to megabytes(bytes)
                    }
            return RunMemoryRecord(value("runtime"), value("model"), megabytes(value("weights")), shapes)
        }

        /** The version of the runtime of the process. */
        fun runtime(): String = OrtEnvironment.getEnvironment().version

        /**
         * The model file [path] told apart from another: its name, its size and a hash of its first and last megabyte —
         * a graph exported anew changes them, read in milliseconds where the whole would take seconds.
         */
        fun printOf(path: Path): String {
            val size = Files.size(path)
            val digest = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(path.toFile(), "r").use { f ->
                val end = 1L shl MB_SHIFT
                for (at in listOf(0L, maxOf(0L, size - end))) {
                    val bytes = ByteArray(minOf(end, size - at).toInt())
                    f.seek(at)
                    f.readFully(bytes)
                    digest.update(bytes)
                }
            }
            val hash = digest.digest().take(HASH_BYTES).joinToString("") { "%02x".format(it) }
            return "${path.name} $size $hash"
        }

        private const val HASH_BYTES = 8

        /**
         * THE MEMORY A PLUGIN TELLS OF A RUN OF ITS GRAPH STILL BOUNDS WHAT WAS MEASURED, of the runtime and the model
         * of now: the record of [graph] made of this runtime's version and — where the model is there, [model] — of
         * this very file; a measure of the largest batch of its [batching] and of every one of [shapes], the others the
         * plugin runs; and of every shape measured, the memory it tells no less than the measure, nor wasting more than
         * [WASTE] of it and [SLACK] besides (a batch made smaller than the card has room for). Fails with what to do
         * when the measure is stale.
         */
        fun checkTold(graph: String, batching: Batching, shapes: List<RunShape>, model: Path?) =
            checkRecord(
                graph,
                read(graph),
                runtime(),
                model?.takeIf { Files.isRegularFile(it) }?.let(::printOf),
                batching,
                shapes,
            )

        /**
         * The check of [checkTold] of the [record] of [graph] against the [runtime] of now and the print of its model
         * ([model], null where the model is not there).
         */
        fun checkRecord(
            graph: String,
            record: RunMemoryRecord?,
            runtime: String,
            model: String?,
            batching: Batching,
            shapes: List<RunShape>,
        ) {
            val memory = batching.memory
            val again = "measure it again on a card: gradlew test --tests \"*RunMemory*\" -PallTests, and commit"
            assertNotNull(record, "$graph was never measured: $again")
            assertEquals(runtime, record.runtime, "$graph was measured of another ONNX Runtime: $again")
            if (model != null) assertEquals(model, record.model, "$graph was measured of another model file: $again")
            val missing = (shapes + RunShape(batching.cardRows, batching.page)).filter { it !in record.measured }
            if (missing.isNotEmpty()) fail("$graph was not measured of the shapes $missing it runs now: $again")
            record.measured.forEach { (shape, measured) ->
                val told = memory.of(shape.rows, shape.length)
                assertTrue(
                    told >= measured,
                    "$graph of $shape takes ${measured shr MB_SHIFT} MB, told ${told shr MB_SHIFT}",
                )
                assertTrue(
                    told <= measured * WASTE + SLACK,
                    "$graph of $shape takes ${measured shr MB_SHIFT} MB, told ${told shr MB_SHIFT}: too many",
                )
            }
        }

        /** How many times what a run takes it may be told: the rest of the room is the card's lost for the batch. */
        private const val WASTE = 1.25

        /** What a run may be told beyond its [WASTE]: what a run of few pieces takes besides them. */
        private const val SLACK = 160L shl MB_SHIFT
    }
}
