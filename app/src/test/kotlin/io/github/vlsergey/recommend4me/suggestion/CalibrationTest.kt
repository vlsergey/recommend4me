package io.github.vlsergey.recommend4me.suggestion

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalibrationTest {

    /** Answers of [value] whose chance of yes at a score is [truth] of it: [n] of them, scores spread over -4..4. */
    private fun answers(value: String, n: Int, seed: Int, truth: (Float) -> Double): List<Answered> {
        val random = Random(seed)
        return List(n) {
            val score = random.nextFloat() * 8 - 4
            Answered(value, score, random.nextDouble() < truth(score))
        }
    }

    /** Of no answer none; of answers all of one word none: a likely value is told from an unlikely one of both. */
    @Test
    fun `none of no answer, nor of answers all of one word`() {
        assertNull(Calibration.fit(emptyList()))
        assertNull(Calibration.fit(List(50) { Answered("v", it / 10f, true) }))
        assertNull(Calibration.fit(List(50) { Answered("v", it / 10f, false) }))
    }

    /** Of answers whose curve is one stretch — every score as likely — none: a likely value is not told by them. */
    @Test
    fun `none of a curve of one stretch`() {
        // A no above every yes: pooled, the curve is one stretch
        assertNull(Calibration.fit(List(20) { Answered("v", it / 10f, it < 19) }))
    }

    /**
     * A value of a few answers all of one word among many values: a finite shift, drawn to the facet's curve — its
     * chance at a low score not made all but certain (2026-11-19: of author.today's tags of two yeses each, slopes of
     * 10⁷, every work given them).
     */
    @Test
    fun `a value of few answers all of one word is drawn to the facet's curve`() {
        val common = (1..30).flatMap { v -> answers("v$v", 40, v) { Calibration.sigmoid(it.toDouble()) } }
        val yeses = (1..20).flatMap { v -> List(2) { Answered("yes$v", 1f + it, true) } }
        val noes = (1..20).flatMap { v -> List(2) { Answered("no$v", -1f - it, false) } }
        val c = assertNotNull(Calibration.fit(common + yeses + noes))
        val low = c.chance("yes1", -3f)
        assertTrue(low < 0.5, "a value of two yeses at a low score: $low")
        val high = c.chance("no1", 3f)
        assertTrue(high > 0.5, "a value of two noes at a high score: $high")
    }

    /** A suggester too sure of itself: the facet's curve gives the share of yeses a score has. */
    @Test
    fun `the facet's curve is monotone and true to the answers`() {
        val c = assertNotNull(Calibration.fit(answers("v", 4000, 1) { Calibration.sigmoid(it / 2.0) }))
        var before = 0.0
        for (k in -40..40) {
            val chance = c.chance("v", k / 10f)
            assertTrue(chance >= before, "monotone at ${k / 10f}")
            before = chance
        }
        assertEquals(Calibration.sigmoid(1.0), c.chance("other", 2f), 0.06)
        assertEquals(0.5, c.chance("other", 0f), 0.06)
    }

    /**
     * A value of many answers its own curve, one of none the facet's, one of a few between: the values are seen to
     * differ from the facet's curve.
     */
    @Test
    fun `a value is its own of many answers, the facet's of none`() {
        // The facet's curve is of every answer: the shifted value's few among many
        val common = (1..30).flatMap { v -> answers("v$v", 300, v) { Calibration.sigmoid(it.toDouble()) } }
        val shifted = answers("rare", 600, 99) { Calibration.sigmoid(it.toDouble() + 2) }
        val few = answers("few", 6, 7) { Calibration.sigmoid(it.toDouble() + 2) }
        val c = assertNotNull(Calibration.fit(common + shifted + few))
        val facet = c.chance("unanswered", 0f)
        assertEquals(0.5, facet, 0.1)
        assertEquals(Calibration.sigmoid(2.0), c.chance("rare", 0f), 0.08)
        val between = c.chance("few", 0f)
        assertTrue(between >= facet && between <= c.chance("rare", 0f), "few $between")
    }

    /**
     * Rather shown wrongly than hidden: of the chances of every work's open values, counted a portion at a time, the
     * cutoff leaves no more expected wrongly hidden than wrongly shown, and is the highest that does — within a stretch
     * of the counting ([OpenChances.BINS]).
     */
    @Test
    fun `the cutoff shows wrongly at least as many as it hides wrongly`() {
        val random = Random(5)
        val chances = DoubleArray(5000) { random.nextDouble().let { it * it * it } }
        val open = OpenChances()
        chances.forEach(open::add)
        val t = open.balanced()
        fun shown(at: Double) = chances.filter { it >= at }.sumOf { 1 - it }
        fun hidden(at: Double) = chances.filter { it < at }.sum()
        assertTrue(shown(t) >= hidden(t), "at $t shown ${shown(t)}, hidden ${hidden(t)}")
        // The highest that does, of every chance held
        val highest = chances.sortedDescending().first { shown(it) >= hidden(it) }
        assertEquals(highest, t, 1.0 / OpenChances.BINS)
        assertTrue(t < 0.5, "rather shown: $t")
        assertEquals(0.5, OpenChances().balanced())
    }

    /** A score above a value's cutoff is likely, one below it is not; kept and read back the same. */
    @Test
    fun `the cutoff of a score tells what is likely, and is kept`() {
        val c =
            assertNotNull(
                Calibration.fit(
                    (1..10).flatMap { v -> answers("v$v", 200, v) { Calibration.sigmoid(it.toDouble() + v / 5.0) } }
                )
            )
        val read = assertNotNull(Calibration.unpack(c.pack()))
        (1..10).forEach { v ->
            val value = "v$v"
            val cut = c.cutoff(value)
            if (cut.isFinite()) {
                assertTrue(c.likely(value, Math.nextUp(cut) + 1e-3f), "$value above $cut")
                assertTrue(!c.likely(value, cut - 1e-3f), "$value below $cut")
            }
            assertEquals(c.chance(value, 0.3f), read.chance(value, 0.3f), 1e-9)
            assertEquals(cut, read.cutoff(value))
        }
        assertEquals(c.threshold, read.threshold)
    }

    /**
     * Under the lowest score answered the answers tell nothing: no value likely by them, its chance no higher than the
     * suggester's own — of answers all of high scores, every lower score is not made likely by their last chance.
     */
    @Test
    fun `under the lowest score answered nothing is likely, the chance no higher than the suggester's`() {
        val random = Random(11)
        val high = List(400) { Answered("v", 2f + random.nextFloat() * 2, random.nextDouble() < 0.7) }
        val c = assertNotNull(Calibration.fit(high))
        assertTrue(!c.likely("v", 0f) && !c.likely("v", -3f))
        assertTrue(c.chance("v", 0f, 0.05) <= 0.05)
        assertTrue(c.cutoff("v") >= Math.nextDown(c.lowest))
        assertEquals(c.chance("v", 3f), c.chance("v", 3f, 0.01))
    }
}
