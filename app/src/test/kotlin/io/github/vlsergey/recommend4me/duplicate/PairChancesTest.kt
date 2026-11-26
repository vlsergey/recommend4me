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
        DuplicateCandidate(ItemKey("a", n.toLong()), ItemKey("b", n.toLong()), title, null, null, emptyMap())

    /**
     * The [pairs] as a search finds them, with the words [said] on them: of one work by the title, of one series by
     * [series].
     */
    private fun found(
        pairs: List<DuplicateCandidate>,
        said: Said,
        series: (DuplicateCandidate) -> Double? = { null },
    ) = pairs.map { FoundCandidate(it, byTitle.chance(it.layers), series(it), 0, 0, 1, 0, said[it.pair]) }

    @Test
    fun `the pairs in most doubt stand between what is said one work and what not, of a half of one kind of answer`() {
        val same = (0 until 10).map { pair(it, 0.9f) }
        val different = (10 until 20).map { pair(it, 0.2f) }
        val said =
            Said(
                (same.map { it.pair to DuplicateAnswer.SAME } + different.map { it.pair to DuplicateAnswer.DIFFERENT })
                    .toMap(),
                emptySet(),
            )
        val doubt = PairDoubt.of(found(same + different, said))
        val between = doubt.ofSame(0.5)
        assertTrue(between > doubt.ofSame(0.9) && between > doubt.ofSame(0.2), "$between")
        val oneKind = PairDoubt.of(found(same, said))
        assertEquals(1.0, oneKind.ofSame(0.5), 1e-9)
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
                    progress: (Double) -> Unit,
                ) = emptyList<FoundPair>()
            }
        val fitted = PairModels.fit(model, pairs, said)
        // Of the pairs answered alone. Of one work: one work, different, one series — two works —; the translation and
        // the part left out
        assertEquals(listOf(true, false, false), asked[0])
        // Of one series: every answer but the series a pair not of one — one work left out, not said not to be one
        assertEquals(listOf(false, true, false, false), asked[1])
        assertTrue(fitted.same != null && fitted.series != null)
    }

    @Test
    fun `no chance of a series before any pair is said to be of one`() {
        val chances = PairChances(byTitle, null, false)
        assertEquals(null, chances.series(pair(1, 0.5f)))
        assertEquals(0.5, chances.same(pair(1, 0.5f)), 1e-9)
    }

    @Test
    fun `the pairs in most doubt of one series stand on the chance of one series, of a half without its model`() {
        // The chance of one work by the title, of one series by its complement: the two scales apart
        val series = (0 until 10).map { pair(it, 0.1f) }
        val other = (10 until 20).map { pair(it, 0.7f) }
        val said =
            Said(
                (series.map { it.pair to DuplicateAnswer.SERIES } + other.map { it.pair to DuplicateAnswer.DIFFERENT })
                    .toMap(),
                emptySet(),
            )
        val doubt = PairDoubt.of(found(series + other, said) { 1 - it.title.toDouble() })
        val between = doubt.ofSeries(0.6)
        assertTrue(between > doubt.ofSeries(0.9) && between > doubt.ofSeries(0.3), "$between")
        val none = PairDoubt.of(found(series + other, said))
        assertEquals(1.0, none.ofSeries(0.5), 1e-9)
    }
}
