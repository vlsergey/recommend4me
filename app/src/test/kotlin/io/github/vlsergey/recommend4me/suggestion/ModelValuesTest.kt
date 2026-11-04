package io.github.vlsergey.recommend4me.suggestion

import kotlin.math.exp
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

    /** [ModelValues.open] of every value's [chances], or of the [allowed] alone scored apart, as the values chosen. */
    private fun opened(chances: FloatArray, allowed: IntArray?, closed: Set<Int>, of: SuggestionScores = ModelValues.CHANCES, scores: FloatArray = chances): Set<Int> {
        val asked = allowed?.let { a -> FloatArray(a.size) { scores[a[it]] } } ?: scores
        return ModelValues.open(asked, allowed, closed.sorted().toIntArray(), of).map { allowed?.get(it) ?: it }.toSet()
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
            assertEquals(byTheRule(chances, allowed, closed), opened(chances, allowed, closed), "chances ${chances.toList()}, allowed ${allowed?.toList()}, closed $closed")
        }
    }

    @Test
    fun `chosen of the scores the chances are made of, the same as of the chances, a chance made of the likely alone`() {
        val random = Random(1)
        var made = 0
        val sigmoid = object : SuggestionScores {
            override fun of(rows: IntArray): List<FloatArray> = error("Of scores alone")
            override fun chance(score: Float): Float {
                made++
                return 1f / (1f + exp(-score))
            }
            override val half: Float get() = 0f
        }
        repeat(5_000) {
            val size = random.nextInt(1, 60)
            // Scores apart enough that their chances are apart too
            val scores = FloatArray(size) { (random.nextInt(-400, 400) / 50f) }.distinct().toFloatArray()
            val chances = FloatArray(scores.size) { sigmoid.chance(scores[it]) }
            val allowed = if (random.nextBoolean()) null else (scores.indices).shuffled(random).take(random.nextInt(scores.size + 1)).toIntArray()
            val closed = (scores.indices).shuffled(random).take(random.nextInt(scores.size / 2 + 1)).toSet()
            made = 0
            assertEquals(byTheRule(chances, allowed, closed), opened(chances, allowed, closed, sigmoid, scores), "scores ${scores.toList()}, allowed ${allowed?.toList()}, closed $closed")
            assertEquals(scores.indices.count { (allowed == null || it in allowed) && scores[it] > 0f }, made, "chances made")
        }
    }

    @Test
    fun `no value but the allowed is looked at`() {
        val chances = FloatArray(6) { 0.9f }
        assertEquals(setOf(1, 4), opened(chances, intArrayOf(4, 1), emptySet()))
        assertEquals(setOf(4), opened(chances, intArrayOf(4, 1), setOf(1)))
    }
}
