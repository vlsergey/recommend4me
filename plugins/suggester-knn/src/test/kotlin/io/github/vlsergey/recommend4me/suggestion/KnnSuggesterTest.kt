package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnnSuggesterTest {
    /** The chances of the items of [task] by the model [fitted] made of it: the one way to them. */
    private fun scored(fitted: FittedSuggester, task: SuggestionTask) =
        assertNotNull(KnnSuggester().scoring(fitted.model(task.valueSide()), task))

    /** The fitting of [task] handed over whole, as one portion. */
    private fun fit(task: SuggestionTask) = assertNotNull(KnnSuggester().fit(portionsOf(task, TaskPortions.MOST)))

    /** The [task] of the blocks of input [inputs] in place of its own. */
    private fun SuggestionTask.withInputs(inputs: List<InputBlock>) =
        SuggestionTask(
            itemIds,
            inputs,
            values,
            valueKeys,
            assigned,
            confirmed,
            rejected,
            context,
            contextKeys,
            allowed,
            groupings,
            lackWeight,
            examples,
            hints,
            hintTexts,
            drafts,
            draftWeight,
            barred,
        )

    /**
     * The value side of the task, of no item, as the application hands it over: of no input block — the blocks are the
     * portions' items'.
     */
    private fun SuggestionTask.valueSide() = ofItems(IntArray(0)).withInputs(emptyList())

    /**
     * The items of [task] in portions of [size], as the application hands a source of many works over: the value side
     * of no input block, a portion of the blocks its items have alone; [walks]: how often they were walked.
     */
    private fun portionsOf(task: SuggestionTask, size: Int, walks: IntArray = IntArray(1)): TaskPortions =
        object : TaskPortions {
            override val values = task.valueSide()
            override val items = task.itemCount

            override fun forEach(action: (SuggestionTask) -> Unit) {
                walks[0]++
                for (from in 0 until task.itemCount step size) {
                    val portion = task.ofItems(IntArray(minOf(size, task.itemCount - from)) { from + it })
                    action(portion.withInputs(portion.inputs.filter { it.rows.isNotEmpty() }))
                }
            }
        }

    /** Of [n] works, the texts of those of the value 0 lie near one direction, of the value 1 near another. */
    private fun task(n: Int = 80, d: Int = 16): SuggestionTask {
        val random = Random(11)
        val centres = List(2) { FloatArray(d) { random.nextGaussian().toFloat() } }
        val texts = Matrix(n, d)
        repeat(n) { i ->
            val v = FloatArray(d) { centres[i % 2][it] + 0.3f * random.nextGaussian().toFloat() }
            texts.held.put(v, 0, texts.row(i), d)
        }
        return SuggestionTask(
            itemIds = List(n) { "w$it" },
            inputs = listOf(InputBlock.of("text:annotation", texts, BooleanArray(n) { true })),
            values = Matrix(2, 4),
            valueKeys = listOf("v0", "v1"),
            assigned = List(n) { intArrayOf(it % 2) },
            confirmed = List(n) { if (it == 33) intArrayOf(1) else IntArray(0) },
            rejected = List(n) { if (it == 50) intArrayOf(1) else IntArray(0) },
            context = List(n) { IntArray(0) },
            contextKeys = emptyList(),
        )
    }

    @Test
    fun `a work has the values of the works nearest it by its texts, judged on works held out`() {
        val task = task()
        // No card: the processor finds the nearest
        val fitted = fit(task)
        val report = assertNotNull(fitted.report)
        assertTrue(report.tried.size == 4 && report.fromScratch)
        assertTrue(report.loss < 0.3, "loss ${report.loss}")
        val chances = scored(fitted, task).of(intArrayOf(0, 1))
        assertTrue(chances[0][0] > 0.8f && chances[0][1] < 0.2f, chances[0].toList().toString())
        assertTrue(chances[1][1] > 0.8f && chances[1][0] < 0.2f, chances[1].toList().toString())
        // Packed: the works gathered and the k chosen, the model made of them alone; of other values, fitted again
        val side = task.valueSide()
        val unpacked = assertNotNull(KnnSuggester().unpack(fitted.pack(), side))
        assertTrue(fitted.model(side).contentEquals(unpacked.model(side)))
        assertNull(KnnSuggester().unpack(fitted.pack(), answeredTask(answeredTexts(), listOf(0), listOf("w0"))))
    }

    @Test
    fun `a work read alone later is scored by its nearest among the works kept, of its own texts, never by itself`() {
        val task = task()
        val suggester = KnnSuggester()
        val model = fit(task).model(task.valueSide())
        // A page of two works: one never fitted on, its text the text of a work of the value 1; and the work w0 itself,
        // of the value 0, its text now that of a work of the value 1 — scored by the others, not by its own old self
        val block = task.inputs.single()
        val texts = Matrix(2, block.dim)
        val row = FloatArray(block.dim)
        block.vectors.take(block.rows.indexOf(1), row)
        texts.held.put(row, 0, texts.row(0), block.dim)
        texts.held.put(row, 0, texts.row(1), block.dim)
        val alone =
            SuggestionTask(
                itemIds = listOf("new", "w0"),
                inputs = listOf(InputBlock.of("text:annotation", texts, BooleanArray(2) { true })),
                values = Matrix(2, 0),
                valueKeys = listOf("v0", "v1"),
                assigned = List(2) { IntArray(0) },
                confirmed = List(2) { IntArray(0) },
                rejected = List(2) { IntArray(0) },
                context = List(2) { IntArray(0) },
                contextKeys = emptyList(),
            )
        val chances = assertNotNull(suggester.scoring(model, alone)).of(intArrayOf(0, 1))
        chances.forEach { assertTrue(it[1] > 0.8f && it[0] < 0.2f, it.toList().toString()) }
        // Every value's share of the works fitted on, told without a work read
        val weights = assertNotNull(suggester.weights(model))
        assertEquals(0.5, weights[0], 0.01)
        // Of a block of another width: not scored
        val other =
            SuggestionTask(
                alone.itemIds,
                listOf(InputBlock.of("text:annotation", Matrix(2, block.dim + 1), BooleanArray(2) { true })),
                alone.values,
                alone.valueKeys,
                alone.assigned,
                alone.confirmed,
                alone.rejected,
                alone.context,
                alone.contextKeys,
            )
        assertNull(suggester.scoring(model, other))
    }

    @Test
    fun `the works kept as neighbours are bounded, the answered first, the same at every walk however portioned`() {
        val task = task()
        val small = KnnSuggester(null, room = 30)
        val walks = IntArray(1)
        val once = small.gather(portionsOf(task, 7, walks)) { false }.reference
        val again = small.gather(portionsOf(task, 7, walks)) { false }.reference
        val whole = small.gather(portionsOf(task, TaskPortions.MOST)) { false }.reference
        assertEquals(30, once.size)
        assertEquals(listOf("w33", "w50"), once.ids.take(2).sorted())
        assertEquals(once.ids, again.ids)
        assertEquals(once.ids, whole.ids)
        val numbers = once.size * Neighbours.DIMENSIONS
        for (q in 0 until numbers) assertEquals(whole.vectors[q], once.vectors[q], 1e-5f)
        // Every work's share counted, not those kept alone
        val gathered = small.gather(portionsOf(task, 7)) { false }
        assertEquals(40.0, gathered.total.one[0], 1e-9)
        // The model holds the works kept alone, and it is fitted in two walks: one to choose them, one to place them
        walks[0] = 0
        val fitted = assertNotNull(small.fit(portionsOf(task, 7, walks)))
        assertEquals(2, walks[0])
        assertEquals(once.ids, assertNotNull(KnnModel.read(fitted.model(task.valueSide()))).ids)
    }

    @Test
    fun `never more works kept than a portion holds`() {
        val task = task(TaskPortions.MOST + 300, 4)
        val reference = KnnSuggester().gather(portionsOf(task, 1000)) { false }.reference
        assertEquals(TaskPortions.MOST, reference.size)
        assertEquals(listOf("w33", "w50"), reference.ids.take(2).sorted())
    }

    /** The texts of 12 works, those of the even near one direction, of the odd near another. */
    private fun answeredTexts(): List<FloatArray> {
        val d = 16
        val random = Random(17)
        val centres = List(2) { FloatArray(d) { random.nextGaussian().toFloat() } }
        return List(12) { i -> FloatArray(d) { centres[i % 2][it] + 0.3f * random.nextGaussian().toFloat() } }
    }

    /**
     * The works [rows] of the [texts], their values those of their texts' direction, the work 4 alone the value 2 too:
     * the user confirmed its 0 and 2 and rejected its 1, rejected the 0 of the work 7 and confirmed the 1 of the
     * work 9. [ids]: of every work of [rows].
     */
    private fun answeredTask(texts: List<FloatArray>, rows: List<Int>, ids: List<String>): SuggestionTask {
        val d = texts.first().size
        val matrix = Matrix(rows.size, d)
        rows.forEachIndexed { r, i -> matrix.held.put(texts[i], 0, matrix.row(r), d) }
        return SuggestionTask(
            itemIds = ids,
            inputs = listOf(InputBlock.of("text:annotation", matrix, BooleanArray(rows.size) { true })),
            values = Matrix(3, 4),
            valueKeys = listOf("v0", "v1", "v2"),
            assigned = rows.map { if (it == 4) intArrayOf(0, 2) else intArrayOf(it % 2) },
            confirmed = rows.map { if (it == 4) intArrayOf(0, 2) else if (it == 9) intArrayOf(1) else IntArray(0) },
            rejected = rows.map { if (it == 4) intArrayOf(1) else if (it == 7) intArrayOf(0) else IntArray(0) },
            context = rows.map { IntArray(0) },
            contextKeys = emptyList(),
        )
    }

    /**
     * The chances of a work never fitted on, of the text of the work [i], by a model of the works [others] gathered, of
     * the k [fitted] chose.
     */
    private fun asked(fitted: FittedSuggester, texts: List<FloatArray>, others: List<Int>, i: Int): FloatArray {
        val rest = answeredTask(texts, others, others.map { "w$it" })
        val gathered = KnnSuggester().gather(portionsOf(rest, TaskPortions.MOST)) { false }
        val ref = gathered.reference
        val k = assertNotNull(fitted.report).chosen.getValue("k").toInt()
        val model = KnnModel(k, ref.layout, ref.ids, ref.vectors, ref.labels, gathered.total.share()).pack()
        val alone = answeredTask(texts, listOf(i), listOf("asked"))
        return assertNotNull(KnnSuggester().scoring(model, alone)).of(intArrayOf(0))[0]
    }

    /** The answers as the user gave them: of every work by its id, confirmed then rejected. */
    private val answers =
        listOf("w4" to 0, "w4" to 2, "w4" to 1, "w7" to 0, "w9" to 1).groupBy({ it.first }, { it.second })

    @Test
    fun `every value the user answered on is scored held out, by the nearest of the other works alone`() {
        val texts = answeredTexts()
        val all = texts.indices.toList()
        val task = answeredTask(texts, all, all.map { "w$it" })
        val fitted = fit(task)
        val held = assertNotNull(fitted.heldOut)
        // The answers by the works' ids, confirmed then rejected of each work, and no other pair
        assertEquals(answers, held.items.indices.groupBy({ held.items[it] }, { held.values[it] }))
        val score = held.items.indices.associate { (held.items[it] to held.values[it]) to held.scores[it] }
        // Each as a model of every other work scores it — on the scale of the scoring, a chance — within the half
        // precision the model keeps the works in
        score.forEach { (pair, s) ->
            val i = pair.first.removePrefix("w").toInt()
            val expected = asked(fitted, texts, all.filter { it != i }, i)[pair.second]
            assertEquals(expected, s, 2e-3f, "work $i, value ${pair.second}")
        }
        // The value 2 no other work has: the work's own answer is no witness of it — lower than of a work of its text
        // never fitted on, whose neighbours and shares have the work 4 among them
        val seen = asked(fitted, texts, all, 4)
        val own = score.getValue("w4" to 2)
        assertTrue(own < 0.05f && own < seen[2], "$own against ${seen[2]}")
        // Of a few works every one is a neighbour: the value of its direction likelier than the other's still
        assertTrue(score.getValue("w4" to 0) > 0.5f && score.getValue("w4" to 1) < 0.5f, score.toString())
        // A state unpacked holds nothing out
        assertNull(assertNotNull(KnnSuggester().unpack(fitted.pack(), task.valueSide())).heldOut)
    }

    @Test
    fun `the works answered on left out of those kept are held out in a walk of their own`() {
        val texts = answeredTexts()
        val all = texts.indices.toList()
        val task = answeredTask(texts, all, all.map { "w$it" })
        val walks = IntArray(1)
        val fitted = assertNotNull(KnnSuggester(null, room = 2).fit(portionsOf(task, 5, walks)))
        assertEquals(3, walks[0])
        val held = assertNotNull(fitted.heldOut)
        // Every answer held out, of the works kept and of the one left out alike
        assertEquals(answers, held.items.indices.groupBy({ held.items[it] }, { held.values[it] }))
        assertTrue(held.scores.all { it in 0f..1f }, held.scores.toList().toString())
        val kept = assertNotNull(KnnModel.read(fitted.model(task.valueSide()))).ids
        assertEquals(2, kept.size)
        assertTrue(kept.all { it in answers }, kept.toString())
        // Without one left out, two walks
        walks[0] = 0
        assertNotNull(KnnSuggester(null, room = 3).fit(portionsOf(task, 5, walks)))
        assertEquals(2, walks[0])
    }

    /**
     * Of [n] works, the first half of texts alone, the second of pictures alone, those of either of the value 0 near
     * one direction, of the value 1 near another: in portions of [n] / 2, one portion of a block the other lacks.
     */
    private fun twoKinds(n: Int = 80): SuggestionTask {
        val random = Random(23)
        val half = n / 2
        fun block(name: String, d: Int, from: Int): InputBlock {
            val centres = List(2) { FloatArray(d) { random.nextGaussian().toFloat() } }
            val vectors = Matrix(n, d)
            for (i in from until from + half) {
                val v = FloatArray(d) { centres[i % 2][it] + 0.3f * random.nextGaussian().toFloat() }
                vectors.held.put(v, 0, vectors.row(i), d)
            }
            return InputBlock.of(name, vectors, BooleanArray(n) { it in from until from + half })
        }
        return SuggestionTask(
            itemIds = List(n) { "w$it" },
            inputs = listOf(block("text:annotation", 16, 0), block("picture:cover", 8, half)),
            values = Matrix(2, 4),
            valueKeys = listOf("v0", "v1"),
            assigned = List(n) { intArrayOf(it % 2) },
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { IntArray(0) },
            context = List(n) { IntArray(0) },
            contextKeys = emptyList(),
        )
    }

    @Test
    fun `every block of input some portion has is read, the value side telling none`() {
        val task = twoKinds()
        val portions = portionsOf(task, task.itemCount / 2)
        val seen = ArrayList<List<String>>()
        portions.forEach { portion -> seen += portion.inputs.map { it.name } }
        assertEquals(listOf(listOf("text:annotation"), listOf("picture:cover")), seen)
        assertTrue(portions.values.inputs.isEmpty())
        val fitted = assertNotNull(KnnSuggester().fit(portions))
        val model = fitted.model(task.valueSide())
        // The layout of every block, in the order the portions showed them
        val layout = assertNotNull(KnnModel.read(model)).layout
        assertEquals(listOf("text:annotation" to 16, "picture:cover" to 8), layout)
        // Works of texts alone and works of pictures alone have the values of their nearest, held out and scored
        assertTrue(assertNotNull(fitted.report).loss < 0.3, "loss ${fitted.report?.loss}")
        val rows = intArrayOf(0, 1, 40, 41)
        val chances = scored(fitted, task).of(rows)
        rows.forEachIndexed { r, i ->
            assertTrue(chances[r][i % 2] > 0.8f && chances[r][1 - i % 2] < 0.2f, "w$i: ${chances[r].toList()}")
        }
        // A block the model does not know is not read; one the work lacks is nought
        val notes = InputBlock.of("text:notes", Matrix(task.itemCount, 4), BooleanArray(task.itemCount) { true })
        val more = task.withInputs(task.inputs + notes)
        val again = assertNotNull(KnnSuggester().scoring(model, more)).of(rows)
        rows.indices.forEach { r -> assertTrue(again[r].contentEquals(chances[r]), "w${rows[r]}") }
    }

    @Test
    fun `a value known only had is no chance of a work its nearest say nothing of, one known lacked too its share`() {
        // Of two works, the first said to have the value 0 and nothing else; the second to have 0 and lack 1
        val labels =
            Labels(
                2,
                floatArrayOf(0f, 0f),
                emptyList(),
                intArrayOf(-1, -1),
                arrayOf(intArrayOf(0), intArrayOf(0, 1)),
                arrayOf(floatArrayOf(1f), floatArrayOf(1f, 1f)),
                arrayOf(floatArrayOf(1f), floatArrayOf(1f, 0f)),
            )
        val share = doubleArrayOf(1.0, 0.5)
        // No neighbour of any likeness: the value known only had drawn towards none, the other towards its share
        val chances = labels.chances(intArrayOf(), floatArrayOf(), share, 5) { true }
        assertEquals(0f, chances[0])
        assertEquals(0.5f, chances[1])
    }
}
