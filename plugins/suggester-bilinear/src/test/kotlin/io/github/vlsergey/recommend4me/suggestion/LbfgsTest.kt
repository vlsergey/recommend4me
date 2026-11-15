package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Floats
import kotlin.test.Test
import kotlin.test.assertTrue

/** The search for a minimum where the function has no value a float holds. */
class LbfgsTest {

    /**
     * (x − 3)² of every number, its value NaN past 1: the minimum lies where the function is not, and every full step
     * reaches past it — the languages' model of fanfics.me went on so for 7501 values of its loss, 45 s of the card.
     */
    private fun past(x: FloatArray, g: FloatArray): Float {
        var f = 0f
        for (k in x.indices) {
            if (x[k] > 1f) {
                g.fill(Float.NaN)
                return Float.NaN
            }
            f += (x[k] - 3f) * (x[k] - 3f)
            g[k] = 2 * (x[k] - 3f)
        }
        return f
    }

    @Test
    fun `a value no float holds is no fall, and of the shortest step ends the search where it is`() {
        var evaluations = 0
        val x =
            Lbfgs.minimize(FloatArray(4), Bilinear.MAX_STEPS) { point, g ->
                evaluations++
                past(point, g)
            }
        assertTrue(x.all { it.isFinite() && it <= 1f }, "the point ${x.toList()}")
        assertTrue(evaluations < STEPS_OF_ONE_SEARCH, "$evaluations values of the function")
    }

    @Test
    fun `a search a point at a time ends where it is at a value no float holds`() {
        val size = 4
        val search = Search(OnHost, size, Bilinear.MAX_STEPS)
        search.begin(FloatArray(size))
        val point = Pieces(arrayOf(Floats(size)), intArrayOf(0), intArrayOf(size))
        val grad = Pieces(arrayOf(Floats(size)), intArrayOf(0), intArrayOf(size))
        val x = FloatArray(size)
        val g = FloatArray(size)
        while (!search.done) {
            search.propose(point)
            point.floats[0].take(x, 0, 0, size)
            val loss = past(x, g)
            grad.floats[0].put(g, 0, 0, size)
            search.answer(loss, point, grad)
        }
        val result = search.result()
        assertTrue(result.all { it.isFinite() && it <= 1f }, "the point ${result.toList()}")
        assertTrue(search.evaluations < STEPS_OF_ONE_SEARCH, "${search.evaluations} values of the function")
    }

    private companion object {
        /** The values of the function a search of a few steps takes, each tried shorter some twenty times at most. */
        const val STEPS_OF_ONE_SEARCH = 200
    }
}
