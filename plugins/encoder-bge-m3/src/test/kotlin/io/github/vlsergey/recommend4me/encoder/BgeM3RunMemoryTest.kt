package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.RunMemoryMeasure
import io.github.vlsergey.recommend4me.onnx.RunMemoryRecord
import io.github.vlsergey.recommend4me.onnx.RunShape
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import kotlin.test.Test
import org.junit.jupiter.api.Tag

/**
 * What a run of bge-m3 takes of the card, as the plugin tells it ([BgeM3Encoder.MEMORY]), against what was measured of
 * the model's graph — the graphs made of it run the same layers, the pieces' output told besides ([BgeM3Pieces]): its
 * windows of a chapter, its batches of the longest, a text read whole.
 */
class BgeM3RunMemoryTest {
    private val model = defaultModelsDir().resolve(BgeM3Encoder.MODEL)
    private val rows = BgeM3Encoder.BATCHING.cardRows
    private val shapes =
        listOf(
            RunShape(rows, SHORT),
            RunShape(rows, BgeM3Encoder.SCENE),
            RunShape(rows / 4, BgeM3Encoder.BATCHING.page),
            RunShape(4, BgeM3Encoder.LONGEST / 4),
            RunShape(1, BgeM3Encoder.LONGEST / 2),
            RunShape(1, BgeM3Encoder.LONGEST),
        )

    @Test
    fun `the memory told of a run bounds what was measured of this runtime and model`() =
        RunMemoryRecord.checkTold(GRAPH, BgeM3Encoder.BATCHING, shapes, model)

    // Minutes, and a card: run by its name when the runtime or the model changes
    @Tag("ci")
    @Test
    fun `the memory of a run is measured on the card and kept`() {
        RunMemoryMeasure.record(GRAPH, model, shapes + RunShape(rows, BgeM3Encoder.BATCHING.page))
        RunMemoryRecord.checkTold(GRAPH, BgeM3Encoder.BATCHING, shapes, model)
    }

    private companion object {
        const val GRAPH = "bge-m3"

        /** A batch of short texts: titles, lines of pairings. */
        const val SHORT = 32
    }
}
