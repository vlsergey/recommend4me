package io.github.vlsergey.recommend4me.encoder

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
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
            val first = onnx.runSequence(BgeM3Encoder.MODEL, inputs)[0][0]
            val norm = sqrt(first.sumOf { (it * it).toDouble() }).toFloat()
            val whole = FloatArray(first.size) { first[it] / norm }
            assertTrue(cos(whole, windows[t]) > 0.9999, "${cos(whole, windows[t])}")
        }
    }
}
