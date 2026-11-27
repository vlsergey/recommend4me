package io.github.vlsergey.recommend4me.encoder

import ai.djl.huggingface.tokenizers.Encoding
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.djl.huggingface.tokenizers.jni.CharSpan
import ai.onnxruntime.OnnxTensor
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.OnnxSplice
import io.github.vlsergey.recommend4me.onnx.rows
import java.nio.LongBuffer
import java.nio.file.Files
import kotlin.math.sqrt

/**
 * A STRONG MULTILINGUAL SENTENCE ENCODER: BAAI/bge-m3 (XLM-RoBERTa large, MIT) as an ONNX graph in half precision, its
 * dense vector — the hidden state of the first piece, of unit length — 1024 numbers. The sibling of the reranker
 * (bge-reranker-v2-m3): what it finds near a line is what that one reads. It replaced multilingual-e5-small, whose 384
 * numbers found "a detective in Victorian London" in the texts naming a Victor and London.
 *
 * No prefixes: bge-m3 reads a query and a passage alike.
 *
 * A TEXT IS READ WHOLE, in one pass, as far as the [LONGEST] pieces bge-m3 was trained to read of one text: its vector
 * the first piece's of that pass (owner, 2026-11-27: a description is one vector, not the mean of its windows). Only a
 * chapter is read in windows ([encodeWindows]): a scene the search finds, not the chapter's mean.
 */
class BgeM3Encoder(private val onnx: Onnx) : TextEncoder {

    // The graph run: the model's, its output the first piece's hidden state alone, in the half precision it is computed
    // in — not every piece's, cast to floats, brought back of the card for the first one's sake
    init {
        onnx.madeOf(FIRST_PIECE, MODEL) { OnnxSplice.firstPiece(it, HIDDEN_STATE, FIRST_PIECE_OUTPUT) }
    }

    internal val tokenizer: HuggingFaceTokenizer by lazy {
        val p = onnx.path(TOKENIZER)
        check(Files.isRegularFile(p)) { "The bge-m3 tokenizer is missing: ${p.toAbsolutePath()}" }
        HuggingFaceTokenizer.newInstance(
            p,
            mapOf("addSpecialTokens" to "false", "truncation" to "false", "padding" to "false"),
        )
    }

    /** The ids that open and close every window: <s> and </s> of XLM-RoBERTa. */
    internal val bos: Long by lazy { tokenizer.encode("", true, false).ids.first() }
    internal val eos: Long by lazy { tokenizer.encode("", true, false).ids.last() }

    override val id = ENCODER
    override val dim = DIM

    override fun ready(): Boolean = onnx.has(MODEL) && onnx.has(TOKENIZER)

    /** The vectors of every piece by bge-m3's ColBERT head ([BgeM3Pieces]): of the head's file beside the model's. */
    override val pieces: PieceEncoder = BgeM3Pieces(onnx, this)

    /** Token ids of every text of [texts], without special tokens: the texts handed to the tokenizer together. */
    internal fun piecesOf(texts: List<String>): List<LongArray> = cutOf(texts).map { it.ids }

    /** Every text of [texts] cut into its pieces, no special tokens: the texts handed to the tokenizer together. */
    internal fun cutOf(texts: List<String>): List<Encoding> =
        if (texts.isEmpty()) emptyList() else tokenizer.batchEncode(texts, false, false).toList()

    /**
     * Vectors of texts of any length, unit length, every text read whole in one pass ([wholeOf]); an empty text gets
     * null. A query and a passage are read alike.
     */
    override fun encode(texts: List<String>, kind: TextKind): List<FloatArray?> {
        val written = texts.indices.filter { texts[it].isNotBlank() }
        val vectors = embedIds(piecesOf(written.map { texts[it] }).map(::wholeOf))
        val out = arrayOfNulls<FloatArray>(texts.size)
        written.forEachIndexed { k, t -> out[t] = vectors[k] }
        return out.toList()
    }

    /** The one window a text is read in whole: its pieces, as many as bge-m3 reads of one, between <s> and </s>. */
    internal fun wholeOf(ids: LongArray): LongArray =
        longArrayOf(bos) + ids.copyOf(minOf(ids.size, LONGEST_ROOM)) + longArrayOf(eos)

    override val windowsId: String = "$ENCODER/$SCENE-$SCENE_STEP"

