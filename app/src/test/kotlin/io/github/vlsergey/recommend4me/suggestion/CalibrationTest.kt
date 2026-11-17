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
     * Rather shown wrongly than hidden: the cutoff leaves no more answers wrongly hidden than wrongly shown, and is the
     * highest that does.
     */
    @Test
    fun `the cutoff shows wrongly at least as many as it hides wrongly`() {
        val answered = answers("v", 3000, 3) { Calibration.sigmoid(it.toDouble() - 1) }
        val c = assertNotNull(Calibration.fit(answered))
        val shownWrongly = answered.count { !it.yes && c.likely(it.value, it.score) }
        val hiddenWrongly = answered.count { it.yes && !c.likely(it.value, it.score) }
        assertTrue(shownWrongly >= hiddenWrongly, "shown $shownWrongly, hidden $hiddenWrongly")
        // The highest such: one step up hides more wrongly than it shows
        val above = answered.map { c.chance(it.value, it.score) }.filter { it > c.threshold }.minOrNull()
        if (above != null)
            assertTrue(
                answered.count { !it.yes && c.chance(it.value, it.score) >= above } <
                    answered.count { it.yes && c.chance(it.value, it.score) < above }
            )
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
}
