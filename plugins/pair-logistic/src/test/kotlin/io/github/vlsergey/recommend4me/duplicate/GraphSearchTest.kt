package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * THE SEARCH BY A GRAPH keeps of every work pairs of works of the search, never the work itself nor one answered on —
 * and of nearly every work and model the likeliest pair every pair's search finds, or one as likely; the same of a
 * descent keeping the chances of the neighbours of a step as of one scoring them anew.
 */
class GraphSearchTest {

    @Test
    fun `the kernels of the graph fill a multiprocessor of the card`() {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val card = Cublas.open(dir!!)
        assumeTrue(card != null, "no cuBLAS to run on")
        val kernels = assertNotNull(GraphSearch.kernels(card!!))
        GraphSearch.BOUNDED.forEach { name ->
            val fit = kernels.fit(name)
            assertEquals(kernels.blocks, fit.blocks, "$name: $fit of ${kernels.blocks}")
        }
    }

    @Test
    fun `a descent keeping the chances of the neighbours finds what one scoring them anew does`() {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val card = Cublas.open(dir!!)
        assumeTrue(card != null, "no cuBLAS to run on")
        val search = RandomSearches.search(Random(7))
        val alike = PairSearch(search.sites.take(2), search.families, search.familyOf, search.sizes, search.answered)
        val laid = RandomSearches.models().map { it.laid(alike.families) }
        fun found(keeps: Boolean) =
            assertNotNull(GraphSearch.search(alike, laid, card!!, keeps)).found().map {
                listOf(it.site, it.item, it.otherSite, it.other, it.kinds)
            }
        val anew = found(keeps = false)
        assertTrue(anew.size >= alike.sites.sumOf { it.count }, "a work of no pair")
        assertEquals(anew, found(keeps = true))
    }

    @Test
    fun `of nearly every work the graph keeps the likeliest pair, never itself nor one answered on`() {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val card = Cublas.open(dir!!)
        assumeTrue(card != null, "no cuBLAS to run on")
        val search = RandomSearches.search(Random(7))
        // Of one width alike, the three sites' trigrams: the third of another width left out
        val alike = PairSearch(search.sites.take(2), search.families, search.familyOf, search.sizes, search.answered)
        val models = RandomSearches.models()
        val laid = models.map { it.laid(alike.families) }
        val graph = assertNotNull(GraphSearch.search(alike, laid, card!!)).found()
        val every = ProcessorSearch.search(alike, laid, Compared(alike) {})
        fun chance(p: FoundPair, k: Int) = models[k].chance(alike.layers(p.site, p.item, p.otherSite, p.other))
        graph.forEach { p ->
            assertTrue(p.site != p.otherSite || p.item != p.other, "a work with itself")
            val passed = alike.answered[p.site to p.otherSite]?.get(p.item) ?: IntArray(0)
            assertTrue(p.other !in passed, "a pair answered on kept")
        }
        var found = 0
        var asked = 0
        models.indices.forEach { k ->
            val byWork = graph.filter { it.kinds and (1 shl k) != 0 }.associateBy { it.site to it.item }
            every
                .filter { it.kinds and (1 shl k) != 0 }
                .forEach { best ->
                    asked++
                    val kept = byWork[best.site to best.item]
                    if (kept != null && chance(kept, k) >= chance(best, k) - CLOSE) found++
                }
        }
        val recall = found.toDouble() / asked
        println("the graph found the likeliest of $found of $asked")
        assertTrue(recall >= LEAST_RECALL, "the graph found the likeliest of $found of $asked")
    }

    private companion object {
        /** The chances of the card: of products in half precision. */
        const val CLOSE = 5e-3

        /** The share of the likeliest pairs the graph finds at least. */
        const val LEAST_RECALL = 0.5
    }
}