    /**
     * A vector for every window of every text, however many windows a text has: of [SCENE] pieces each, its two special
     * ones among them, every one beginning half a window after the one before — a scene cut at the edge of one stands
     * whole in the next; the last ending with the text. A text of one window or less is one (user, 2026-11-13: a scene,
     * not a page of 512 pieces end to end).
     */
    override fun encodeWindows(texts: List<String>): List<List<FloatArray>> {
        val windows = ArrayList<LongArray>()
        val owner = ArrayList<Int>()
        val written = texts.indices.filter { texts[it].isNotBlank() }
        piecesOf(written.map { texts[it] }).forEachIndexed { k, ids ->
            val t = written[k]
            scenesOf(ids.size).forEach { chunk ->
                windows += longArrayOf(bos) + LongArray(chunk.count()) { ids[chunk.first + it] } + longArrayOf(eos)
                owner += t
            }
        }
        val vectors = embedIds(windows)
        val out = List(texts.size) { ArrayList<FloatArray>() }
        windows.indices.forEach { w -> out[owner[w]] += vectors[w] }
        return out
    }

    /** The places of the pieces of every window [encodeWindows] makes of a text of [size] pieces, in order. */
    internal fun scenesOf(size: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var from = 0
        while (from < size) {
            val to = minOf(from + SCENE_ROOM, size)
            out += from until to
            if (to == size) break
            from += SCENE_STEP
        }
        return out
    }

    /** The windows of [encodeWindows] in the text, by the characters of their first and last pieces. */
    override fun windowSpans(text: String): List<IntRange> =
        if (text.isBlank()) emptyList()
        else tokenizer.encode(text, false, false).charTokenSpans.let { spansOf(it, scenesOf(it.size)) }

    /** The windows of the pieces [chunks] in a text, by the characters of its pieces' [spans]. */
    internal fun spansOf(spans: Array<CharSpan?>, chunks: List<IntRange>): List<IntRange> = chunks.mapNotNull { chunk ->
        // A piece of no characters of its own (a joined byte) has no span: the window's ends are its pieces that
        // have
        val from = chunk.firstNotNullOfOrNull { spans[it] }?.start ?: return@mapNotNull null
        val to = chunk.reversed().firstNotNullOfOrNull { spans[it] }?.end ?: return@mapNotNull null
        from until to
    }

    /**
     * Vectors of ready windows. Windows are SORTED BY LENGTH before batching: a batch is padded to its longest window,
     * and a ten-piece genre beside a five-hundred-piece description would run through fifty times the work.
     */
    private fun embedIds(windows: List<LongArray>): List<FloatArray> {
        val out = arrayOfNulls<FloatArray>(windows.size)
        val order = windows.indices.sortedBy { windows[it].size }
        for (batch in RunBatches.of(order, { windows[it].size }, onnx.onGpu(FIRST_PIECE))) {
            val len = batch.maxOf { windows[it].size }
            val ids = LongArray(batch.size * len) { PAD }
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
            // The dense vector of bge-m3: the first piece's hidden state, as it is trained
            val first = onnx.rows(FIRST_PIECE, inputs)
            batch.forEachIndexed { r, w -> out[w] = unit(first[r]) }
        }
        return out.map { it!! }
    }

    private fun unit(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x.toDouble() * x
        n = sqrt(n)
        return if (n > LEAST_NORM) FloatArray(v.size) { (v[it] / n).toFloat() } else v
    }

    companion object {
        /** The least length a vector is made of unit length from: a shorter one has no direction to keep. */
        private const val LEAST_NORM = 1e-9

        /** Stored with every vector: vectors of another encoder are of another space and are made again. */
        const val ENCODER = "bge-m3"
        const val MODEL = "bge-m3-fp16.onnx"
        const val TOKENIZER = "bge-m3-tokenizer.json"

        /**
         * The graph run, made of the [MODEL]: its output [HIDDEN_STATE] cut to the first piece's, [FIRST_PIECE_OUTPUT].
         */
        private const val FIRST_PIECE = "bge-m3-first-piece"
        internal const val HIDDEN_STATE = "last_hidden_state"
        internal const val FIRST_PIECE_OUTPUT = "first_piece"
        const val DIM = 1024
        internal const val PAD = 1L

        /** The pieces bge-m3 reads of one text, its two special ones among them: what it was trained to read. */
        private const val LONGEST = 8192

        /** The pieces of a text read whole besides the two special ones. */
        internal const val LONGEST_ROOM = LONGEST - 2

        /** The pieces of a window ([encodeWindows]), its two special ones among them: a scene, not a page. */
        internal const val SCENE = 256

        /** The pieces of the text a window holds besides its two special ones. */
        private const val SCENE_ROOM = SCENE - 2

        /**
         * How far a window begins after the one before: half of it, so each overlaps the next by half — every piece
         * encoded twice, kept so till there are good ways of finding a meaning in a text (owner, 2026-11-25).
         */
        internal const val SCENE_STEP = SCENE_ROOM / 2
    }
}
