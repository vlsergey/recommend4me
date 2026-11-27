package io.github.vlsergey.recommend4me.encoder

import io.github.vlsergey.recommend4me.onnx.RunMemoryMeasure
import io.github.vlsergey.recommend4me.onnx.RunMemoryRecord
import io.github.vlsergey.recommend4me.onnx.RunShape
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import kotlin.test.Test
import org.junit.jupiter.api.Tag

/** What a run of the reranker takes of the card, as the plugin tells it, against what was measured of its graph. */
class BgeRerankerRunMemoryTest {
    private val model = defaultModelsDir().resolve(BgeReranker.MODEL)
    private val rows = BgeReranker.BATCHING.cardRows
    private val shapes =
        listOf(
            RunShape(rows, BgeReranker.REACH / 8),
            RunShape(rows, BgeReranker.REACH / 2),
            RunShape(rows / 4, BgeReranker.REACH),
        )

    @Test
    fun `the memory told of a run bounds what was measured of this runtime and model`() =
        RunMemoryRecord.checkTold(GRAPH, BgeReranker.BATCHING, shapes, model)

    // Minutes, and a card: run by its name when the runtime or the model changes
    @Tag("ci")
    @Test
    fun `the memory of a run is measured on the card and kept`() {
        RunMemoryMeasure.record(GRAPH, model, shapes + RunShape(rows, BgeReranker.BATCHING.page))
        RunMemoryRecord.checkTold(GRAPH, BgeReranker.BATCHING, shapes, model)
    }

    private companion object {
        const val GRAPH = "bge-reranker-v2-m3"
    }
}
