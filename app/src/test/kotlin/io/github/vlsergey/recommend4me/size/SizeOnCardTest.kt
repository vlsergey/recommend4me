package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.matrix.Cublas
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** The ridge of the words to a character solved on the card as on the processor; skipped without the card. */
class SizeOnCardTest {

    private fun cublas(): Cublas {
        // Those of the checkout's data folder (the build's recommend4me.local-data-dir)
        val dir = Path.of(System.getProperty("recommend4me.local-data-dir") ?: "data", "cuda")
        assumeTrue(Files.exists(dir.resolve("nvrtc64_120_0.dll")), "no CUDA libraries with NVRTC")
        listOf("cudart64_12.dll", "cublasLt64_12.dll").forEach {
            System.load(dir.resolve(it).toAbsolutePath().toString())
        }
        val cublas = Cublas.open(dir)
        assumeTrue(cublas != null, "no card")
        return cublas!!
    }

    @Test
    fun `the card solves the ridge the processor solves`() {
        val cublas = cublas()
        val kernels = SizeOnCard.kernels(cublas)!!
        val random = Random(5)
        val features = SizeFeatures()
        // Of 300 authors, 5 sites and 40 tags: each example a site, an author and two tags
        val examples =
            List(EXAMPLES) {
                val chars = Math.pow(10.0, 3 + 2 * random.nextDouble())
                SizeExample(
                    intArrayOf(
                        features.id("source:${random.nextInt(5)}"),
                        features.id("author:${random.nextInt(300)}"),
                        features.id("tag:${random.nextInt(40)}"),
                        features.id("tag:x${random.nextInt(40)}"),
                    ),
                    chars * 0.15 * Math.exp(0.05 * random.nextGaussian()),
                    chars,
                )
            }
        val rows = SizeRows(examples, features.names.size)
        val weights = DoubleArray(rows.count) { 0.5 + random.nextDouble() }
        val lambda = DoubleArray(rows.features) { if (it == 0) 0.0 else 3.0 }
        val processor = SizeOnProcessor(rows).solve(weights, lambda, null, TOLERANCE, MOST)
        val card =
            cublas.withMemory(SizeOnCard.bytes(rows), SizeOnCard.bytes(rows)) { memory ->
                SizeOnCard(rows, kernels, memory).solve(weights, lambda, null, TOLERANCE, MOST)
            }
        assertTrue(card.steps in 1 until MOST, "${card.steps} steps")
        processor.x.indices.forEach { assertEquals(processor.x[it], card.x[it], 1e-6 * (1 + abs(processor.x[it]))) }
    }

    private companion object {
        const val EXAMPLES = 20_000
        const val TOLERANCE = 1e-8
        const val MOST = 5000
    }
}
