package io.github.vlsergey.recommend4me.encoder

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import io.github.vlsergey.recommend4me.onnx.sequence
import java.nio.LongBuffer
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag

// More than a few seconds: in CI alone — the local build's tests take a minute at most together (user, 2026-11-18)
@Tag("ci")
class BgeM3EncoderTest {

    private val onnx = Onnx(defaultModelsDir())
    private val encoder = BgeM3Encoder(onnx)

    private fun cos(a: FloatArray, b: FloatArray) = a.indices.sumOf { (a[it] * b[it]).toDouble() }

    /** The hidden state of every piece of the one text of [inputs], by the model's own graph. */
    private fun hiddenOf(inputs: Map<String, OnnxTensor>): Array<FloatArray> {
        var hidden: Array<FloatArray>? = null
        onnx.inBatches(BgeM3Encoder.MODEL, listOf(0), { 1 }, BgeM3Encoder.BATCHING) { _, graph ->
            hidden = graph.sequence(inputs)[0]
        }
        return hidden!!
    }

    @Test
    fun `a Russian query finds the English text about the same`() {
        assumeTrue(encoder.ready(), "bge-m3 files are not in the models folder")
        val (dragons, college) =
            encoder
                .encode(
                    listOf(
                        "An epic fantasy adventure: you ride dragons, fight knights and explore a magic kingdom.",
                        "A romance at college: you date your classmates, attend lectures and go to parties.",
                    )
                )
                .map { it!! }
        val dragonsQuery = encoder.encode(listOf("фэнтези с драконами"), TextKind.QUERY).single()!!
        val collegeQuery = encoder.encode(listOf("студенческая жизнь в университете"), TextKind.QUERY).single()!!
        assertEquals(BgeM3Encoder.DIM, dragons.size)
        assertTrue(cos(dragonsQuery, dragons) > cos(dragonsQuery, college))
        assertTrue(cos(collegeQuery, college) > cos(collegeQuery, dragons))
    }

    @Test
    fun `a detective in Victorian London is not a Victor in London`() {
        assumeTrue(encoder.ready(), "bge-m3 files are not in the models folder")
        val (holmes, victor) =
            encoder
                .encode(
                    listOf(
                        "Шерлок Холмс расследует загадочное убийство в туманном Лондоне конца девятнадцатого века.",
                        "Виктор переезжает в Лондон и открывает кофейню на окраине.",
                    )
                )
                .map { it!! }
        val q = encoder.encode(listOf("детектив в викторианском Лондоне"), TextKind.QUERY).single()!!
        assertTrue(cos(q, holmes) > cos(q, victor), "${cos(q, holmes)} vs ${cos(q, victor)}")
    }

    @Test
    fun `a long text gives a vector for every window, each where it lies, the next half past it, empty ones none`() {
        assumeTrue(encoder.ready(), "bge-m3 files are not in the models folder")
        val long = (1..400).joinToString(" ") { "Глава $it: герой идёт дальше." }
        val (windows, empty) = encoder.encodeWindows(listOf(long, ""))
        assertTrue(windows.size > 1)
        windows.forEach { assertEquals(1.0, cos(it, it), 1e-3) }
        assertTrue(empty.isEmpty())
        val spans = encoder.windowSpans(long)
        assertEquals(windows.size, spans.size)
        assertEquals(0, spans.first().first)
        assertEquals(long.length, spans.last().last + 1)
        // Scenes, each overlapping the next (by half its pieces): a scene cut at the edge of one stands whole in the
        // next
        spans.zipWithNext().forEach { (a, b) ->
            assertTrue(b.first > a.first && b.first < a.last, "$a $b")
        }
        assertNull(encoder.encode(listOf("   ")).single())
    }

    @Test
    fun `a long text is read whole in one pass, its pieces of the same pass, the longest beside the shortest`() {
        val pieces = encoder.pieces
        assumeTrue(pieces.ready(), "the ColBERT head of bge-m3 is not in the models folder")
        // Of some three thousand pieces: twelve windows, read as one
        val long = (1..400).joinToString(" ") { "Глава $it: герой идёт дальше." }
        val short = "Дракон спит под холмом."
        val (vector, shortVector, blank) = encoder.encode(listOf(long, short, " "))
        assertNull(blank)
        val (whole, shortWhole) = pieces.encodeWhole(listOf(long, short), null)
        assertEquals(1, whole.windows.size, "one vector of the whole text")
        assertTrue(cos(vector!!, whole.windows.single()) > 0.9999, "the vector of the text, of the same pass")
        assertTrue(cos(shortVector!!, shortWhole.windows.single()) > 0.9999)
        assertEquals(listOf(0 until long.length), whole.spans, "the one span of the whole text")
        assertEquals(encoder.cutOf(listOf(long)).single().ids.size, whole.pieces.size, "every piece read")
        // Not the mean of its windows: a vector of its own
        val windows = encoder.encodeWindows(listOf(long)).single()
        assertTrue(windows.size > 2)
        assertTrue(windows.all { cos(it, vector) < 0.9999 })
    }

