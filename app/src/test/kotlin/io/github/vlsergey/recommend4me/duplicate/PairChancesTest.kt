package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.item.ItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the user's answers on the pairs change: what the pairs in doubt are told by. */
class PairChancesTest {
    /** A model whose chance of a pair is its title's cosine. */
    private val byTitle =
        object : FittedPairModel {
            override fun chance(layers: PairLayers) = layers.title.toDouble()

            override fun pack() = ByteArray(0)
        }

    private fun pair(n: Int, title: Float) =
        DuplicateCandidate(ItemKey("a", "$n"), ItemKey("b", "$n"), title, null, null, emptyMap())

    @Test
    fun `the boundary of the answers stands between what is said one work and what not, none of one kind of answer`() {
        val same = (0 until 10).map { pair(it, 0.9f) }
        val different = (10 until 20).map { pair(it, 0.2f) }
        val chances = PairChances(byTitle, null, true)
        val said = (same.map { it.pair to true } + different.map { it.pair to false }).toMap()
        val boundary = chances.boundary(same + different, chances::logit) { said[it.pair] }
        assertTrue(boundary > chances.logit(pair(100, 0.2f)) && boundary < chances.logit(pair(100, 0.9f)), "$boundary")
        assertEquals(0.0, chances.boundary(same, chances::logit) { said[it.pair] })
    }

    @Test
    fun `each model learns of the answers by its own kind`() {
        val words =
            listOf(
                DuplicateAnswer.SAME,
                DuplicateAnswer.DIFFERENT,
                DuplicateAnswer.SERIES,
                DuplicateAnswer.TRANSLATION,
                DuplicateAnswer.PARTIAL,
                null,
            )
        val pairs = words.indices.map { pair(it, it / 10f) }
        val said = pairs.zip(words).mapNotNull { (p, w) -> w?.let { p.pair to it } }.toMap()
        val asked = ArrayList<List<Boolean?>>()
        val model =
            object : PairModel {
                override val id = "recording"

                override fun prior() = byTitle

                override fun fit(pairs: List<PairLayers>, said: List<Boolean?>): FittedPairModel? {
                    asked += said
                    return if (said.contains(true)) byTitle else null
                }

                override fun unpack(bytes: ByteArray) = null

                override fun search(
                    search: PairSearch,
                    models: List<FittedPairModel>,
                    card: io.github.vlsergey.recommend4me.matrix.Cublas?,
                ) = emptyList<FoundPair>()
            }
        val fitted = PairModels.fit(model, pairs, said)
        // Of one work: one work, different, one series — two works —, not answered; the translation and the part left
        // out
        assertEquals(listOf(true, false, false, null), asked[0])
        // Of one series: every answer but the series a pair not of one
        assertEquals(listOf(false, false, true, false, false, null), asked[1])
        assertTrue(fitted.same != null && fitted.series != null)
    }

    @Test
    fun `no chance of a series before any pair is said to be of one`() {
        val chances = PairChances(byTitle, null, false)
        assertEquals(null, chances.series(pair(1, 0.5f)))
        assertEquals(0.5, chances.same(pair(1, 0.5f)), 1e-9)
    }

    @Test
    fun `the boundary of one series stands on the chance of one series, none without its model`() {
        // The chance of one work by the title, of one series by its complement: the two scales apart
        val bySeries =
            object : FittedPairModel {
                override fun chance(layers: PairLayers) = 1 - layers.title.toDouble()

                override fun pack() = ByteArray(0)
            }
        val series = (0 until 10).map { pair(it, 0.1f) }
        val other = (10 until 20).map { pair(it, 0.7f) }
        val said = (series.map { it.pair to true } + other.map { it.pair to false }).toMap()
        val chances = PairChances(byTitle, bySeries, true)
        val boundary = chances.boundary(series + other, chances::seriesLogit) { said[it.pair] }
        assertTrue(
            boundary < chances.seriesLogit(pair(100, 0.1f))!! && boundary > chances.seriesLogit(pair(100, 0.7f))!!,
            "$boundary",
        )
        val none = PairChances(byTitle, null, true)
        assertEquals(0.0, none.boundary(series + other, none::seriesLogit) { said[it.pair] })
    }

    @Test
    fun `a page holds the pairs of the answers chosen, those not answered on as one of them`() {
        val different = PairChoice(false, setOf(DuplicateAnswer.DIFFERENT))
        assertTrue(!different.holds(null) && different.holds(DuplicateAnswer.DIFFERENT))
        assertTrue(!different.holds(DuplicateAnswer.SAME) && !different.holds(DuplicateAnswer.SERIES))
        val open = PairChoice(true)
        assertTrue(open.holds(null) && !open.holds(DuplicateAnswer.DIFFERENT))
    }
}
