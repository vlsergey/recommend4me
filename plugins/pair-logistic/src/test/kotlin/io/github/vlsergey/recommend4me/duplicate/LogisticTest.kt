package io.github.vlsergey.recommend4me.duplicate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the logistic model of pairs learns of the user's answers, and how a search scores a pair by it. */
class LogisticTest {

    private fun pair(
        title: Float,
        description: Float?,
        size: Float? = null,
        facets: Map<String, Float> = emptyMap(),
        sameAuthor: Boolean = false,
    ) = PairLayers(title, description, size, facets, sameAuthor)

    private fun FittedPairModel.chance(title: Float, description: Float?, facets: Map<String, Float> = emptyMap()) =
        chance(pair(title, description, facets = facets))

    /** The model of [yes] said of the kind, [no] said not, [rest] not answered. */
    private fun fitted(yes: List<PairLayers>, rest: List<PairLayers>, no: List<PairLayers> = emptyList()): Logistic =
        Logistic.fit(yes + no + rest, yes.map { true } + no.map { false } + rest.map { null })!!

    @Test
    fun `one author by the dictionary lifts a pair`() {
        val one = (0 until 30).map { pair(0.6f, null, sameAuthor = true) }
        val two = (0 until 30).map { pair(0.6f, null) }
        val model = Logistic.fit(one + two, one.map { true } + two.map { null })!!
        assertTrue(model.chance(pair(0.6f, null, sameAuthor = true)) > model.chance(pair(0.6f, null)))
    }

    @Test
    fun `without an answer that a pair is of the kind the mean of the layers stands for the chance`() {
        val pairs = listOf(pair(0.9f, 0.5f), pair(0.4f, null, facets = mapOf("tags" to 0.1f, "status" to 0.3f)))
        assertNull(Logistic.fit(pairs, listOf(null, false)))
        val prior = LogisticPairModel().prior()
        assertEquals(0.7, prior.chance(pairs[0]), 1e-6)
        // The facets' mean one layer of them
        assertEquals(0.3, prior.chance(pairs[1]), 1e-6)
    }

    @Test
    fun `the answers teach which layer tells a pair is of the kind`() {
        // The pairs said of the kind have alike descriptions, whatever their titles; the many others not
        val yes = (0 until 20).map { pair(0.3f + (it % 5) * 0.1f, 0.95f) }
        val rest = (100 until 2100).map { pair(0.3f + (it % 5) * 0.1f, 0.2f + (it % 7) * 0.05f) }
        val model = fitted(yes, rest)
        assertTrue(model.chance(0.4f, 0.95f) > model.chance(0.9f, 0.3f))
        val w = model.weights!!
        assertTrue(
            w.getValue(Logistic.DESCRIPTION) > w.getValue(Logistic.TITLE),
            "the description weighs more than the title: $w",
        )
    }

    @Test
    fun `every facet is weighed apart by the answers`() {
        // The pairs said of the kind share their characters; the status every pair shares alike
        val yes = (0 until 20).map { pair(0.5f, null, facets = mapOf("characters" to 0.9f, "status" to 1f)) }
        val rest =
            (100 until 2100).map { pair(0.5f, null, facets = mapOf("characters" to (it % 10) * 0.03f, "status" to 1f)) }
        val model = fitted(yes, rest)
        val w = model.weights!!
        assertTrue(
            w.getValue(Logistic.facet("characters")) > w.getValue(Logistic.facet("status")),
            "the characters tell more than the status: $w",
        )
    }

    @Test
    fun `a pair said not of the kind lowers the chance of its like`() {
        val yes = (0 until 10).map { pair(0.9f, 0.9f) }
        val no = (10 until 20).map { pair(0.9f, 0.3f) }
        val rest = (100 until 600).map { pair(0.5f, 0.5f) }
        val model = fitted(yes, rest, no)
        assertTrue(model.chance(0.9f, 0.9f) > model.chance(0.5f, 0.5f))
        assertTrue(model.chance(0.9f, 0.3f) < model.chance(0.9f, 0.9f))
    }

    @Test
    fun `the score of the search orders the pairs as the chance does, and the state packed reads the same`() {
        val yes = (0 until 20).map { pair(0.5f, 0.9f, facets = mapOf("characters" to 0.8f)) }
        val rest = (100 until 600).map { pair(0.5f, 0.3f, facets = mapOf("characters" to 0.1f)) }
        val model = fitted(yes, rest)
        val laid = model.laid(listOf("characters", "status"))
        val valued = arrayOf(floatArrayOf(0.8f, 0.1f), floatArrayOf(0f, 0f))
        val near = laid.score(0.5f, 0.9f, true, 0f, false, 1L, { f -> valued[f][0] }, false)
        val far = laid.score(0.5f, 0.3f, true, 0f, false, 1L, { f -> valued[f][1] }, false)
        assertTrue(near > far)
        assertEquals(model.chance(0.5f, 0.9f, mapOf("characters" to 0.8f)), Logistic.sigmoid(near), 1e-9)
        val read = LogisticPairModel().unpack(model.pack())!!
        assertEquals(
            model.chance(0.5f, 0.9f, mapOf("characters" to 0.8f)),
            read.chance(pair(0.5f, 0.9f, facets = mapOf("characters" to 0.8f))),
            1e-12,
        )
    }
}
