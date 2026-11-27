package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.RunMemoryMeasure
import io.github.vlsergey.recommend4me.onnx.RunMemoryRecord
import io.github.vlsergey.recommend4me.onnx.RunShape
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import kotlin.test.Test
import org.junit.jupiter.api.Tag

/** What a run of e5 takes of the card, as the plugin tells it, against what was measured of its graph. */
class E5RunMemoryTest {
    private val model = defaultModelsDir().resolve(E5Encoder.MODEL)
    private val rows = E5Encoder.BATCHING.cardRows
    private val shapes =
        listOf(
            RunShape(rows, E5Encoder.WINDOW / 8),
            RunShape(rows, E5Encoder.WINDOW / 2),
            RunShape(rows / 4, E5Encoder.WINDOW),
            RunShape(1, E5Encoder.WINDOW),
        )

    @Test
    fun `the memory told of a run bounds what was measured of this runtime and model`() =
        RunMemoryRecord.checkTold(GRAPH, E5Encoder.BATCHING, shapes, model)

    // Minutes, and a card: run by its name when the runtime or the model changes
    @Tag("ci")
    @Test
    fun `the memory of a run is measured on the card and kept`() {
        RunMemoryMeasure.record(GRAPH, model, shapes + RunShape(rows, E5Encoder.BATCHING.page))
        RunMemoryRecord.checkTold(GRAPH, E5Encoder.BATCHING, shapes, model)
    }

    private companion object {
        const val GRAPH = "e5-small"
    }
}
