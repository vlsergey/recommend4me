package io.github.vlsergey.recommend4me.universe

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The likeness of the fandoms' names learnt of the universes: the names of one universe alike, of two different not.
 */
class FandomLikenessTest {

    private fun unit(vararg x: Double): FloatArray {
        val n = sqrt(x.sumOf { it * it })
        return FloatArray(x.size) { (x[it] / n).toFloat() }
    }

    @Test
    fun `a dimension that tells nothing of the universe is weighed down, the names of one universe the nearer`() {
        // Every universe a direction of the first two dimensions; its two names apart in the third, which the encoder
        // weighs most: a name of another universe written alike in it is nearer than its own other name
        val names =
            (0 until 8).associate { u ->
                val t = u * 0.4
                u.toLong() to listOf(unit(cos(t), sin(t), 2.0), unit(cos(t), sin(t), -2.0))
            }
        val different = mapOf(0L to setOf(1L), 1L to setOf(0L))
        val pairs = FandomLikeness.pairs(names, emptyMap(), different, names)
        val asEncoded = FandomLikeness.ordered(pairs, null)
        val learnt = FandomLikeness.fit(pairs)!!
        val ordered = FandomLikeness.ordered(pairs, learnt)
        assertTrue(ordered > asEncoded && ordered > 0.9, "learnt $ordered, as encoded $asEncoded")
        assertTrue(learnt.weights[2] < learnt.weights[0] / 2, "weights ${learnt.weights.toList()}")
        // Kept as it was learnt
        assertContentEquals(learnt.weights, FandomLikeness.unpack(learnt.pack()).weights)
        // A name made ready is of unit length
        val ready = learnt.of(names.getValue(3L)[0])
        assertTrue(kotlin.math.abs(ready.sumOf { (it * it).toDouble() } - 1) < 1e-5)
    }
}
