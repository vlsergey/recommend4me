package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The scores of the values asked ([SuggestionScores.scores]) are the chances of every value ([SuggestionScores.of]) of
 * those values alone: whether the values are every value, a set shared by many rows — scored by a product — or a few of
 * a row of its own — summed by hand — the site's examples and the names the texts count landing on the values they are
 * of.
 */
class ScoringTest {
    /** The chances of the items of [task] by the model [fitted] made of it: the one way to them. */
    private fun scored(fitted: FittedSuggester, task: SuggestionTask) =
        assertNotNull(BilinearSuggester().scoring(fitted.model(task), task))

    private val d = 8
    private val m = 50
    private val n = 300

    /** The rows a task of many is fitted on: the fit of more of them is not what a test of the scoring checks. */
    private val fitRows = 2_000

    private fun FloatArray.normalized(): FloatArray {
        val l = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(size) { this[it] / l }
    }

    /**
     * Items of a text each, values of a name each; an item has a value its text points to; some items the site's
     * examples. A block of notes of every third item besides: of the items that have it alone. Given a [card], the
     * inputs put there, as the application puts those of a pass of every work.
     */
    private fun task(
        random: Random = Random(5),
        m: Int = this.m,
        card: Cublas? = null,
        n: Int = this.n,
    ): SuggestionTask {
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
                .map { b ->
                    if (card == null) b
                    else InputBlock(b.name, b.vectors, b.rows, CardRows.put(card, b.vectors, b.rows)!!)
                }
        return SuggestionTask(
            itemIds = List(n) { "item$it" },
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

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 1e-5f, "$what: $expected, scored $actual")

    /**
     * Near as two products of single precision summed in other orders are: by a few of the last digits of their terms —
     * of the scores of this task terms of a hundred, cancelling down to a score of one.
     */
    private fun assertRelative(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 2e-4f + 1e-5f * abs(expected), "$what: $expected, scored $actual")

    @Test
    fun `the scores of every value, of a shared set and of a few of a row's own are the chances of those values`() {
        val task = task()
        val scores = scored(assertNotNull(BilinearSuggester().fit(task)), task)
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
        // So many values that the rows of every value are made side by side, a block of them in a buffer each
        val values = 900
        val task = task(Random(11), values)
        val scores = scored(assertNotNull(BilinearSuggester().fit(task)), task)
        val rows = IntArray(2_000) { (it * 7) % n }
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
        val task = task(Random(9))
        val scores = scored(assertNotNull(BilinearSuggester().fit(task)), task)
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
        val task = task()
        val scores = scored(assertNotNull(BilinearSuggester().fit(task)), task)
        assertEquals(0.5f, scores.chance(scores.half))
        var last = 0f
        for (k in -400..400) {
            val chance = scores.chance(k / 10f)
            assertTrue(chance >= last, "the chance of ${k / 10f}")
            last = chance
        }
    }

    @Test
    fun `on the card the chances and what every row is shown with are those of the main memory`() = cardAlike(n, 900)

    @Test
    fun `on the card as in the main memory of a block of more rows than are made single precision at a time`() =
        cardAlike(maxOf(FeatureKernels.CARD_ROWS, ItemFeatures.ROWS) + 3_000, 60)

    /**
     * Of a task of [items] and [values], the scoring on the card and in the main memory: what every row is shown with
     * the same, the scores near.
     */
    private fun cardAlike(items: Int, values: Int) {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val cublas = Cublas.open(dir!!)
        assumeTrue(cublas != null, "no cuBLAS to run on")
        val here = task(Random(11), values, n = items)
        val there = task(Random(11), values, cublas, items)
        try {
            val suggester = BilinearSuggester()
            // Fitted on the first rows alone — the same values, the same seed: what is checked is the scoring of a
            // block of many rows, not the fit, whose time grows with them
            val fitted = task(Random(11), values, n = minOf(items, fitRows))
            val bytes = assertNotNull(assertNotNull(suggester.fit(fitted)).model(fitted))
            val main = assertNotNull(suggester.scoring(bytes, here))
            val card = assertNotNull(suggester.scoring(bytes, there))
            val rows = IntArray(2_000) { (it * 7 + it / 3 * 4_001) % items }
            // Every value, a set of many rows, a few of a row's own, none; of the closed some
            val shared = IntArray(40) { (it * 11 + 3) % values }.distinct().sorted().toIntArray()
            val columns =
                List(rows.size) { r ->
                    when (r % 4) {
                        0 -> null
                        1 -> shared
                        2 -> intArrayOf(r % values, 40, 41).distinct().toIntArray()
                        else -> IntArray(0)
                    }
                }
            val random = Random(3)
            val closed =
                List(rows.size) {
                    IntArray(random.nextInt(4)) { random.nextInt(values) }.distinct().sorted().toIntArray()
                }
            // The stripe of the one pass as it is, and of one: nearly every row of more likely than it holds, written
            // again whole
            for (stripe in listOf(FeatureKernels.STRIPE, 1)) for (least in listOf(0, 3, FeatureKernels.MAX_LEAST)) {
                (card as Scoring.Scored).stripe = stripe
                val expected = main.shown(rows, columns, closed, least)
                val got = card.shown(rows, columns, closed, least)
                assertTrue(card.onTheCard, "chosen on the card")
                assertShownAlike(expected, got, "least $least, stripe $stripe")
                if (stripe == 1)
                    assertTrue(
                        expected.any { e -> e.scores.count { it > card.half } > 1 },
                        "some row of more likely than the stripe",
                    )
            }
            // The coordinates made on the card, brought over: the chances of every value as the main memory's
            val asked = intArrayOf(0, 3, items - 1, items / 2)
            val a = main.of(asked)
            val b = card.of(asked)
            asked.indices.forEach { r -> for (v in 0 until values) assertNear(a[r][v], b[r][v], "row $r, value $v") }
            card.close()
            main.close()
        } finally {
            there.inputs.forEach { it.onCard?.close() }
        }
    }

    /**
     * What every row is shown with on the card, [got], as in the main memory, [expected]: the same places, the scores
     * near — the coordinates of the card's product and of the processor's differ in their last digits.
     */
    private fun assertShownAlike(expected: List<Shown>, got: List<Shown>, what: String) {
        expected.indices.forEach { r ->
            assertEquals(expected[r].places.toList(), got[r].places.toList(), "row $r, $what")
            expected[r].scores.indices.forEach { k ->
                assertRelative(expected[r].scores[k], got[r].scores[k], "row $r, place ${expected[r].places[k]}")
            }
        }
    }
}
