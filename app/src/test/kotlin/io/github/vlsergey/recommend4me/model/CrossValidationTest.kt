package io.github.vlsergey.recommend4me.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossValidationTest {

    @Test
    fun `every row has a fold, and a work keeps to one`() {
        val grades = IntArray(12) { 1 + it % 5 }
        // Rows 10 and 11 are one work
        val works = LongArray(12) { if (it == 11) 10L else it.toLong() }
        val folds = CrossValidation.folds(grades, works)
        assertEquals(grades.size, folds.size)
        assertEquals(folds[10], folds[11])
        assertTrue(folds.distinct().size > 1)
    }
}
