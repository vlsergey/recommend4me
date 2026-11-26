package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * The scores of the values asked ([SuggestionScores.scores]) are the chances of every value ([SuggestionScores.of]) of
 * those values alone: whether the values are every value, a set shared by many rows — scored by a product — or a few of
 * a row of its own — summed by hand — the site's examples and the names the texts count landing on the values they are
 * of.
 */
// More than a few seconds: in CI alone — the local build's tests take a minute at most together (user, 2026-11-18)
@Tag("ci")
class ScoringTest {
    private val task = TASK
    private val scores = SCORES
    private val m = VALUES
    private val n = ITEMS

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 1e-5f, "$what: $expected, scored $actual")

    @Test
    fun `the scores of every value, of a shared set and of a few of a row's own are the chances of those values`() {
        val rows = IntArray(n) { it }
        val chances = scores.of(rows)
        // Every value: the same chance, made of the score
        val every = scores.scores(rows, List(n) { null })
        for (i in 0 until n) for (v in 0 until m) assertEquals(
            chances[i][v],
            scores.chance(every[i][v]),
            "item $i, value $v",
        )
        // A set of forty values — more than are looked through — shared by every row: scored by a product
        val shared = IntArray(40) { (it * 11 + 3) % m }.distinct().toIntArray()
        // A few values a row of its own, and none at all
        val own =
            List(n) { i ->
                if (i % 5 == 0) IntArray(0) else intArrayOf(i % m, 40, 41, (i + 17) % m).distinct().toIntArray()
            }
        val columns = List(n) { i -> if (i % 2 == 0) shared else own[i] }
        val asked = scores.scores(rows, columns)
        for (i in 0 until n) {
            assertEquals(columns[i].size, asked[i].size, "item $i")
            columns[i].forEachIndexed { c, v ->
                assertNear(chances[i][v], scores.chance(asked[i][c]), "item $i, value $v")
            }
        }
    }

    @Test
    fun `the rows handed over one at a time, made in a buffer of their block, are the rows of the scores`() {
        // So many rows that the rows of every value are made side by side, a block of them in a buffer each: of the
        // task's few values, a quarter of the rows asked every value ([Scoring]'s DIRECT is 2^16 of rows by values)
        val values = m
        val rows = IntArray(HANDED) { (it * 7) % n }
        val shared = IntArray(40) { (it * 11 + 3) % values }.distinct().toIntArray()
        val columns =
            List(rows.size) { r ->
                when (r % 4) {
                    0 -> null
                    1 -> shared
                    2 -> intArrayOf(r % values, 40, 41).distinct().toIntArray()
                    else -> IntArray(0)
                }
            }
        val whole = scores.scores(rows, columns)
        val handed = arrayOfNulls<FloatArray>(rows.size)
        val threads = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()
        // A row is valid during its call alone: copied, as a reader keeps what it keeps
        scores.scores(rows, columns) { r, row ->
            threads += Thread.currentThread()
            assertEquals(null, handed[r], "row $r handed twice")
            handed[r] = row.copyOf()
        }
        rows.indices.forEach { r -> assertEquals(whole[r].toList(), handed[r]!!.toList(), "row $r") }
        assertTrue(
            threads.size > 1 || Runtime.getRuntime().availableProcessors() == 1,
            "made on ${threads.size} threads",
        )
    }

    @Test
    fun `a few rows of a big set and many of a few values alike`() {
        val rows = intArrayOf(3, 0, 299, 12, 4)
        val chances = scores.of(rows)
        val big = IntArray(m) { m - 1 - it }
        val asked = scores.scores(rows, List(rows.size) { big })
        rows.indices.forEach { r ->
            big.forEachIndexed { c, v -> assertNear(chances[r][v], scores.chance(asked[r][c]), "row $r, value $v") }
        }
    }

    @Test
    fun `a score at or under the half is no likelier than one half, and the chance never falls as the score grows`() {
        assertEquals(0.5f, scores.chance(scores.half))
        var last = 0f
        for (k in -400..400) {
            val chance = scores.chance(k / 10f)
            assertTrue(chance >= last, "the chance of ${k / 10f}")
            last = chance
        }
    }

    private companion object {
        const val DIMENSIONS = 8
        const val VALUES = 50
        const val ITEMS = 300

        /**
         * The rows handed over one at a time: a quarter of them of every value, of a fold more rows by values than are
         * made in one block.
         */
        const val HANDED = 40_000

        /** The one task of the tests, its model fitted once: what is checked is the scoring, not the fit. */
        val TASK = task()
        val SCORES: SuggestionScores =
            assertNotNull(
                BilinearSuggester()
                    .scoring(
                        assertNotNull(BilinearSuggester().fit(TaskPortions.of(TASK))).model(TASK.ofItems(IntArray(0))),
                        TASK,
                    )
            )

        private fun FloatArray.normalized(): FloatArray {
            val l = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
            return FloatArray(size) { this[it] / l }
        }

        /**
         * Items of a text each, values of a name each; an item has a value its text points to; some items the site's
         * examples. A block of notes of every third item besides: of the items that have it alone.
         */
        private fun task(random: Random = Random(5), m: Int = VALUES, n: Int = ITEMS): SuggestionTask {
            val d = DIMENSIONS
            val directions = List(m) { FloatArray(d) { random.nextGaussian().toFloat() }.normalized() }
            val texts = Matrix(n, d)
            val notes = Matrix(n, d)
            val assigned =
                List(n) { i ->
                    val v = FloatArray(d) { random.nextGaussian().toFloat() }.normalized()
                    texts.held.put(v, 0, texts.row(i), d)
                    notes.held.put(FloatArray(d) { v[(it + 1) % d] }, 0, notes.row(i), d)
                    directions.indices
                        .filter { k -> directions[k].indices.sumOf { (directions[k][it] * v[it]).toDouble() } > 0.6 }
                        .toIntArray()
                }
            val names = Matrix(m, d)
            directions.forEachIndexed { k, v -> names.held.put(v, 0, names.row(k), d) }
            val inputs =
                listOf(
                    InputBlock.of("text:annotation", texts, BooleanArray(n) { true }),
                    InputBlock.of("text:notes", notes, BooleanArray(n) { it % 3 == 0 }),
                )
            return SuggestionTask(
                itemIds = LongArray(n) { it.toLong() },
                inputs = inputs,
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
    }
}
