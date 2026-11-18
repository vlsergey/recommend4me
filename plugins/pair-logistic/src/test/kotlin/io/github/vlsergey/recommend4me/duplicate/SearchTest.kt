package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The search keeps of every work the work of any site — its own too, never itself — likeliest by every model: the
 * chance of the pair it keeps is the highest of the chances of every pair of that work, the answered ones passed over —
 * in the main memory and on the card alike.
 */
class SearchTest {
    /**
     * Of every work, by every model, one pair kept — the likeliest of every work of every site compared, its own site
     * too, itself never, the answered passed over —, and as many pairs as there are models at most.
     */
    private fun assertLikeliest(
        search: PairSearch,
        models: List<Logistic>,
        found: List<FoundPair>,
        close: Double = EXACT,
    ) {
        val byWork = found.groupBy { it.site to it.item }
        fun passed(a: Int, i: Int, b: Int) = search.answered[a to b]?.get(i) ?: IntArray(0)
        for (a in search.sites.indices) for (i in 0 until search.sites[a].count) {
            val kept = byWork[a to i].orEmpty()
            assertTrue(kept.size <= models.size, "site $a work $i: ${kept.size} pairs kept")
            models.forEachIndexed { k, model ->
                val chosen = kept.filter { it.kinds and (1 shl k) != 0 }
                assertEquals(1, chosen.size, "site $a work $i, model $k: ${chosen.size} kept")
                val c = chosen.single()
                assertTrue(c.otherSite != a || c.other != i, "site $a work $i kept with itself")
                assertTrue(c.other !in passed(a, i, c.otherSite), "an answered pair kept")
                val chances =
                    search.sites.indices
                        .filter { search.compared(a, it) }
                        .flatMap { b ->
                            (0 until search.sites[b].count)
                                .filter { j -> (b != a || j != i) && j !in passed(a, i, b) }
                                .map { j -> model.chance(search.layers(a, i, b, j)) }
                        }
                val got = model.chance(search.layers(a, i, c.otherSite, c.other))
                assertTrue(
                    got >= chances.max() - close,
                    "site $a work $i, model $k: $got, the likeliest ${chances.max()}",
                )
            }
        }
    }

    @Test
    fun `in the main memory every work's pair is the likeliest by every model, the answered passed over`() {
        val search = RandomSearches.search(Random(3))
        val models = RandomSearches.models()
        assertLikeliest(search, models, LogisticPairModel().search(search, models, null))
    }

    @Test
    fun `on the card every work's pair is the likeliest by every model, as in the main memory`() {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val card = Cublas.open(dir!!)
        assumeTrue(card != null, "no cuBLAS to run on")
        val search = RandomSearches.search(Random(5))
        val models = RandomSearches.models()
        assertLikeliest(
            search,
            models,
            CardSearch.search(search, models.map { it.laid(search.families) }, card!!),
            HALVES,
        )
    }

    @Test
    fun `on the card in runs of a few works every work's pair is the likeliest, of every run against every run`() {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val card = Cublas.open(dir!!)
        assumeTrue(card != null, "no cuBLAS to run on")
        val search = RandomSearches.search(Random(5))
        val models = RandomSearches.models()
        // Runs of 64 works: of the sites of 300, 500 and 40 works, a run against itself, against a run of its own site,
        // against another site's
        assertLikeliest(
            search,
            models,
            CardSearch.search(search, models.map { it.laid(search.families) }, card!!, 64),
            HALVES,
        )
    }

    private companion object {
        /** The chances of the main memory: of single precision's last digits. */
        const val EXACT = 1e-5

        /**
         * The chances of the card: of products summed in half precision, the cosines within its last digits (user,
         * 2026-11-18: "all in fp16").
         */
        const val HALVES = 5e-3
    }
}
