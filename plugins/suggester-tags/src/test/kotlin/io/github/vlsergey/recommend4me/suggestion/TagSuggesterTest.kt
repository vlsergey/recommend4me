package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

class TagSuggesterTest {

    private val d = 16
    private val random = Random(1)

    private fun unit(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    /** A text about [topic]: its axis with some noise. */
    private fun text(topic: Int) = unit(FloatArray(d) { q -> (if (q == topic) 3f else 0f) + random.nextFloat() * 0.6f })

    private fun matrix(rows: List<FloatArray>): Matrix {
        val m = Matrix(rows.size, d)
        rows.forEachIndexed { i, r -> m.held.put(r, 0, m.row(i), d) }
        return m
    }

    /**
     * Two kinds of works: about space (tags 0 "космос" and 1 "корабли") and about dragons (2
     * "драконы" and 3 "магия"). Value 4 is noise every tenth work has.
     */
    private fun catalogue(): Triple<MutableList<FloatArray>, MutableList<IntArray>, Matrix> {
        val texts = ArrayList<FloatArray>()
        val tags = ArrayList<IntArray>()
        repeat(400) { k ->
            val space = k % 2 == 0
            texts += text(if (space) 0 else 1)
            val own = if (space) mutableListOf(0, 1) else mutableListOf(2, 3)
            if (k % 10 == 0) own += 4
            tags += own.toIntArray()
        }
        val names = matrix(listOf(text(0), text(5), text(1), text(6), text(7)))
        return Triple(texts, tags, names)
    }

    private fun task(texts: List<FloatArray>, tags: List<IntArray>, names: Matrix, rejected: List<IntArray> = List(tags.size) { IntArray(0) }) =
        SuggestionTask(matrix(texts), names, tags, List(tags.size) { IntArray(0) }, rejected)

    @Test
    fun `a missing tag of the kind of the work is suggested, a foreign one is not`() {
        val (texts, tags, names) = catalogue()
        // A space work the site gave no tags, and one it gave a dragons' tag by mistake
        texts += text(0)
        tags += IntArray(0)
        texts += text(0)
        tags += intArrayOf(0, 1, 2)
        val task = task(texts, tags, names)
        val fitted = TagSuggester().fit(task)
        val scores = fitted.on(task).of(intArrayOf(texts.size - 2, texts.size - 1))

        val untagged = scores[0]
        assertTrue(untagged[0] > 0.5f && untagged[0] > 5 * untagged[2], "space for a space text: ${untagged.toList()}")
        val mistaken = scores[1]
        assertTrue(mistaken[2] < 0.2f && mistaken[0] > 0.5f, "dragons doubtful on a space text: ${mistaken.toList()}")
    }

    @Test
    fun `the fitted weights survive packing`() {
        val (texts, tags, names) = catalogue()
        val task = task(texts, tags, names)
        val fitted = TagSuggester().fit(task)
        val again = TagSuggester().unpack(fitted.pack())
        val a = fitted.on(task).of(intArrayOf(0, 1))
        val b = again.on(task).of(intArrayOf(0, 1))
        assertTrue(a.indices.all { a[it].contentEquals(b[it]) })
    }

    @Test
    fun `a catalogue without texts still learns from the other tags`() {
        val (_, tags, names) = catalogue()
        val zeros = List(tags.size + 1) { FloatArray(d) }
        tags += intArrayOf(0)
        val task = task(zeros, tags, names)
        val scores = TagSuggester().fit(task).on(task).of(intArrayOf(tags.size - 1))[0]
        assertTrue(scores[1] > scores[2] * 5, "ships go with space: ${scores.toList()}")
    }
}
