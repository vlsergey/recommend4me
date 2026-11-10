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
        assertEquals(model.chance(0.5f, 0.9f, mapOf("characters" to 0.8f)), model.calibration.chance(near), 1e-9)
        val read = LogisticPairModel().unpack(model.pack())!!
        assertEquals(
            model.chance(0.5f, 0.9f, mapOf("characters" to 0.8f)),
            read.chance(pair(0.5f, 0.9f, facets = mapOf("characters" to 0.8f))),
            1e-12,
        )
        // A model kept before its calibration was: the chance its own sigmoid of the score
        val old = Logistic.read("constant=-1.0\ntitle=2.0".toByteArray())!!
        assertEquals(Logistic.sigmoid(0.0), old.chance(pair(0.5f, null)), 1e-12)
    }

    @Test
    fun `the chance is what the answers say of pairs scored so, though most pairs are not answered`() {
        // Of the pairs with alike descriptions some are answered, every other one of them said one work; the rest
        // not answered, unlike, taken as not of the kind though the like among them are as often one work
        val like = (0 until 400).map { pair(0.5f, 0.9f) }
        val unlike = (0 until 20_000).map { pair(0.5f, 0.1f + (it % 5) * 0.05f) }
        val said = like.indices.map { if (it < 200) it % 2 == 0 else null } + unlike.map { null }
        val model = Logistic.fit(like + unlike, said)!!
        // The model's own sigmoid of the score: the 200 like ones unanswered taken as not, it is below a half
        val laid = model.laid(emptyList())
        val z = laid.score(0.5f, 0.9f, true, 0f, false, 0L, { 0f }, false)
        assertTrue(Logistic.sigmoid(z) < 0.5, "the model's own sigmoid ${Logistic.sigmoid(z)}")
        // The answers say a half
        assertEquals(0.5, model.chance(0.5f, 0.9f), 0.1)
        assertTrue(model.chance(0.5f, 0.9f) > model.chance(0.5f, 0.1f))
    }

    @Test
    fun `the calibration of scores by the answers`() {
        // Of the score 0 one in ten said yes, of the score 2 nine in ten: answers as many as the user's
        val z = DoubleArray(2000) { if (it < 1000) 0.0 else 2.0 }
        val yes = BooleanArray(2000) { if (it < 1000) it % 10 == 0 else it % 10 != 0 }
        val c = Calibration.fit(z, yes)
        assertEquals(0.1, c.chance(0.0), 0.02)
        assertEquals(0.9, c.chance(2.0), 0.02)
    }
}
