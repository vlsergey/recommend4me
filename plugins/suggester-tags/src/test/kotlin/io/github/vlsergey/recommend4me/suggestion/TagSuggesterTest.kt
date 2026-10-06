package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNull
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
     * Two kinds of works: about space (tags 0 "космос" and 1 "корабли", context 0 — a fandom) and
     * about dragons (2 "драконы" and 3 "магия", context 1). As on a site, some works carry one tag
     * of their two; value 4 is noise every tenth work has.
     */
    private class Catalogue(val texts: MutableList<FloatArray>, val tags: MutableList<IntArray>, val context: MutableList<IntArray>)

    private fun catalogue(): Catalogue {
        val c = Catalogue(ArrayList(), ArrayList(), ArrayList())
        repeat(400) { k ->
            val space = k % 2 == 0
            c.texts += text(if (space) 0 else 1)
            val both = if (space) mutableListOf(0, 1) else mutableListOf(2, 3)
            val own = if (k % 3 == 0) mutableListOf(both[(k / 3) % 2]) else both
            if (k % 10 == 0) own += 4
            c.tags += own.toIntArray()
            c.context += intArrayOf(if (space) 0 else 1)
        }
        return c
    }

    private val names = matrix(listOf(text(0), text(5), text(1), text(6), text(7)))

    private fun task(c: Catalogue, views: List<List<FloatArray>> = listOf(c.texts)) = SuggestionTask(
        views = views.mapIndexed { k, v -> TextView("view$k", matrix(v)) },
        values = names,
        assigned = c.tags,
        confirmed = List(c.tags.size) { IntArray(0) },
        rejected = List(c.tags.size) { IntArray(0) },
        context = c.context,
        contextCount = 2,
    )

    @Test
    fun `a missing tag of the kind of the work is suggested, a foreign one is doubted`() {
        val c = catalogue()
        // A space work the site gave no tags, and one it gave a dragons' tag by mistake
        c.texts += text(0); c.tags += IntArray(0); c.context += intArrayOf(0)
        c.texts += text(0); c.tags += intArrayOf(0, 1, 2); c.context += intArrayOf(0)
        val task = task(c)
        val scores = TagSuggester().fit(task)!!.on(task).of(intArrayOf(c.texts.size - 2, c.texts.size - 1))

        val untagged = scores[0]
        assertTrue(untagged[0] > 0.5f && untagged[0] > 5 * untagged[2], "space for a space text: ${untagged.toList()}")
        val mistaken = scores[1]
        assertTrue(mistaken[2] < 0.2f && mistaken[0] > 0.5f, "dragons doubtful on a space text: ${mistaken.toList()}")
    }

    @Test
    fun `the fitted weights survive packing, and do not fit a task of other views`() {
        val c = catalogue()
        val task = task(c)
        val fitted = TagSuggester().fit(task)!!
        val again = TagSuggester().unpack(fitted.pack(), task)!!
        val a = fitted.on(task).of(intArrayOf(0, 1))
        val b = again.on(task).of(intArrayOf(0, 1))
        assertTrue(a.indices.all { a[it].contentEquals(b[it]) })
        assertNull(TagSuggester().unpack(fitted.pack(), task(c, listOf(c.texts, c.texts))))
    }

    @Test
    fun `without texts the context and the other tags tell`() {
        val c = catalogue()
        c.tags += IntArray(0); c.context += intArrayOf(1)
        val zeros = List(c.tags.size) { FloatArray(d) }
        val task = task(c, listOf(zeros))
        val scores = TagSuggester().fit(task)!!.on(task).of(intArrayOf(c.tags.size - 1))[0]
        assertTrue(scores[2] > scores[0] * 5 && scores[3] > scores[1] * 5, "dragons go with their fandom: ${scores.toList()}")
    }

    @Test
    fun `a second view helps where the first says nothing`() {
        val c = catalogue()
        // The descriptions say nothing, nor does any context; the chapters do
        c.context.replaceAll { IntArray(0) }
        val blank = c.texts.map { text(9) }.toMutableList()
        c.texts += text(1); blank += text(9); c.tags += IntArray(0); c.context += IntArray(0)
        val task = task(c, listOf(blank, c.texts))
        val scores = TagSuggester().fit(task)!!.on(task).of(intArrayOf(c.tags.size - 1))[0]
        assertTrue(maxOf(scores[2], scores[3]) > 0.5f && minOf(scores[2], scores[3]) > 100 * maxOf(scores[0], scores[1]), "dragons from the chapters: ${scores.toList()}")
    }

    @Test
    fun `a value the texts name often is likelier than one they never name`() {
        val c = catalogue()
        // Neither the texts nor any context tell; the works name their own values, now and then another
        c.context.replaceAll { IntArray(0) }
        val blank = c.texts.map { text(9) }.toMutableList()
        val mentions = c.tags.mapIndexed { k, own -> own.associateWith { 5 + k % 7 } + mapOf((k % 5) to 1) }.toMutableList<Map<Int, Int>?>()
        blank += text(9); c.tags += IntArray(0); c.context += IntArray(0); mentions += mapOf(3 to 12)
        val task = task(c, listOf(blank)).let { t ->
            SuggestionTask(t.views, t.values, t.assigned, t.confirmed, t.rejected, t.context, t.contextCount, mentions)
        }
        val scores = TagSuggester().fit(task)!!.on(task).of(intArrayOf(c.tags.size - 1))[0]
        assertTrue(scores[3] > 0.5f && scores[3] > 10 * scores[0], "the named value: ${scores.toList()}")
    }
}
