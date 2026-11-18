package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Matrix
import java.lang.ref.WeakReference
import java.util.Random
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

// More than a few seconds: in CI alone — the local build's tests take a minute at most together (user, 2026-11-18)
@Tag("ci")
class BilinearSuggesterTest {
    /** The chances of the items of [task] by the model [fitted] made of it: the one way to them. */
    private fun scored(fitted: FittedSuggester, task: SuggestionTask) =
        assertNotNull(BilinearSuggester().scoring(fitted.model(task.valueSide()), task))

    /** The items of [task] in one portion, as the application hands them over ([Cut]). */
    private fun whole(task: SuggestionTask): TaskPortions = Cut(task, TaskPortions.MOST)

    private val d = 8

    /** A task: every item has a value when its text points the value's way, the value's name pointing so too. */
    private fun task(n: Int, wrong: Int = -1, random: Random = Random(7)): SuggestionTask {
        val directions = List(4) { FloatArray(d) { random.nextGaussian().toFloat() }.normalized() }
        val texts = Matrix(n, d)
        val assigned = ArrayList<IntArray>()
        repeat(n) { i ->
            val v = FloatArray(d) { random.nextGaussian().toFloat() }.normalized()
            texts.held.put(v, 0, texts.row(i), d)
            val has =
                directions.indices.filter { k ->
                    directions[k].indices.sumOf { (directions[k][it] * v[it]).toDouble() } > 0.3
                }
            // One item said to have what its text does not point to: the site's word alone
            assigned += if (i == wrong) intArrayOf(directions.indices.first { it !in has }) else has.toIntArray()
        }
        val names = Matrix(directions.size, d)
        directions.forEachIndexed { k, v -> names.held.put(v, 0, names.row(k), d) }
        return SuggestionTask(
            itemIds = List(n) { "item$it" },
            inputs = listOf(InputBlock.of("text:annotation", texts, BooleanArray(n) { true })),
            values = names,
            valueKeys = directions.indices.map { "v$it" },
            assigned = assigned,
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { IntArray(0) },
            context = List(n) { IntArray(0) },
            contextKeys = emptyList(),
        )
    }

