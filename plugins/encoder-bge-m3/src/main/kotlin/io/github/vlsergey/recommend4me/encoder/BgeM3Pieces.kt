package io.github.vlsergey.recommend4me.encoder

import ai.onnxruntime.OnnxTensor
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxSplice
import io.github.vlsergey.recommend4me.onnx.PiecesOutput
import io.github.vlsergey.recommend4me.onnx.rowsAndPieces
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.math.sqrt

/**
 * THE VECTOR OF EVERY PIECE BY bge-m3's ColBERT HEAD (`colbert_linear`, BAAI/bge-m3, MIT): the last hidden state of
 * every piece of a window multiplied by the head's matrix and its bias added — what bge-m3 was trained to match a
 * query's pieces with a passage's by, every one with its best (MaxSim) — and by the application's projection after it,
 * in one: (P·W)·h + P·b, on the card, in the graph of the model ([OnnxSplice.withOutputs]); the window's own vector,
 * its first piece's, out of the same run. A piece is read in the window where it stands farthest from an edge.
 *
 * The head is its file beside the model's ([HEAD]), as published: a PyTorch archive whose two tensors, the weight
 * [out, in] and the bias, are read as the raw bytes they are kept as — nothing of the archive is run.
 */
internal class BgeM3Pieces(private val onnx: Onnx, private val encoder: BgeM3Encoder) : PieceEncoder {

    override val id = "${BgeM3Encoder.ENCODER}/colbert/${BgeM3Encoder.SCENE}-${BgeM3Encoder.SCENE_STEP}"
    override val width = BgeM3Encoder.DIM

    override fun ready(): Boolean = encoder.ready() && onnx.has(HEAD)

    /** The head's weight, [out, in] row after row, and its bias. */
    private val head: Pair<FloatArray, FloatArray> by lazy { readHead(onnx.path(HEAD)) }

    override fun encode(texts: List<String>, projection: PieceProjection?): List<EncodedPieces> {
        val written = texts.indices.filter { texts[it].isNotBlank() }
        val ids = encoder.piecesOf(written.map { texts[it] })
        // Every window of every text, the text it is of and where in it it begins
        val windows = ArrayList<LongArray>()
        val owner = ArrayList<Int>()
        val begins = ArrayList<Int>()
        ids.forEachIndexed { k, own ->
            encoder.scenesOf(own.size).forEach { chunk ->
                windows +=
                    longArrayOf(encoder.bos) +
                        LongArray(chunk.count()) { own[chunk.first + it] } +
                        longArrayOf(encoder.eos)
                owner += k
                begins += chunk.first
            }
        }
        val (firsts, pieces) = run(windows, projection)
        val out = ArrayList<EncodedPieces>()
        val byText = written.withIndex().associate { (k, t) -> t to k }
        texts.indices.forEach { t ->
            val k = byText[t]
            if (k == null) {
                out += EncodedPieces(emptyList(), emptyList())
                return@forEach
            }
            val n = ids[k].size
            val best = arrayOfNulls<FloatArray>(n)
            val margin = IntArray(n) { -1 }
            val own = windows.indices.filter { owner[it] == k }
            own.forEach { w ->
                val from = begins[w]
                val len = windows[w].size - 2
                for (j in 0 until len) {
                    val m = minOf(j, len - 1 - j)
                    if (m > margin[from + j]) {
                        margin[from + j] = m
                        best[from + j] = pieces[w][j + 1]
                    }
                }
            }
            out += EncodedPieces(own.map { unit(firsts[it]) }, best.map { unit(it!!) })
        }
        return out
    }

    override fun query(line: String, projection: PieceProjection?): List<FloatArray> {
        val ids = encoder.piecesOf(listOf(line)).firstOrNull()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val window = longArrayOf(encoder.bos) + ids.copyOf(minOf(ids.size, LINE)) + longArrayOf(encoder.eos)
        val (_, pieces) = run(listOf(window), projection)
        return (1 until window.size - 1).map { unit(pieces[0][it]) }
    }

    override fun pieceSpans(text: String): List<IntRange?> =
        if (text.isBlank()) emptyList()
        else encoder.tokenizer.encode(text, false, false).charTokenSpans.map { it?.let { s -> s.start until s.end } }

