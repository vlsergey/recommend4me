package io.github.vlsergey.recommend4me.suggestion

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a work is shown of its chances: chosen in one pass, as if every value were looked at in turn. */
class ModelValuesTest {

    /** The rule told value by value: the likely ones, then the likeliest others, one after another, up to [ModelValues.OFFERED] with them. */
    private fun byTheRule(chances: FloatArray, allowed: IntArray?, closed: Set<Int>): Set<Int> {
        val may = allowed?.toSet()
        fun open(v: Int) = v !in closed && (may == null || v in may)
        val likely = chances.indices.filter { open(it) && chances[it] > ModelValues.LIKELY }
        val others = chances.indices.filter { open(it) && chances[it] <= ModelValues.LIKELY }.sortedWith(compareByDescending<Int> { chances[it] }.thenBy { it })
        return (likely + others.take((ModelValues.OFFERED - likely.size).coerceAtLeast(0))).toSet()
    }

    @Test
    fun `the likely and the likeliest others are chosen in one pass`() {
        val random = Random(0)
        repeat(5_000) {
            val size = random.nextInt(1, 40)
            // Equal chances among them: of those the earlier value stays
            val chances = FloatArray(size) { if (random.nextInt(4) == 0) 0.25f else random.nextFloat() * if (random.nextBoolean()) 1f else 0.3f }
            val allowed = if (random.nextBoolean()) null else (0 until size).shuffled(random).take(random.nextInt(size + 1)).toIntArray()
            val closed = (0 until size).shuffled(random).take(random.nextInt(size / 2 + 1)).toSet()
            assertEquals(byTheRule(chances, allowed, closed), ModelValues.open(chances, allowed, closed).toSet(), "chances ${chances.toList()}, allowed ${allowed?.toList()}, closed $closed")
        }
    }

    @Test
    fun `no value but the allowed is looked at`() {
        val chances = FloatArray(6) { 0.9f }
        assertEquals(setOf(1, 4), ModelValues.open(chances, intArrayOf(4, 1), emptySet()).toSet())
        assertEquals(setOf(4), ModelValues.open(chances, intArrayOf(4, 1), setOf(1)).toSet())
    }
}