    @Test
    fun `the graph run gives the first piece's hidden state of the model's, within half precision`() {
        assumeTrue(encoder.ready(), "bge-m3 files are not in the models folder")
        val texts = listOf("Шерлок Холмс расследует загадочное убийство в Лондоне.", "A dragon sleeps under the hill.")
        val tokenizer =
            HuggingFaceTokenizer.newInstance(
                onnx.path(BgeM3Encoder.TOKENIZER),
                mapOf("addSpecialTokens" to "true", "truncation" to "false", "padding" to "false"),
            )
        val windows = encoder.encodeWindows(texts).map { it.single() }
        texts.forEachIndexed { t, text ->
            val ids = tokenizer.encode(text, true, false).ids
            val shape = longArrayOf(1, ids.size.toLong())
            val inputs =
                mapOf(
                    "input_ids" to OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(ids), shape),
                    "attention_mask" to
                        OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape),
                )
            val first = hiddenOf(inputs)[0]
            val norm = sqrt(first.sumOf { (it * it).toDouble() }).toFloat()
            val whole = FloatArray(first.size) { first[it] / norm }
            assertTrue(cos(whole, windows[t]) > 0.9999, "${cos(whole, windows[t])}")
        }
    }

    @Test
    fun `the pieces are the ColBERT head of the model's every hidden state, projected as asked`() {
        val pieces = encoder.pieces
        assumeTrue(pieces.ready(), "the ColBERT head of bge-m3 is not in the models folder")
        val text = "Шерлок Холмс расследует загадочное убийство в Лондоне."
        val (got, blank) = pieces.encode(listOf(text, "  "), null)
        assertTrue(blank.pieces.isEmpty() && blank.windows.isEmpty())
        val windows = encoder.encodeWindows(listOf(text)).single()
        assertEquals(windows.size, got.windows.size)
        assertTrue(cos(windows.single(), got.windows.single()) > 0.9999, "the window's vector as before")
        assertEquals(encoder.windowSpans(text), got.spans, "the windows where the text cut alone puts them")
        assertTrue(blank.spans.isEmpty())
        val long = List(60) { text }.joinToString(" ")
        val spans = pieces.encode(listOf(long), null).single().spans
        assertTrue(spans.size > 2)
        assertEquals(encoder.windowSpans(long), spans, "the windows of a long text where the text cut alone puts them")

        // The model's own hidden states, and the head of its file applied to them
        val tokenizer =
            HuggingFaceTokenizer.newInstance(
                onnx.path(BgeM3Encoder.TOKENIZER),
                mapOf("addSpecialTokens" to "true", "truncation" to "false", "padding" to "false"),
            )
        val ids = tokenizer.encode(text, true, false).ids
        val shape = longArrayOf(1, ids.size.toLong())
        val inputs =
            mapOf(
                "input_ids" to OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(ids), shape),
                "attention_mask" to
                    OnnxTensor.createTensor(onnx.env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape),
            )
        val hidden = hiddenOf(inputs)
        val (weight, bias) = head()
        fun headOf(h: FloatArray) =
            unit(
                FloatArray(DIM) { o ->
                    bias[o] + (0 until DIM).sumOf { weight[o * DIM + it].toDouble() * h[it] }.toFloat()
                }
            )
        assertEquals(ids.size - 2, got.pieces.size, "a vector of every piece, none of the special ones")
        got.pieces.forEachIndexed { t, v -> assertTrue(cos(headOf(hidden[t + 1]), v) > 0.999, "piece $t") }

        // A projection of two rows: the head's first two numbers, of unit length
        val two =
            PieceProjection(
                "two",
                listOf(FloatArray(DIM) { if (it == 0) 1f else 0f }, FloatArray(DIM) { if (it == 1) 1f else 0f }),
            )
        val projected = pieces.encode(listOf(text), two).single().pieces
        val line = pieces.query(text, two)
        projected.forEachIndexed { t, v ->
            val whole = headOf(hidden[t + 1])
            assertTrue(cos(unit(floatArrayOf(whole[0], whole[1])), v) > 0.999, "piece $t projected")
            assertTrue(cos(v, line[t]) > 0.999, "a line read as a text")
        }
        assertEquals(text.length, pieces.pieceSpans(text).last()!!.last + 1)
    }

    private fun unit(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    /** The head of its file, half precision: the weight [out, in] and the bias. */
    private fun head(): Pair<FloatArray, FloatArray> =
        java.util.zip.ZipFile(onnx.path(BgeM3Pieces.HEAD).toFile()).use { zip ->
            fun tensor(suffix: String): FloatArray {
                val bytes =
                    zip.getInputStream(zip.entries().asSequence().first { it.name.endsWith(suffix) }).readAllBytes()
                val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                return FloatArray(bytes.size / 2) { java.lang.Float.float16ToFloat(b.getShort()) }
            }
            tensor("/data/0") to tensor("/data/1")
        }

    private companion object {
        const val DIM = BgeM3Encoder.DIM
    }
}
