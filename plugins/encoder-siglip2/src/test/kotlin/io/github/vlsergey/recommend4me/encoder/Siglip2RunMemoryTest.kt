package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.RunMemoryMeasure
import io.github.vlsergey.recommend4me.onnx.RunMemoryRecord
import io.github.vlsergey.recommend4me.onnx.RunShape
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import kotlin.test.Test
import org.junit.jupiter.api.Tag

/**
 * What a run of SigLIP2 takes of the card, as the plugin tells it, against what was measured of its graph: a batch of
 * pictures of so many patches each.
 */
class Siglip2RunMemoryTest {
    private val model = defaultModelsDir().resolve(Siglip2Encoder.MODEL)
    private val rows = Siglip2Encoder.BATCHING.cardRows
    private val shapes =
        listOf(
            RunShape(rows, Siglip2Encoder.MAX_PATCHES / 4),
            RunShape(rows, Siglip2Encoder.MAX_PATCHES / 2),
            RunShape(rows / 4, Siglip2Encoder.MAX_PATCHES),
            RunShape(1, Siglip2Encoder.MAX_PATCHES),
        )

    @Test
    fun `the memory told of a run bounds what was measured of this runtime and model`() =
        RunMemoryRecord.checkTold(GRAPH, Siglip2Encoder.BATCHING, shapes, model)

    // Minutes, and a card: run by its name when the runtime or the model changes
    @Tag("ci")
    @Test
    fun `the memory of a run is measured on the card and kept`() {
        RunMemoryMeasure.record(GRAPH, model, shapes + RunShape(rows, Siglip2Encoder.BATCHING.page))
        RunMemoryRecord.checkTold(GRAPH, Siglip2Encoder.BATCHING, shapes, model)
    }

    private companion object {
        const val GRAPH = "siglip2"
    }
}