    /**
     * Of every window, its first piece's vector and every piece's projected, by the graph of the [projection]: the
     * windows sorted by length, so a batch is padded to a length near its own.
     */
    private fun run(
        windows: List<LongArray>,
        projection: PieceProjection?,
    ): Pair<List<FloatArray>, List<Array<FloatArray>>> {
        val graph = graphOf(projection)
        val firsts = arrayOfNulls<FloatArray>(windows.size)
        val pieces = arrayOfNulls<Array<FloatArray>>(windows.size)
        val order = windows.indices.sortedBy { windows[it].size }
        for (batch in order.chunked(if (onnx.onGpu(graph)) BgeM3Encoder.GPU_BATCH else BgeM3Encoder.BATCH)) {
            val len = batch.maxOf { windows[it].size }
            val ids = LongArray(batch.size * len) { BgeM3Encoder.PAD }
            val mask = LongArray(batch.size * len)
            batch.forEachIndexed { r, w ->
                windows[w].forEachIndexed { j, id ->
                    ids[r * len + j] = id
                    mask[r * len + j] = 1
                }
            }
            val shape = longArrayOf(batch.size.toLong(), len.toLong())
            val inputs =
                mapOf(
                    "input_ids" to OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(ids), shape),
                    "attention_mask" to OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(mask), shape),
                )
            val (first, each) = onnx.rowsAndPieces(graph, inputs, BgeM3Encoder.FIRST_PIECE_OUTPUT, PIECES_OUTPUT)
            batch.forEachIndexed { r, w ->
                firsts[w] = first[r]
                pieces[w] = each[r]
            }
        }
        return firsts.map { it!! } to pieces.map { it!! }
    }

    /**
     * The graph of the [projection] — of none, the head's vectors as they are —, made of the model when its session is
     * first asked for.
     */
    private fun graphOf(projection: PieceProjection?): String {
        val name = "$GRAPH-${projection?.key ?: "head"}"
        val (weight, bias) = head
        val rows = projection?.rows
        // The product of the projection and the head, as the graph multiplies a piece's row by it: [in, out]
        val out = rows?.size ?: width
        val matrix = FloatArray(width * out)
        val shift = FloatArray(out)
        for (o in 0 until out) {
            val p = rows?.get(o)
            for (i in 0 until width) {
                var s = 0.0
                if (p == null) s = weight[o * width + i].toDouble()
                else for (k in 0 until width) s += p[k] * weight[k * width + i]
                matrix[i * out + o] = s.toFloat()
            }
            shift[o] = if (p == null) bias[o] else (0 until width).sumOf { p[it].toDouble() * bias[it] }.toFloat()
        }
        onnx.madeOf(name, BgeM3Encoder.MODEL) {
            OnnxSplice.withOutputs(
                it,
                BgeM3Encoder.HIDDEN_STATE,
                BgeM3Encoder.FIRST_PIECE_OUTPUT,
                PiecesOutput(PIECES_OUTPUT, matrix, shift),
            )
        }
        return name
    }

    private fun unit(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x.toDouble() * x
        n = sqrt(n)
        return if (n > 0) FloatArray(v.size) { (v[it] / n).toFloat() } else v
    }

    companion object {
        /** The head's file beside the model's, as BAAI publishes it (`colbert_linear.pt`). */
        const val HEAD = "bge-m3-colbert-linear.pt"

        /** The graph of the pieces, by the projection's key. */
        private const val GRAPH = "bge-m3-pieces"
        private const val PIECES_OUTPUT = "pieces"

        /** The pieces of a line searched for read at most: one window. */
        private const val LINE = BgeM3Encoder.SCENE - 2

        /**
         * The weight [out, in] and the bias of the head, of their raw bytes: half precision, or single by their size.
         */
        private fun readHead(file: Path): Pair<FloatArray, FloatArray> =
            ZipFile(file.toFile()).use { zip ->
                fun tensor(suffix: String, count: Int): FloatArray {
                    val entry = zip.entries().asSequence().first { it.name.endsWith(suffix) }
                    val bytes = zip.getInputStream(entry).readAllBytes()
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    return if (bytes.size == count * Float.SIZE_BYTES) FloatArray(count) { buffer.getFloat() }
                    else FloatArray(count) { java.lang.Float.float16ToFloat(buffer.getShort()) }
                }
                tensor(WEIGHT, BgeM3Encoder.DIM * BgeM3Encoder.DIM) to tensor(BIAS, BgeM3Encoder.DIM)
            }

        /** The entries of the weight and of the bias in the archive. */
        private const val WEIGHT = "/data/0"
        private const val BIAS = "/data/1"
    }
}
