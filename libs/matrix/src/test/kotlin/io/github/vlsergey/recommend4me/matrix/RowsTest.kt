package io.github.vlsergey.recommend4me.matrix

import java.util.Random
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Rows kept sparse or a byte a number read as the rows they are made of, within the digits they keep. */
class RowsTest {
    private val random = Random(7)
    private val cols = 64

    /** A unit row of a few counts — as a short text's trigrams —, or of every number. */
    private fun unit(sparse: Boolean): FloatArray {
        val v = FloatArray(cols)
        if (sparse) repeat(5) { v[random.nextInt(cols)] += 1f }
        else for (q in 0 until cols) v[q] = random.nextGaussian().toFloat()
        val l = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(cols) { v[it] / l }
    }

    private fun dot(x: FloatArray, y: FloatArray) = x.indices.sumOf { (x[it] * y[it]).toDouble() }.toFloat()

    @Test
    fun `sparse rows keep their numbers, a row of none none, and a run of them reads as its rows`() {
        val made = List(20) { if (it == 3) null else unit(sparse = true) }
        val rows = SparseRows.of(made.size, cols) { made[it] }
        val into = FloatArray(cols)
        made.forEachIndexed { i, v ->
            rows.take(i, into)
            (v ?: FloatArray(cols)).forEachIndexed { q, x -> assertEquals(x, into[q], 1e-3f, "$i:$q") }
        }
        assertEquals(dot(made[1]!!, made[2]!!), rows.dot(1, rows, 2), 1e-3f)
        val run = rows.rows(10, 5)
        run.take(2, into)
        made[12]!!.forEachIndexed { q, x -> assertEquals(x, into[q], 1e-3f) }
        // The entries of the run, from nought, as the card is sent them: a row's column and half in an int
        val e = run.entries(0, 5)
        assertEquals(e.packed.size, e.at.last())
        assertEquals(0, e.at.first())
        val floats = Floats(5 * cols)
        run.floats(0, 5, floats)
        assertContentEquals(FloatArray(cols).also { rows.take(10, it) }, FloatArray(cols) { floats[it] })
    }

    @Test
    fun `rows of a byte a number move a cosine of unit rows by thousandths`() {
        val made = List(10) { unit(sparse = false) }
        val rows = ByteMatrix(made.size, cols).also { m -> made.forEachIndexed { i, v -> m.put(i, v) } }
        for (i in made.indices) for (j in made.indices) assertEquals(dot(made[i], made[j]), rows.dot(i, rows, j), 5e-3f)
        // Their bytes summed as integers: the product of the rows read as single precision, but for the last digits
        val x = FloatArray(cols)
        val y = FloatArray(cols)
        for (i in made.indices) for (j in made.indices) {
            rows.take(i, x)
            rows.take(j, y)
            assertEquals(dot(x, y), rows.dot(i, rows, j), 1e-6f)
        }
        val run = rows.rows(4, 3)
        assertEquals(dot(made[5], made[6]), run.dot(1, rows, 6), 5e-3f)
        assertContentEquals(rows.scales(4, 3), run.scales(0, 3))
    }
}
