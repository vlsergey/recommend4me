package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.item.ItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuplicateModelTest {

    private fun pair(n: Int, title: Float, description: Float?, facets: Float? = null, size: Float? = null) =
        DuplicateCandidate(ItemKey("a", "$n"), ItemKey("b", "$n"), title, description, facets, size)

    @Test
    fun `without an answer that a pair is one work the mean of the layers stands for the chance`() {
        val pairs = listOf(pair(1, 0.9f, 0.5f), pair(2, 0.4f, null, facets = 0.2f))
        assertNull(DuplicateModel.fit(pairs) { null })
        val model = DuplicateModel(null)
        assertEquals(0.7, model.chance(pairs[0]), 1e-6)
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
        assertTrue(model.chance(0.4f, 0.95f, null, null) > model.chance(0.9f, 0.3f, null, null))
        assertTrue(model.weights!![2] > model.weights!![1], "the description weighs more than the title: ${model.weights!!.toList()}")
    }

    @Test
    fun `a pair said to be different lowers the chance of its like`() {
        val same = (0 until 10).map { pair(it, 0.9f, 0.9f) }
        val different = (10 until 20).map { pair(it, 0.9f, 0.3f) }
        val rest = (100 until 600).map { pair(it, 0.5f, 0.5f) }
        val sameKeys = same.map { it.pair }.toSet()
        val differentKeys = different.map { it.pair }.toSet()
        val model = DuplicateModel.fit(same + different + rest) { when (it.pair) { in sameKeys -> true; in differentKeys -> false; else -> null } }!!
        assertTrue(model.chance(0.9f, 0.9f, null, null) > model.chance(0.5f, 0.5f, null, null))
        assertTrue(model.chance(0.9f, 0.3f, null, null) < model.chance(0.9f, 0.9f, null, null))
    }
}
