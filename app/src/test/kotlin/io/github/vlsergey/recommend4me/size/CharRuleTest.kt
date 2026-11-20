package io.github.vlsergey.recommend4me.size

import java.util.Random
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CharRuleTest {

    /**
     * A site counting a Cyrillic letter by its two bytes, no space, no break: its rule told of its counts, though a
     * tenth of the texts kept are of fewer chapters than the site counts.
     */
    @Test
    fun `how a site counts the characters is told of its counts`() {
        val random = Random(5)
        val counts = DoubleArray(8)
        val classes =
            List(2000) {
                DoubleArray(counts.size) { k -> if (k == 2 || k == 7) 0.0 else 1000.0 + random.nextInt(50_000) }
            }
        val truth = doubleArrayOf(1.0, 2.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
        val stated = classes.mapIndexed { i, c ->
            val whole = c.indices.sumOf { truth[it] * c[it] }
            if (i % 10 == 0) whole * 2 else whole
        }
        val rule = assertNotNull(CharRule.of(classes, stated))
        listOf(0, 1, 3, 4, 5, 6).forEach { k ->
            assertTrue(abs(rule.coefficients[k] - truth[k]) < 0.05, "$k: $rule")
        }
    }
}
