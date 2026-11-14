package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The likeness learnt on the card as on the processor, within the last digits; skipped without the card's libraries.
 */
class FandomLikenessOnCardTest {

    private fun card(): FandomLikeness.OnCard {
        val dir = Path.of(System.getenv("LOCALAPPDATA") ?: "", "recommend4me", "cuda")
        assumeTrue(Files.exists(dir.resolve("nvrtc64_120_0.dll")), "no CUDA libraries with NVRTC")
        listOf("cudart64_12.dll", "cublasLt64_12.dll").forEach {
            System.load(dir.resolve(it).toAbsolutePath().toString())
        }
        val cublas = Cublas.open(dir)
        assumeTrue(cublas != null, "no card")
        return FandomLikeness.OnCard(cublas!!, FandomLikenessOnCard.kernels(cublas)!!)
    }

    @Test
    fun `the card learns the weights the processor learns and orders the pairs alike`() {
        val card = card()
        val r = Random(3)
        val dim = 64
        val universes = 40
        val rows = HalfMatrix(universes * 3, dim)
        // Every universe a direction of its own, its three names the direction and noise
        val names =
            (0 until universes).associate { u ->
                val direction = FloatArray(dim) { r.nextFloat() - 0.5f }
                u.toLong() to
                    IntArray(3) { k ->
                        val row = 3 * u + k
                        rows.put(row, FloatArray(dim) { direction[it] + 0.8f * (r.nextFloat() - 0.5f) })
                        row
                    }
            }
        val different = mapOf(0L to setOf(1L, 2L), 1L to setOf(0L), 2L to setOf(0L))
        val related = mapOf(3L to setOf(4L), 4L to setOf(3L))
        val pairs = NamePairs.of(names, related, different, names)
        val onProcessor = FandomLikeness.fit(pairs, rows, null)!!.weights
        val onCard = FandomLikeness.fit(pairs, rows, card)!!.weights
        onProcessor.indices.forEach {
            assertEquals(onProcessor[it], onCard[it], 2e-3f * (1 + abs(onProcessor[it])), "weight $it")
        }
        val learnt = FandomLikeness(onCard)
        val byProcessor = FandomLikeness.ordered(pairs, rows, learnt, null)
        val byCard = FandomLikeness.ordered(pairs, rows, learnt, card)
        assertEquals(byProcessor, byCard, 1e-3)
        assertTrue(byCard > FandomLikeness.ordered(pairs, rows, null, card) - 1e-9)
    }
}
