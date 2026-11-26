package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class BilinearTest {

    /** A small task with every kind of input: two text blocks, a context, examples, names of the values as hints. */
    private fun task(): SuggestionTask {
        val random = Random(5)
        val n = 12
        val d = 6
        val m = 4
        val names = randomRows(random, m, d)
        return SuggestionTask(
            itemIds = LongArray(n) { it.toLong() },
            inputs = listOf(block(random, "text:title", n, d, 5), block(random, "text:annotation", n, d, 3)),
            values = names,
            valueKeys = List(m) { "v$it" },
            assigned = List(n) { i -> if (i % 4 == 0) IntArray(0) else intArrayOf(i % m) },
            confirmed = List(n) { i -> if (i % 5 == 2) intArrayOf(i % m) else IntArray(0) },
            rejected = List(n) { i -> if (i == 3) intArrayOf(1) else if (i == 10) intArrayOf(0) else IntArray(0) },
            context = List(n) { i -> intArrayOf(i % 2) },
            contextKeys = listOf("f\u0001a", "f\u0001b"),
            lackWeight = 0.5,
            examples = List(n) { i -> if (i % 2 == 0) intArrayOf(i % m) else IntArray(0) },
            hints = IntArray(m) { it }.let { all -> List(6) { k -> NameHint(k % m, all) } },
            hintTexts = randomRows(random, 6, d),
            // Values some items may have, one array for them all, one of them barred to an item; a draft not confirmed
            allowed = intArrayOf(2, 0, 1).let { shared -> List(n) { i -> if (i % 3 == 1) shared else null } },
            barred = List(n) { i -> if (i == 7) intArrayOf(0) else IntArray(0) },
            drafts = List(n) { i -> if (i == 5) intArrayOf(i % m) else IntArray(0) },
            draftWeight = 0.5,
        )
    }

    /**
     * A task whose pairs that weigh are few of the items × the values: two items of values of their own, one of three
     * values it may have, one of any — the pairs listed ([ListedPairs]), not swept.
     */
    private fun sparseTask(): SuggestionTask {
        val random = Random(9)
        val n = 12
        val d = 6
        val m = 40
        return SuggestionTask(
            itemIds = LongArray(n) { it.toLong() },
            inputs = listOf(block(random, "text:title", n, d, 5), block(random, "text:annotation", n, d, 3)),
            values = randomRows(random, m, d),
            valueKeys = List(m) { "v$it" },
            assigned = List(n) { i -> if (i == 1) intArrayOf(1) else if (i == 2) intArrayOf(2, 5) else IntArray(0) },
            confirmed = List(n) { i -> if (i == 2) intArrayOf(5) else IntArray(0) },
            rejected = List(n) { i -> if (i == 3) intArrayOf(1) else if (i == 8) intArrayOf(9) else IntArray(0) },
            context = List(n) { i -> intArrayOf(i % 2) },
            contextKeys = listOf("f\u0001a", "f\u0001b"),
            lackWeight = 0.5,
            examples = List(n) { i -> if (i == 2) intArrayOf(7) else IntArray(0) },
            hints = IntArray(m) { it }.let { all -> List(6) { k -> NameHint(k, all) } },
            hintTexts = randomRows(random, 6, d),
            allowed = intArrayOf(2, 0, 1).let { shared -> List(n) { i -> if (i == 1) shared else null } },
        )
    }

    /** [rows] rows of [d] numbers drawn from [random]. */
    private fun randomRows(random: Random, rows: Int, d: Int) =
        Matrix(rows, d).also { v ->
            for (j in 0 until rows) v.held.put(FloatArray(d) { random.nextGaussian().toFloat() }, 0, v.row(j), d)
        }

    /** The text block [name] of [n] items, [d] numbers each drawn from [random]: every [every]th item without it. */
    private fun block(random: Random, name: String, n: Int, d: Int, every: Int) =
        Matrix(n, d).let { b ->
            val present = BooleanArray(n) { it % every != 0 }
            for (i in 0 until n) if (present[i])
                b.held.put(FloatArray(d) { random.nextGaussian().toFloat() }, 0, b.row(i), d)
            InputBlock.of(name, b, present)
        }

    @Test
    fun `the pairs not kept one by one are those laid out whole`() {
        val task = task()
        val pairs = SuggestionPairs(task)
        val m = task.valueCount
        for (i in 0 until task.itemCount) {
            // Laid out whole: a row of every value's weight and label
            val weight = FloatArray(m)
            val label = FloatArray(m)
            if (task.assigned[i].isNotEmpty()) {
                val allowed = task.allowed[i]?.toSet()
                for (v in 0 until m) if ((allowed == null || v in allowed) && v !in task.barred[i])
                    weight[v] = task.lackWeight.toFloat()
            }
            task.rejected[i].forEach { weight[it] = 1f }
            task.assigned[i].forEach {
                weight[it] = 1f
                label[it] = 1f
            }
            task.drafts[i].forEach {
                if (label[it] > 0f && it !in task.confirmed[i]) weight[it] = task.draftWeight.toFloat()
            }
            val expected = (0 until m).filter { weight[it] != 0f }.map { Triple(it, weight[it], label[it]) }
            val read = ArrayList<Triple<Int, Float, Float>>()
            pairs.forEach(i) { v, w, y -> read += Triple(v, w, y) }
            assertEquals(expected, read, "item $i")
            assertEquals(expected.size, pairs.count[i], "item $i")
            assertEquals(expected.sumOf { it.second.toDouble() }.toFloat(), pairs.rowWeight[i], "item $i")
        }
    }

    @Test
    fun `every model in one product has the loss and the gradient it has alone, without names`() {
        val t = task()
        val task =
            SuggestionTask(
                t.itemIds,
                t.inputs,
                t.values,
                t.valueKeys,
                t.assigned,
                t.confirmed,
                t.rejected,
                t.context,
                t.contextKeys,
                t.allowed,
                t.groupings,
                t.lackWeight,
                t.examples,
                emptyList(),
                Matrix(0, 0),
                t.drafts,
                t.draftWeight,
            )
        compareTogether(task)
    }

    @Test fun `every model in one product has the loss and the gradient it has alone`() = compareTogether(task())

    @Test
    fun `every model in one product on the card has the loss and the gradient it has alone`() {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val cublas = Cublas.open(dir!!)
        assumeTrue(cublas != null, "no cuBLAS to run on")
        // The items' features' products on the tensor cores, in brain floating point: within its digits, piece by
        // piece of the gradient
        compareTogether(task(), cublas, sixteenBits = true)
        // And of the rank the application fits by: its kernels compiled with the rank known
        compareTogether(task(), cublas, rank = 16, sixteenBits = true)
        // And of the pairs that weigh listed, not swept
        val sparse = sparseTask()
        assertTrue(ListedPairs.of(Problem(sparse, Design.of(sparse), BilinearSuggester.FOLDS)) != null, "not listed")
        compareTogether(sparse, cublas, sixteenBits = true)
        compareTogether(sparse, cublas, rank = 16, sixteenBits = true)
    }

    @Test
    fun `the held-out loss of every model on the card is the processor's`() {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val cublas = Cublas.open(dir!!)
        assumeTrue(cublas != null, "no cuBLAS to run on")
        heldOutTogether(task(), cublas, rank = 3)
        heldOutTogether(task(), cublas, rank = 16)
        heldOutTogether(sparseTask(), cublas, rank = 3)
        heldOutTogether(sparseTask(), cublas, rank = 16)
    }

    @Test fun `the held-out loss of every model in one product is the one it has alone`() = heldOutTogether(task())

    /**
     * Of models at random points, the held-out loss [Ensemble.heldOut] makes — on the [card] when given — against its
     * measure: on the card, the processor's of the same coordinates (the items' features' products in brain floating
     * point there, which the card's pairs and the processor's read alike); without one, the one each makes alone
     * ([Bilinear.heldOutLoss]). The same pairs weighed alike, the losses the same but for the order of the sums.
     */
    private fun heldOutTogether(task: SuggestionTask, card: Cublas? = null, rank: Int = 3) {
        val problem = Problem(task, Design.of(task), BilinearSuggester.FOLDS)
        val lambdas = floatArrayOf(0.1f, 0.01f, 0.001f, 0.01f, 0.1f)
        val folds = intArrayOf(0, 1, 2, 3, 4)
        val alone = Bilinear(problem, rank)
        val random = Random(23)
        val thetas = List(lambdas.size) { FloatArray(alone.size) { (random.nextGaussian() * 0.3).toFloat() } }
        fun helds() = List(lambdas.size) { Bilinear.Held() }
        val together = helds()
        val unseenTogether = helds()
        val measure = helds()
        val unseenMeasure = helds()
        val point = EnsemblePoint(lambdas.size, rank, problem.design.itemWidth, problem.design.valueWidth, problem.m)
        thetas.forEachIndexed { k, theta -> point.put(k, theta) }
        Ensemble(problem, rank, lambdas, folds, card, point).use { ensemble ->
            ensemble.heldOut(together, unseenTogether, problem::unseenOutside)
            if (card != null) {
                assertTrue(ensemble.pairsOnCardUsed, "the pairs not on the card")
                ensemble.heldOut(measure, unseenMeasure, problem::unseenOutside, pairsThere = false)
            }
        }
        if (card == null)
            thetas.forEachIndexed { k, theta -> alone.heldOutLoss(theta, folds[k], measure[k], unseenMeasure[k]) }
        for (k in thetas.indices) {
            listOf("every pair" to (measure[k] to together[k]), "the unseen" to (unseenMeasure[k] to unseenTogether[k]))
                .forEach { (what, held) ->
                    val (a, b) = held
                    assertTrue(
                        abs(a.weight - b.weight) <= 1e-4 * a.weight,
                        "model $k, $what: weight ${a.weight}, ${b.weight}",
                    )
                    if (a.weight > 0)
                        assertTrue(
                            abs(a.mean - b.mean) <= 1e-4 * a.mean,
                            "model $k, $what: loss ${a.mean} measured, ${b.mean} together",
                        )
                }
        }
    }

    private fun compareTogether(
        task: SuggestionTask,
        card: Cublas? = null,
        rank: Int = 3,
        sixteenBits: Boolean = false,
    ) {
        val problem = Problem(task, Design.of(task), BilinearSuggester.FOLDS)
        val lambdas = floatArrayOf(0.1f, 0.01f, 0.001f, 0.01f)
        val folds = intArrayOf(0, 1, 2, 4)
        val ensemble = Ensemble(problem, rank, lambdas, folds, card)
        if (card != null)
            assertTrue(ensemble.featuresOnCard && ensemble.pairsOnCardUsed, "the features or the pairs not on the card")
        val alone = Bilinear(problem, rank)
        val random = Random(17)
        val thetas = List(lambdas.size) { FloatArray(alone.size) { (random.nextGaussian() * 0.3).toFloat() } }
        val (losses, gradients) = ensemble.use { it.lossesAndGradients(thetas) }
        thetas.forEachIndexed { k, theta ->
            val grad = FloatArray(alone.size)
            val loss = alone.lossAndGradient(theta, grad, lambdas[k]) { problem.foldOf[it] != folds[k] }
            if (sixteenBits) {
                assertTrue(
                    abs(loss - losses[k]) < LOSS_16 * abs(loss),
                    "model $k: loss $loss alone, ${losses[k]} together",
                )
                sixteenBitPieces(problem, rank, k, grad, gradients[k])
                return@forEachIndexed
            }
            assertTrue(abs(loss - losses[k]) < 1e-4 * abs(loss), "model $k: loss $loss alone, ${losses[k]} together")
            val at = grad.indices.maxBy { abs(grad[it] - gradients[k][it]) / max(1e-4f, abs(grad[it])) }
            val worst = abs(grad[at] - gradients[k][at]) / max(1e-4f, abs(grad[at]))
            val dx = problem.design.itemWidth * rank
            val dz = problem.design.valueWidth * rank
            val part =
                when {
                    at < dx -> "A"
                    at < dx + dz -> "B"
                    at < dx + dz + problem.m * rank -> "E"
                    at < dx + dz + problem.m * rank + problem.m -> "bias"
                    else -> "pair"
                }
            assertTrue(
                worst < 1e-3,
                "model $k: the worst relative difference of the gradient $worst, at $at of $part: " +
                    "${grad[at]} alone, ${gradients[k][at]} together",
            )
        }
    }

    /**
     * The gradient [together] of model [k] within the digits of brain floating point of the one [alone]: of every piece
     * — A, B, E, the biases, the pair's own weights — the difference's length a small part of the piece's own. Not
     * number by number: a slope that is the sum of terms that all but cancel keeps few of the digits of its terms.
     */
    private fun sixteenBitPieces(problem: Problem, rank: Int, k: Int, alone: FloatArray, together: FloatArray) {
        val sizes =
            listOf(
                "A" to problem.design.itemWidth * rank,
                "B" to problem.design.valueWidth * rank,
                "E" to problem.m * rank,
                "bias" to problem.m,
                "pair" to Bilinear.PAIR,
            )
        var from = 0
        sizes.forEach { (piece, size) ->
            var diff = 0.0
            var own = 0.0
            for (at in from until from + size) {
                diff += (alone[at] - together[at]).toDouble().let { it * it }
                own += alone[at].toDouble().let { it * it }
            }
            val relative = sqrt(diff) / max(sqrt(own), TINY)
            assertTrue(relative < GRADIENT_16, "model $k: the gradient of $piece differs by $relative of its length")
            from += size
        }
    }

    @Test
    fun `the descent of a portion comes down to where the loss of every item has no slope`() =
        descended(TaskPortions.of(task()), Plan(WALKS, STEPS, RATE), FLATTER)

    @Test
    fun `the descent over several portions, the names a share of them each, comes down the loss of every item`() {
        val task = task()
        // A step a portion of every walk, the portions taking turns
        val portions =
            object : TaskPortions {
                override val values = task.ofItems(IntArray(0))
                override val items = task.itemCount

                override fun forEach(action: (SuggestionTask) -> Unit) =
                    (0 until task.itemCount).chunked(PORTION).forEach { action(task.ofItems(it.toIntArray())) }
            }
        descended(portions, Plan(WALKS * STEPS / 3, 3, RATE), FLATTER_IN_PORTIONS)
    }

    /**
     * Two models of the task of [portions] descended by the [plan]: the slope of the loss of every item and every name
     * at their points [flatter] times flatter at the end than at the start — the loss of each portion's own items, of
     * its own share of the names, a mean over its pairs, comes down to where the whole's has no slope, or near; their
     * held-out losses made on the last walk.
     */
    private fun descended(portions: TaskPortions, plan: Plan, flatter: Int) {
        val task = task()
        val design = Design.of(task)
        val rank = 3
        val lambdas = floatArrayOf(0.1f, 0.01f)
        val folds = intArrayOf(1, 3)
        val problem = Problem(task, design, BilinearSuggester.FOLDS)
        val layout = ModelLayout(design, rank)
        val starts = List(lambdas.size) { layout.start(it + 1L, BooleanArray(problem.m) { true }, 0f) }
        val alone = Bilinear(problem, rank)
        fun slope(theta: FloatArray, k: Int): Double {
            val grad = FloatArray(alone.size)
            alone.lossAndGradient(theta, grad, lambdas[k]) { problem.foldOf[it] != folds[k] }
            return sqrt(grad.sumOf { it.toDouble() * it })
        }
        val descent = Descent(portions, design, rank, lambdas, folds, null, plan) { false }
        val held = List(lambdas.size) { Bilinear.Held() }
        descent.run(starts, held, List(lambdas.size) { Bilinear.Held() })
        lambdas.indices.forEach { k ->
            val before = slope(starts[k], k)
            val after = slope(descent.point.take(k), k)
            assertTrue(after < before / flatter, "model $k: the slope $before at the start, $after at the end")
            assertTrue(held[k].weight > 0 && held[k].mean.isFinite(), "model $k: held out ${held[k].mean}")
        }
    }

    @Test
    fun `the features' products are those of the features laid out whole`() {
        val task = task()
        val design = Design.of(task)
        val width = design.itemWidth
        val rank = 3
        val random = Random(13)
        val x = design.features(task)
        val n = task.itemCount
        val dense = laidOutWhole(task, design)
        fun close(expected: FloatArray, actual: Floats, what: String) =
            expected.indices.forEach {
                assertTrue(
                    abs(expected[it] - actual[it]) < 1e-4f * max(1f, abs(expected[it])),
                    "$what at $it: ${expected[it]}, made ${actual[it]}",
                )
            }
        val a = FloatArray(width * rank) { random.nextGaussian().toFloat() }
        val du = FloatArray(n * rank) { random.nextGaussian().toFloat() }
        val u = Floats(n * rank).also { x.times(Floats.of(a), rank, it, x.scratchOf(rank)) }
        close(
            FloatArray(n * rank) { k ->
                (0 until width).sumOf { c -> (dense[k / rank][c] * a[c * rank + k % rank]).toDouble() }.toFloat()
            },
            u,
            "X A",
        )
        val da = Floats(width * rank).also { x.transposedTimes(Floats.of(du), rank, it, x.scratchOf(rank)) }
        close(
            FloatArray(width * rank) { k ->
                (0 until n).sumOf { i -> (dense[i][k / rank] * du[i * rank + k % rank]).toDouble() }.toFloat()
            },
            da,
            "Xᵀ dU",
        )
        // Of two models one under another: Aᵀ and dUᵀ
        val k = 2 * rank
        val aT = FloatArray(k * width) { random.nextGaussian().toFloat() }
        val duT = FloatArray(k * n) { random.nextGaussian().toFloat() }
        val uT = Floats(k * n).also { x.transposedCoordinates(Floats.of(aT), k, it, x.scratchOf(k)) }
        close(
            FloatArray(k * n) { t ->
                (0 until width).sumOf { c -> (aT[t / n * width + c] * dense[t % n][c]).toDouble() }.toFloat()
            },
            uT,
            "Aᵀ Xᵀ",
        )
        val gA = Floats(k * width).also { x.transposedGradient(Floats.of(duT), k, it, x.scratchOf(k)) }
        close(
            FloatArray(k * width) { t ->
                (0 until n).sumOf { i -> (duT[t / width * n + i] * dense[i][t % width]).toDouble() }.toFloat()
            },
            gA,
            "dUᵀ X",
        )
    }

    /**
     * The features of every item of [task] laid out whole: the constant, every block's mark and its vector times its
     * scale, the values of the context.
     */
    private fun laidOutWhole(task: SuggestionTask, design: Design): Array<FloatArray> {
        val n = task.itemCount
        val base = 1 + design.blocks.sumOf { 1 + it.dim }
        return Array(n) { i ->
            FloatArray(design.itemWidth).also { row ->
                row[0] = 1f
                design.blocks.forEachIndexed { k, b ->
                    val block = task.inputs.first { it.name == b.name }
                    val slot = block.slots(n)[i]
                    if (slot < 0) return@forEachIndexed
                    row[design.blockOffsets[k]] = 1f
                    for (q in 0 until b.dim) row[design.blockOffsets[k] + 1 + q] = block.vectors[slot, q] * b.scale
                }
                task.context[i].forEach { row[base + design.contextKeys.indexOf(task.contextKeys[it])] = 1f }
            }
        }
    }

    @Test
    fun `the gradient is the loss's own, the names' part with it`() {
        val task = task()
        val problem = Problem(task, Design.of(task), BilinearSuggester.FOLDS)
        val model = Bilinear(problem, 3)
        val random = Random(9)
        val theta = FloatArray(model.size) { (random.nextGaussian() * 0.3).toFloat() }
        val grad = FloatArray(model.size)
        val trains: (Int) -> Boolean = { problem.foldOf[it] != 0 }
        model.lossAndGradient(theta, grad, 0.01f, trains)
        val scratch = FloatArray(model.size)
        var worst = 0.0
        for (k in theta.indices step max(1, theta.size / 200)) {
            // In single precision a central difference needs a step far above the float's resolution of the loss
            val eps = 1e-2f
            val plus = theta.copyOf().also { it[k] += eps }
            val minus = theta.copyOf().also { it[k] -= eps }
            val numeric =
                (model.lossAndGradient(plus, scratch, 0.01f, trains) -
                        model.lossAndGradient(minus, scratch, 0.01f, trains))
                    .toDouble() / (2 * eps)
            worst = max(worst, abs(numeric - grad[k]) / max(1e-3, abs(numeric) + abs(grad[k])))
        }
        assertTrue(worst < 0.02, "the worst relative error $worst")
    }

    private companion object {
        /**
         * The digits of brain floating point (a fraction of 8 bits, rounded: half of 2⁻⁸ an input), a product of two
         * inputs rounded twice: of a loss summed of the pairs, its relative part; of a piece of the gradient, the
         * relative length of its difference — with room, the sums' errors not all of a sign.
         */
        const val LOSS_16 = 1e-3
        const val GRADIENT_16 = 1e-2

        /** A piece's length below which its difference is told against this: of no length at all. */
        const val TINY = 1e-12

        /** The walks of the descent's test, its steps a walk and first rate. */
        const val WALKS = 4
        const val STEPS = 150
        const val RATE = 0.05f

        /** How many times flatter the loss of every item is at the descent's end than at its start, at least. */
        const val FLATTER = 10

        /**
         * The items of a portion of the descent over several, and how many times flatter the whole's loss is at its end
         * at least: each portion's loss a mean over its own pairs, the whole's slope nought where theirs is, near.
         */
        const val PORTION = 4
        const val FLATTER_IN_PORTIONS = 3
    }
}
