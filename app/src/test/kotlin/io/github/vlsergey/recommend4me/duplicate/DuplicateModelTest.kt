package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.item.ItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuplicateModelTest {

    private fun pair(n: Int, title: Float, description: Float?, size: Float? = null, facets: Map<String, Float> = emptyMap()) =
        DuplicateCandidate(ItemKey("a", "$n"), ItemKey("b", "$n"), title, description, size, facets)

    private fun DuplicateModel.chance(title: Float, description: Float?, facets: Map<String, Float> = emptyMap()) = chance(title, description, null, facets)

    @Test
    fun `one author by the dictionary lifts a pair, and the boundary of the answers stands between what is said one work and what different`() {
        fun one(n: Int) = DuplicateCandidate(ItemKey("a", "$n"), ItemKey("b", "$n"), 0.6f, null, null, emptyMap(), sameAuthor = true)
        fun two(n: Int) = pair(n, 0.6f, null)
        val pairs = (0 until 30).map(::one) + (30 until 60).map(::two)
        val model = DuplicateModel.fit(pairs) { c -> c.sameAuthor }!!
        assertTrue(model.chance(one(100)) > model.chance(two(100)))
        val boundary = model.boundary(pairs) { c -> c.sameAuthor }
        assertTrue(boundary > model.logit(two(100)) && boundary < model.logit(one(100)), "$boundary")
        assertEquals(0.0, DuplicateModel(null).boundary(pairs) { null })
    }

    @Test
    fun `without an answer that a pair is one work the mean of the layers stands for the chance`() {
        val pairs = listOf(pair(1, 0.9f, 0.5f), pair(2, 0.4f, null, facets = mapOf("tags" to 0.1f, "status" to 0.3f)))
        assertNull(DuplicateModel.fit(pairs) { null })
        val model = DuplicateModel(null)
        assertEquals(0.7, model.chance(pairs[0]), 1e-6)
        // The facets' mean one layer of them
        assertEquals(0.3, model.chance(pairs[1]), 1e-6)
    }

    @Test
    fun `the answers teach which layer tells a pair is one work`() {
        // The pairs said to be one work have alike descriptions, whatever their titles; the many others not
        val same = (0 until 20).map { pair(it, 0.3f + (it % 5) * 0.1f, 0.95f) }
        val others = (100 until 2100).map { pair(it, 0.3f + (it % 5) * 0.1f, 0.2f + (it % 7) * 0.05f) }
        val sameKeys = same.map { it.pair }.toSet()
        val model = DuplicateModel.fit(same + others) { if (it.pair in sameKeys) true else null }!!
        // A pair of alike descriptions and a plain title is likelier one work than one of a near title and unlike descriptions
        assertTrue(model.chance(0.4f, 0.95f) > model.chance(0.9f, 0.3f))
        val w = model.weights!!
        assertTrue(w.getValue(DuplicateModel.DESCRIPTION) > w.getValue(DuplicateModel.TITLE), "the description weighs more than the title: $w")
    }

    @Test
    fun `every facet is weighed apart by the answers`() {
        // The pairs said to be one work share their characters; the status every pair shares alike
        val same = (0 until 20).map { pair(it, 0.5f, null, facets = mapOf("characters" to 0.9f, "status" to 1f)) }
        val others = (100 until 2100).map { pair(it, 0.5f, null, facets = mapOf("characters" to (it % 10) * 0.03f, "status" to 1f)) }
        val sameKeys = same.map { it.pair }.toSet()
        val model = DuplicateModel.fit(same + others) { if (it.pair in sameKeys) true else null }!!
        val w = model.weights!!
        assertTrue(w.getValue(DuplicateModel.facet("characters")) > w.getValue(DuplicateModel.facet("status")), "the characters tell more than the status: $w")
        assertTrue(
            model.chance(0.5f, null, mapOf("characters" to 0.9f, "status" to 1f)) > model.chance(0.5f, null, mapOf("characters" to 0.1f, "status" to 1f)),
        )
    }

    @Test
    fun `a pair said to be different lowers the chance of its like`() {
        val same = (0 until 10).map { pair(it, 0.9f, 0.9f) }
        val different = (10 until 20).map { pair(it, 0.9f, 0.3f) }
        val rest = (100 until 600).map { pair(it, 0.5f, 0.5f) }
        val sameKeys = same.map { it.pair }.toSet()
        val differentKeys = different.map { it.pair }.toSet()
        val model = DuplicateModel.fit(same + different + rest) { when (it.pair) { in sameKeys -> true; in differentKeys -> false; else -> null } }!!
        assertTrue(model.chance(0.9f, 0.9f) > model.chance(0.5f, 0.5f))
        assertTrue(model.chance(0.9f, 0.3f) < model.chance(0.9f, 0.9f))
    }

    @Test
    fun `the scorer of the search orders the pairs as the chance does`() {
        val same = (0 until 20).map { pair(it, 0.5f, 0.9f, facets = mapOf("characters" to 0.8f)) }
        val others = (100 until 600).map { pair(it, 0.5f, 0.3f, facets = mapOf("characters" to 0.1f)) }
        val sameKeys = same.map { it.pair }.toSet()
        val model = DuplicateModel.fit(same + others) { if (it.pair in sameKeys) true else null }!!
        val scorer = model.Scorer(listOf("characters", "status"))
        val valued = arrayOf(floatArrayOf(0.8f, 0.1f), floatArrayOf(0f, 0f))
        val near = scorer.score(0.5f, 0.9f, true, 0f, false, 1L, valued, 0, false)
        val far = scorer.score(0.5f, 0.3f, true, 0f, false, 1L, valued, 1, false)
        assertTrue(near > far)
        assertEquals(
            model.chance(0.5f, 0.9f, mapOf("characters" to 0.8f)), DuplicateModel.sigmoid(near), 1e-9,
        )
    }
}
