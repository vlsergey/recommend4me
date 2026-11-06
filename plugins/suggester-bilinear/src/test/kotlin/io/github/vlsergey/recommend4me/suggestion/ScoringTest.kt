package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The scores of the values asked ([SuggestionScores.scores]) are the chances of every value
 * ([SuggestionScores.of]) of those values alone: whether the values are every value, a set shared by
 * many rows — scored by a product — or a few of a row of its own — summed by hand — the site's
 * examples and the names the texts count landing on the values they are of.
 */
class ScoringTest {

    private val d = 8
    private val m = 50
    private val n = 300

    private fun FloatArray.normalized(): FloatArray {
        val l = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(size) { this[it] / l }
    }

    /** Items of a text each, values of a name each; an item has a value its text points to; some items the site's examples, some the texts' counts of names. */
    private fun task(random: Random = Random(5)): SuggestionTask {
        val directions = List(m) { FloatArray(d) { random.nextGaussian().toFloat() }.normalized() }
        val texts = Matrix(n, d)
        val assigned = List(n) { i ->
            val v = FloatArray(d) { random.nextGaussian().toFloat() }.normalized()
            texts.held.put(v, 0, texts.row(i), d)
            directions.indices.filter { k -> directions[k].indices.sumOf { (directions[k][it] * v[it]).toDouble() } > 0.6 }.toIntArray()
        }
        val names = Matrix(m, d)
        directions.forEachIndexed { k, v -> names.held.put(v, 0, names.row(k), d) }
        return SuggestionTask(
            itemIds = List(n) { "item$it" },
            inputs = listOf(InputBlock("text:annotation", texts, BooleanArray(n) { true })),
            values = names,
            valueKeys = List(m) { "v$it" },
            assigned = assigned,
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { IntArray(0) },
            context = List(n) { IntArray(0) },
            contextKeys = emptyList(),
            examples = List(n) { i -> if (i % 4 == 0) intArrayOf((i * 3) % m, 41) else IntArray(0) },
        )
    }

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 1e-5f, "$what: $expected, scored $actual")

    @Test
    fun `the scores of every value, of a set shared by many rows and of a few of a row's own are the chances of those values`() {
        val task = task()
        val scores = assertNotNull(BilinearSuggester().fit(task)).on(task)
        val rows = IntArray(n) { it }
        val chances = scores.of(rows)
        // Every value: the same chance, made of the score
        val every = scores.scores(rows, List(n) { null })
        for (i in 0 until n) for (v in 0 until m) assertEquals(chances[i][v], scores.chance(every[i][v]), "item $i, value $v")
        // A set of forty values — more than are looked through — shared by every row: scored by a product
        val shared = IntArray(40) { (it * 11 + 3) % m }.distinct().toIntArray()
        // A few values a row of its own, and none at all
        val own = List(n) { i -> if (i % 5 == 0) IntArray(0) else intArrayOf(i % m, 40, 41, (i + 17) % m).distinct().toIntArray() }
        val columns = List(n) { i -> if (i % 2 == 0) shared else own[i] }
        val asked = scores.scores(rows, columns)
        for (i in 0 until n) {
            assertEquals(columns[i].size, asked[i].size, "item $i")
            columns[i].forEachIndexed { c, v -> assertNear(chances[i][v], scores.chance(asked[i][c]), "item $i, value $v") }
        }
    }

    @Test
    fun `a few rows of a big set and many of a few values alike`() {
        val task = task(Random(9))
        val scores = assertNotNull(BilinearSuggester().fit(task)).on(task)
        val rows = intArrayOf(3, 0, 299, 12, 4)
        val chances = scores.of(rows)
        val big = IntArray(m) { m - 1 - it }
        val asked = scores.scores(rows, List(rows.size) { big })
        rows.indices.forEach { r -> big.forEachIndexed { c, v -> assertNear(chances[r][v], scores.chance(asked[r][c]), "row $r, value $v") } }
    }

    @Test
    fun `a score at or under the half is no likelier than one half, and the chance never falls as the score grows`() {
        val task = task()
        val scores = assertNotNull(BilinearSuggester().fit(task)).on(task)
        assertEquals(0.5f, scores.chance(scores.half))
        var last = 0f
        for (k in -400..400) {
            val chance = scores.chance(k / 10f)
            assertTrue(chance >= last, "the chance of ${k / 10f}")
            last = chance
        }
    }
}
