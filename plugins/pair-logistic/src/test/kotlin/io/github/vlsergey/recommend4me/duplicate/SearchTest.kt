package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import java.util.Random
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The search keeps of every work, of every other site, the work likeliest by every model: the chance of the pair it
 * keeps is the highest of the chances of every pair of that work, the answered ones passed over — in the main memory
 * and on the card alike.
 */
class SearchTest {
    private val families = listOf("characters", "status", "tags")
    private val valueCount = 60
    private val familyOf = IntArray(valueCount) { it % families.size }

    private fun site(random: Random, source: String, count: Int, width: Int): SiteWorks {
        fun unit() =
            FloatArray(width) { random.nextGaussian().toFloat() }
                .let { v ->
                    val l = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
                    FloatArray(width) { v[it] / l }
                }
        val titles = Matrix(count, width).also { m -> for (i in 0 until count) m.held.put(unit(), 0, m.row(i), width) }
        val described = BooleanArray(count) { random.nextInt(4) != 0 }
        val descriptions =
            Matrix(count, width).also { m ->
                for (i in 0 until count) if (described[i]) m.held.put(unit(), 0, m.row(i), width)
            }
        val values =
            Array(count) { IntArray(random.nextInt(6)) { random.nextInt(valueCount) }.distinct().sorted().toIntArray() }
        val weights = Array(count) { i -> FloatArray(values[i].size) { 0.2f + random.nextFloat() } }
        val facets = LongArray(count) { i -> values[i].fold(0L) { bits, v -> bits or (1L shl familyOf[v]) } }
        val sizes =
            Array(2) { DoubleArray(count) { if (random.nextInt(3) == 0) 0.0 else 1000.0 + random.nextInt(100_000) } }
        val authors = Array(count) { LongArray(random.nextInt(2)) { random.nextInt(40).toLong() } }
        // An author one with the next, in part
        val kin = Array(count) { i -> authors[i].flatMap { listOf(it, it + 1) }.distinct().toLongArray() }
        return SiteWorks(
            source,
            List(count) { "%05d".format(it) },
            titles,
            descriptions,
            described,
            values,
            weights,
            facets,
            sizes,
            authors,
            kin,
        )
    }

    private fun search(random: Random): PairSearch {
        val sites = listOf(site(random, "a", 300, 24), site(random, "b", 500, 24), site(random, "c", 40, 16))
        // A few pairs answered on, passed over both ways
        val answered = HashMap<Pair<Int, Int>, HashMap<Int, IntArray>>()
        repeat(30) {
            val i = random.nextInt(300)
            val j = random.nextInt(500)
            answered.getOrPut(0 to 1) { HashMap() }.merge(i, intArrayOf(j)) { x, y -> x + y }
            answered.getOrPut(1 to 0) { HashMap() }.merge(j, intArrayOf(i)) { x, y -> x + y }
        }
        return PairSearch(sites, families, familyOf, listOf("words", "chars"), answered)
    }

    /** A learnt model of weights of its own, and the mean of the layers. */
    private fun models(): List<Logistic> =
        listOf(
            Logistic(
                mapOf(
                    Logistic.CONSTANT to -3.0,
                    Logistic.TITLE to 2.5,
                    Logistic.DESCRIPTION to 1.5,
                    Logistic.has(Logistic.DESCRIPTION) to 0.2,
                    Logistic.SIZE to 0.7,
                    Logistic.has(Logistic.SIZE) to -0.1,
                    Logistic.SAME_AUTHOR to 1.2,
                    Logistic.facet("characters") to 2.0,
                    Logistic.has(Logistic.facet("characters")) to 0.3,
                    Logistic.facet("tags") to 0.8,
                )
            ),
            Logistic(null),
        )

    /**
     * Every pair kept is the likeliest of its work and site by every model it is kept for, every other one by them too.
     */
    private fun assertLikeliest(search: PairSearch, models: List<Logistic>, found: List<FoundPair>) {
        val byWork = found.groupBy { Triple(it.site, it.item, it.otherSite) }
        for (a in search.sites.indices) for (b in search.sites.indices) {
            if (!search.compared(a, b)) continue
            val passed = search.answered[a to b].orEmpty()
            for (i in 0 until search.sites[a].count) {
                val kept = byWork[Triple(a, i, b)].orEmpty()
                models.forEachIndexed { k, model ->
                    val chosen = kept.filter { it.kinds and (1 shl k) != 0 }
                    assertEquals(1, chosen.size, "site $a work $i of site $b, model $k: ${chosen.size} kept")
                    val chances =
                        (0 until search.sites[b].count)
                            .filter { it !in (passed[i] ?: IntArray(0)) }
                            .map { j -> model.chance(search.layers(a, i, b, j)) }
                    val got = model.chance(search.layers(a, i, b, chosen.single().other))
                    assertTrue(chosen.single().other !in (passed[i] ?: IntArray(0)), "an answered pair kept")
                    assertTrue(
                        got >= chances.max() - 1e-5,
                        "site $a work $i, model $k: $got, the likeliest ${chances.max()}",
                    )
                }
            }
        }
    }

    @Test
    fun `in the main memory every work's pair is the likeliest by every model, the answered passed over`() {
        val search = search(Random(3))
        val models = models()
        assertLikeliest(search, models, LogisticPairModel().search(search, models, null))
    }

    @Test
    fun `on the card every work's pair is the likeliest by every model, as in the main memory`() {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val card = Cublas.open(dir!!)
        assumeTrue(card != null, "no cuBLAS to run on")
        val search = search(Random(5))
        val models = models()
        assertLikeliest(search, models, CardSearch.search(search, models.map { it.laid(search.families) }, card!!))
    }
}
