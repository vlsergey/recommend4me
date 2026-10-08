package io.github.vlsergey.recommend4me.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossValidationTest {

    @Test
    fun `only the graded rows have folds, and a work keeps to one`() {
        val grades = IntArray(12) { 1 + it % 5 }
        // Rows 10 and 11 are one work; the two rows after the grades are items graded nowhere
        val works = LongArray(14) { if (it == 11) 10L else it.toLong() }
        val folds = CrossValidation.folds(grades, works)
        assertEquals(grades.size, folds.size)
        assertEquals(folds[10], folds[11])
        assertTrue(folds.distinct().size > 1)
    }
}