    private fun FloatArray.normalized(): FloatArray {
        val l = kotlin.math.sqrt(sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(size) { this[it] / l }
    }

    @Test
    fun `a value goes with the texts that point its way, every item judged by a model that did not learn from it`() {
        val task = task(300, wrong = 5)
        val fitted = assertNotNull(BilinearSuggester().fit(whole(task)))
        val chances = scored(fitted, task).of(IntArray(task.itemCount) { it })
        var right = 0
        var all = 0
        for (i in 0 until task.itemCount) {
            if (i == 5) continue
            for (v in 0 until task.valueCount) {
                val has = v in task.assigned[i]
                if (has == chances[i][v] > 0.5f) right++
                all++
            }
        }
        assertTrue(right > 0.85 * all, "$right of $all right")
        // The value the site alone gave item 5 comes out unlikely: the item does not vouch for itself
        assertTrue(chances[5][task.assigned[5].single()] < 0.5f, "${chances[5].toList()}")
    }

    /** No work read: the weights are those kept with the scoring. */
    @Test
    fun `the scoring kept tells every value's weight over the works as a whole - the common above the rare`() {
        val random = Random(11)
        val n = 300
        val texts = Matrix(n, d)
        repeat(n) { i ->
            texts.held.put(FloatArray(d) { random.nextGaussian().toFloat() }.normalized(), 0, texts.row(i), d)
        }
        // Of the texts' say nothing: v0 of most works, v1 of a few, v2 of some; v3 of none that may
        // have it — no work may: of a pair the model never learns of, its score tells nothing
        val shares = doubleArrayOf(0.8, 0.05, 0.3)
        val values = shares.size + 1
        val task =
            SuggestionTask(
                itemIds = List(n) { "item$it" },
                inputs = listOf(InputBlock.of("text:annotation", texts, BooleanArray(n) { true })),
                values =
                    Matrix(values, d).also { names ->
                        (0 until values).forEach { k ->
                            names.held.put(
                                FloatArray(d) { random.nextGaussian().toFloat() }.normalized(),
                                0,
                                names.row(k),
                                d,
                            )
                        }
                    },
                valueKeys = (0 until values).map { "v$it" },
                assigned = List(n) { shares.indices.filter { random.nextDouble() < shares[it] }.toIntArray() },
                confirmed = List(n) { IntArray(0) },
                rejected = List(n) { IntArray(0) },
                context = List(n) { IntArray(0) },
                contextKeys = emptyList(),
                allowed = List(n) { IntArray(shares.size) { it } },
            )
        val suggester = BilinearSuggester()
        val fitted = assertNotNull(suggester.fit(whole(task)))
        val weights = assertNotNull(suggester.weights(fitted.model(task.valueSide())))
        assertEquals(values, weights.size)
        assertTrue(weights.take(shares.size).all { it > 0 && it < 1 }, weights.toList().toString())
        assertTrue(weights[0] > weights[2] && weights[2] > weights[1], weights.toList().toString())
        assertEquals(0.0, weights[3], weights.toList().toString())
        // Bytes not of its making: none
        assertEquals(null, suggester.weights(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `the scoring kept apart gives the items read later the chances of the whole task, without the values' names`() {
        val task = task(120, wrong = 5)
        val suggester = BilinearSuggester()
        val fitted = assertNotNull(suggester.fit(whole(task)))
        val kept = assertNotNull(fitted.model(task.valueSide()))
        // Some items alone, as a page reads them: their texts, the values by their keys, no names
        val rows = intArrayOf(3, 5, 40, 77)
        val texts = task.inputs.single().vectors.gather(rows)
        val alone =
            SuggestionTask(
                itemIds = rows.map { task.itemIds[it] },
                inputs = listOf(InputBlock("text:annotation", texts, IntArray(rows.size) { it })),
                values = Matrix(task.valueCount, 0),
                valueKeys = task.valueKeys,
                assigned = rows.map { task.assigned[it] },
                confirmed = List(rows.size) { IntArray(0) },
                rejected = List(rows.size) { IntArray(0) },
                context = List(rows.size) { IntArray(0) },
                contextKeys = emptyList(),
            )
        val whole = scored(fitted, task).of(rows)
        val read = assertNotNull(suggester.scoring(kept, alone)).of(IntArray(rows.size) { it })
        rows.indices.forEach { r ->
            task.valueKeys.indices.forEach { v ->
                assertEquals(whole[r][v], read[r][v], 1e-5f, "item ${rows[r]}, value $v")
            }
        }
        // Of other values the scoring is not: no chances made of it
        val other =
            SuggestionTask(
                alone.itemIds,
                alone.inputs,
                Matrix(2, 0),
                listOf("v0", "v9"),
                alone.assigned,
                alone.confirmed,
                alone.rejected,
                alone.context,
                alone.contextKeys,
            )
        assertEquals(null, suggester.scoring(kept, other))
    }

    @Test
    fun `a work the site gave no examples is judged by its text, the examples of the others taught it`() {
        val random = Random(3)
        val space = FloatArray(d) { if (it < d / 2) 1f else 0f }.normalized()
        val dragons = FloatArray(d) { if (it >= d / 2) 1f else 0f }.normalized()
        val n = 61
        val texts = Matrix(n, d)
        val assigned = ArrayList<IntArray>()
        val examples = ArrayList<IntArray>()
        repeat(n) { i ->
            val kind = if (i == n - 1) 0 else i % 2
            val base = if (kind == 0) space else dragons
            val v = FloatArray(d) { base[it] + 0.3f * random.nextGaussian().toFloat() }.normalized()
            texts.held.put(v, 0, texts.row(i), d)
            val own = if (kind == 0) intArrayOf(0, 1) else intArrayOf(2, 3)
            when {
                i == n - 1 -> {
                    assigned += IntArray(0)
                    examples += IntArray(0)
                }
                // Some works the site left untagged, tagged by the user
                i % 5 == 0 -> {
                    assigned += own
                    examples += IntArray(0)
                }
                else -> {
                    assigned += own
                    examples += own
                }
            }
        }
        val names = Matrix(4, d)
        val task =
            SuggestionTask(
                itemIds = List(n) { "w$it" },
                inputs = listOf(InputBlock.of("text:annotation", texts, BooleanArray(n) { true })),
                values = names,
                valueKeys = listOf("space", "ships", "dragons", "magic"),
                assigned = assigned,
                confirmed = List(n) { IntArray(0) },
                rejected = List(n) { IntArray(0) },
                context = List(n) { IntArray(0) },
                contextKeys = emptyList(),
                // A value the site lacks says little: the user added many and rejected none
                lackWeight = 1.0 / 14,
                examples = examples,
            )
        val fitted = assertNotNull(BilinearSuggester().fit(whole(task)))
        val lost = scored(fitted, task).of(intArrayOf(n - 1)).single()
        assertTrue(
            lost[0] > 0.5f && lost[1] > 0.5f && lost[2] < 0.5f && lost[3] < 0.5f,
            "a space work: ${lost.toList()}",
        )
    }

    /**
     * The site's catalogue of the application's test: two kinds, tags and pairings as the site writes them, a few works
     * tagged by the user.
     */
    @Test
    fun `a work with nothing but its text, among works the site tagged, is judged by its text`() {
        val words = 64
        val contextKeys =
            listOf(
                "tag\u0001space",
                "tag\u0001ships",
                "tag\u0001dragons",
                "tag\u0001magic",
                "pairing\u0001pc",
                "pairing\u0001kp",
            )
        val catalogue = siteCatalogue(words)
        val rows = catalogue.rows
        val assigned = catalogue.assigned
        val examples = catalogue.examples
        val context = catalogue.context
        val n = rows.size
        val texts = Matrix(n, words)
        rows.forEachIndexed { i, v -> texts.held.put(v, 0, texts.row(i), words) }
        // The values' names, encoded in the space of the texts: each one word of its kind
        val names = Matrix(4, words)
        listOf(1, 0, 0, 1).forEachIndexed { v, kind ->
            val name = FloatArray(words).also { it[if (kind == 0) v else words / 2 + v] = 1f }
            names.held.put(name, 0, names.row(v), words)
        }
        // The site's line of pairings, as a text, of the works that have one
        val lines = Matrix(n, words)
        val hasLine = BooleanArray(n) { i -> context[i].any { it >= 4 } }
        (0 until n)
            .filter { hasLine[it] }
            .forEach { i ->
                val v = FloatArray(words).also { it[if (4 in context[i]) 3 else words - 3] = 1f }
                lines.held.put(v, 0, lines.row(i), words)
            }
        val task =
            SuggestionTask(
                itemIds = List(n) { "w$it" },
                inputs =
                    listOf(
                        InputBlock.of("text:annotation", texts, BooleanArray(n) { true }),
                        InputBlock.of("text:pairings", lines, hasLine),
                    ),
                values = names,
                valueKeys = listOf("dragons", "magic", "ships", "space"),
                assigned = assigned,
                confirmed = List(n) { IntArray(0) },
                rejected = List(n) { IntArray(0) },
                context = context,
                contextKeys = contextKeys,
                lackWeight = 1.0 / 14,
                examples = examples,
            )
        val fitted = assertNotNull(BilinearSuggester().fit(whole(task)))
        val lost = scored(fitted, task).of(intArrayOf(n - 1)).single()
        assertTrue(
            lost[3] > 0.5f && lost[2] > 0.5f && lost[0] < 0.5f && lost[1] < 0.5f,
            "a space work by its text: ${lost.toList()}",
        )
    }

    /** The works of a site's catalogue: their texts, their values, the site's examples and their other facets. */
    private class Catalogue(
        val rows: List<FloatArray>,
        val assigned: List<IntArray>,
        val examples: List<IntArray>,
        val context: List<IntArray>,
    )

    /**
     * Forty works of two kinds, texts of [words] words each: the values of the space kind 3 and 2, of the dragons' 0
     * and 1, given by the site in full, in part or not at all, some by the user; tags and pairings in their context as
     * the site writes them. The last work with its text alone.
     */
    private fun siteCatalogue(words: Int): Catalogue {
        val random = Random(11)
        fun text(kind: Int) =
            FloatArray(words) { q ->
                    if ((q < words / 2) == (kind == 0)) 1f + 0.3f * random.nextGaussian().toFloat()
                    else 0.1f * random.nextFloat()
                }
                .normalized()
        val rows = ArrayList<FloatArray>()
        val assigned = ArrayList<IntArray>()
        val examples = ArrayList<IntArray>()
        val context = ArrayList<IntArray>()
        repeat(20) { k ->
            listOf(0, 1).forEach { kind ->
                rows += text(kind)
                val own = if (kind == 0) intArrayOf(3, 2) else intArrayOf(0, 1)
                val site =
                    when {
                        k % 5 == 0 -> IntArray(0)
                        k % 3 == 0 -> intArrayOf(own[0])
                        else -> own
                    }
                examples += site
                val user = if (k % 5 == 0 && k > 0) own else IntArray(0)
                assigned += (site + user).distinct().toIntArray()
                val tagContext = site.map { v -> mapOf(3 to 0, 2 to 1, 0 to 2, 1 to 3).getValue(v) }
                val pairing = if (k % 4 == 0) emptyList() else listOf(if (kind == 0) 4 else 5)
                context += (tagContext + pairing).toIntArray()
            }
        }
        // The work with its text alone
        rows += text(0)
        assigned += IntArray(0)
        examples += IntArray(0)
        context += IntArray(0)
        return Catalogue(rows, assigned, examples, context)
    }

    @Test
    fun `kept and read again, the model judges the same`() {
        val task = task(120)
        val suggester = BilinearSuggester()
        val fitted = assertNotNull(suggester.fit(whole(task)))
        val again = assertNotNull(suggester.unpack(fitted.pack(), task))
        val rows = IntArray(task.itemCount) { it }
        val a = scored(fitted, task).of(rows)
        val b = scored(again, task).of(rows)
        rows.forEach { i -> a[i].indices.forEach { v -> assertEquals(a[i][v], b[i][v], 1e-6f) } }
    }

    @Test
    fun `gone on from the last state with more items, the model judges as one fitted from scratch`() {
        val small = task(240)
        val suggester = BilinearSuggester()
        val last = assertNotNull(suggester.fit(whole(small)))
        // Sixty items more, the same values: the refit starts where the last one stopped
        val task = task(300)
        val again = assertNotNull(suggester.fit(whole(task), last))
        val scratch = assertNotNull(suggester.fit(whole(task)))
        val rows = IntArray(task.itemCount) { it }
        val a = scored(again, task).of(rows)
        val b = scored(scratch, task).of(rows)
        // The two agree on which side of a half every pair is
        var agree = 0
        var all = 0
        rows.forEach { i ->
            a[i].indices.forEach { v ->
                if ((a[i][v] > 0.5f) == (b[i][v] > 0.5f)) agree++
                all++
            }
        }
        assertTrue(agree >= all * 0.95, "$agree of $all pairs agree")
    }

    @Test
    fun `a block of the items the last state does not know, the refit sees them anew and reads it`() {
        val small = task(240)
        val suggester = BilinearSuggester()
        val last = assertNotNull(suggester.fit(whole(small)))
        // The items gain a block of input: its scale is of the items, seen in a walk of its own
        val task = task(300)
        val notes = task.inputs.single().let { InputBlock("text:notes", it.vectors, it.rows) }
        val gained =
            SuggestionTask(
                task.itemIds,
                task.inputs + notes,
                task.values,
                task.valueKeys,
                task.assigned,
                task.confirmed,
                task.rejected,
                task.context,
                task.contextKeys,
            )
        val portions = Cut(gained, PORTION)
        val again = assertNotNull(suggester.fit(portions, last)) as FittedBilinear
        assertEquals(listOf("text:annotation", "text:notes"), again.design.blocks.map { it.name })
        assertTrue(portions.walks > WALKS_GONE_ON, "${portions.walks} walks, the items seen anew")
    }

    /**
     * The [task] with the user's answers: of every seventh item its values confirmed, of some others a value it has not
     * rejected; the site's examples of every fourth item — its values, some of them answered on too.
     */
    private fun answered(task: SuggestionTask): SuggestionTask =
        SuggestionTask(
            itemIds = task.itemIds,
            inputs = task.inputs,
            values = task.values,
            valueKeys = task.valueKeys,
            assigned = task.assigned,
            confirmed = List(task.itemCount) { i -> if (i % 7 == 0) task.assigned[i] else IntArray(0) },
            rejected =
                List(task.itemCount) { i ->
                    if (i % 11 != 3) IntArray(0)
                    else (0 until task.valueCount).filter { it !in task.assigned[i] }.take(2).toIntArray()
                },
            context = task.context,
            contextKeys = task.contextKeys,
            examples = List(task.itemCount) { i -> if (i % 4 == 0) task.assigned[i] else IntArray(0) },
        )

    @Test
    fun `the values the user answered on are scored by the model of the fold that held their item out`() =
        heldOutAsKept(BilinearSuggester(), SCORE_32)

    @Test
    fun `of a task in several portions, the values answered on are scored as the model kept scores them`() =
        heldOutAsKept(BilinearSuggester(), SCORE_32, PORTION)

    /**
     * Of a fitting by [suggester] of a task with the user's answers, in portions of [portion] items, the scores handed
     * over ([FittedSuggester.heldOut]): every pair answered on once, by its item's id, each scored as the scoring kept
     * scores its item — by the model of the fold that held it out — the difference's length within [tolerance] of the
     * scores'; of a refit gone on from the last state too, none of a state unpacked.
     */
    private fun heldOutAsKept(suggester: BilinearSuggester, tolerance: Float, portion: Int = TaskPortions.MOST) {
        val task = answered(task(300, wrong = 5))
        val expected =
            (0 until task.itemCount).flatMap { i ->
                (task.confirmed[i].asList() + task.rejected[i].asList()).distinct().sorted().map {
                    task.itemIds[i] to it
                }
            }
        assertTrue(expected.size > 40, "${expected.size} answered pairs")
        val fitted = assertNotNull(suggester.fit(Cut(task, portion)))
        val again = assertNotNull(suggester.fit(Cut(task, portion), suggester.unpack(fitted.pack(), task.valueSide())))
        for ((what, fit) in listOf("from scratch" to fitted, "from the last state" to again)) {
            val held = assertNotNull(fit.heldOut, what)
            assertEquals(expected, held.items.indices.map { held.items[it] to held.values[it] }, what)
            val rows = held.items.map { task.itemIds.indexOf(it) }.toIntArray()
            val kept = scored(fit, task).scores(rows, held.values.map { intArrayOf(it) }).map { it.single() }
            // Within the digits as a whole, not score by score: a score that is the sum of terms that all but cancel
            // keeps few of the digits of its terms
            var diff = 0.0
            var own = 0.0
            held.scores.forEachIndexed { k, score ->
                diff += (kept[k] - score).toDouble().let { it * it }
                own += kept[k].toDouble().let { it * it }
            }
            val relative = sqrt(diff / own)
            assertTrue(relative < tolerance, "$what: the scores held out differ from those kept by $relative")
        }
        assertNull(assertNotNull(suggester.unpack(fitted.pack(), task)).heldOut)
    }

    @Test
    fun `nothing to learn from, nothing is fitted`() {
        val task = task(20)
        val empty =
            SuggestionTask(
                task.itemIds,
                task.inputs,
                task.values,
                task.valueKeys,
                List(20) { IntArray(0) },
                task.confirmed,
                task.rejected,
                task.context,
                task.contextKeys,
            )
        assertEquals(null, BilinearSuggester().fit(whole(empty)))
    }

    @Test
    fun `told to stop, the fitting is given up`() {
        val task = task(120)
        assertFailsWith<CancellationException> { BilinearSuggester().fit(whole(task), stop = { true }) }
    }

    @Test
    fun `a task cut into several portions fits to a model that judges as one fitted of a single portion`() {
        val task = task(400, wrong = 5)
        val suggester = BilinearSuggester()
        val whole = assertNotNull(suggester.fit(whole(task)))
        val cut = assertNotNull(suggester.fit(Cut(task, PORTION)))
        assertAlike(scored(whole, task), scored(cut, task), task, "a portion and portions of $PORTION")
    }

    /**
     * The chances of every item of [task] by [a] and by [b] alike: of nearly every pair on the same side of a half, and
     * near on the whole — two descents of other orders of their steps reach the same model, not the same digits.
     */
    private fun assertAlike(a: SuggestionScores, b: SuggestionScores, task: SuggestionTask, what: String) {
        val rows = IntArray(task.itemCount) { it }
        val x = a.of(rows)
        val y = b.of(rows)
        var agree = 0
        var all = 0
        var apart = 0.0
        rows.forEach { i ->
            x[i].indices.forEach { v ->
                if ((x[i][v] > 0.5f) == (y[i][v] > 0.5f)) agree++
                apart += abs(x[i][v] - y[i][v])
                all++
            }
        }
        assertTrue(agree >= all * AGREE, "$what: $agree of $all pairs agree")
        assertTrue(apart / all < APART, "$what: the chances apart by ${apart / all} on the whole")
    }

    @Test
    fun `the fitting holds one portion at a time, and walks them a few times`() {
        val task = task(400, wrong = 5)
        val suggester = BilinearSuggester()
        val scratch = Cut(task, PORTION, watched = true)
        val fitted = assertNotNull(suggester.fit(scratch))
        assertEquals(1, scratch.mostAlive, "portions held at once")
        assertTrue(scratch.walks <= WALKS_FROM_SCRATCH, "${scratch.walks} walks from scratch")
        val goneOn = Cut(task, PORTION, watched = true)
        assertNotNull(suggester.fit(goneOn, suggester.unpack(fitted.pack(), task.valueSide())))
        assertEquals(1, goneOn.mostAlive, "portions held at once, gone on from the last state")
        assertEquals(WALKS_GONE_ON, goneOn.walks, "walks gone on from the last state")
    }

    /**
     * The items of [task] in portions of [size] items each, made anew at every walk, as the application reads them —
     * the walks counted. [watched]: every portion handed over watched, and how many of them were still held at most
     * when the next was handed over told ([mostAlive]) — by the garbage collected, a portion held by nothing gone.
     */
    private class Cut(private val task: SuggestionTask, private val size: Int, private val watched: Boolean = false) :
        TaskPortions {
        override val values: SuggestionTask = task.valueSide()
        override val items: Int = task.itemCount

        var walks = 0
            private set

        var mostAlive = 0
            private set

        /** Of every portion handed over, the portion and its rows of the input: held by nothing, both gone. */
        private val handed = ArrayList<List<WeakReference<Any>>>()

        override fun forEach(action: (SuggestionTask) -> Unit) {
            walks++
            var from = 0
            while (from < task.itemCount) {
                val to = minOf(from + size, task.itemCount)
                if (watched) mostAlive = maxOf(mostAlive, alive() + 1)
                action(portion(from, to))
                from = to
            }
        }

        /** The portion of the items [from] till [to], watched when they are. */
        private fun portion(from: Int, to: Int): SuggestionTask {
            val portion = task.ofItems(IntArray(to - from) { from + it })
            if (watched) handed += listOf(WeakReference(portion), WeakReference(portion.inputs.first().vectors))
            return portion
        }

        /** How many of the portions handed over are held still, the garbage collected first. */
        private fun alive(): Int {
            repeat(COLLECTIONS) {
                System.gc()
                if (handed.none { refs -> refs.any { it.get() != null } }) return 0
                Thread.sleep(COLLECTION_PAUSE)
            }
            return handed.count { refs -> refs.any { it.get() != null } }
        }
    }

    private companion object {
        /**
         * The value side of the task, of no item: what a model is made of beside what was fitted. As the application
         * hands it over: of no input block — the blocks are the portions' items'.
         */
        fun SuggestionTask.valueSide(): SuggestionTask =
            ofItems(IntArray(0)).let { side ->
                SuggestionTask(
                    side.itemIds,
                    emptyList(),
                    side.values,
                    side.valueKeys,
                    side.assigned,
                    side.confirmed,
                    side.rejected,
                    side.context,
                    side.contextKeys,
                    side.allowed,
                    side.groupings,
                    side.lackWeight,
                    side.examples,
                    side.hints,
                    side.hintTexts,
                    side.drafts,
                    side.draftWeight,
                    side.barred,
                )
            }

        /** The items of a portion of the tests cut into several. */
        const val PORTION = 70

        /** The share of the pairs two fittings of one task agree on the side of a half of. */
        const val AGREE = 0.95

        /** How far two fittings' chances of a pair are apart, on the whole. */
        const val APART = 0.05

        /**
         * The walks of a fitting from scratch at most: seeing the items, descending, scoring them by the model kept.
         */
        const val WALKS_FROM_SCRATCH = 8

        /** The walks of a fitting gone on from the last state: descending twice, scoring once. */
        const val WALKS_GONE_ON = 3

        /** The collections of the garbage a portion held by nothing is waited for, and the pause between them. */
        const val COLLECTIONS = 10
        const val COLLECTION_PAUSE = 20L

        /** A score made in single precision in two orders of the sums: its last digits. */
        const val SCORE_32 = 1e-4f
    }
}
