package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BilinearTest {

    /** A small task with every kind of input: two text blocks, a context, examples, mentions, names of the values as hints. */
    private fun task(): SuggestionTask {
        val random = Random(5)
        val n = 12
        val d = 6
        val m = 4
        fun block(name: String, every: Int) = Matrix(n, d).let { b ->
            val present = BooleanArray(n) { it % every != 0 }
            for (i in 0 until n) if (present[i]) b.held.put(FloatArray(d) { random.nextGaussian().toFloat() }, 0, b.row(i), d)
            InputBlock(name, b, present)
        }
        val names = Matrix(m, d).also { v -> for (j in 0 until m) v.held.put(FloatArray(d) { random.nextGaussian().toFloat() }, 0, v.row(j), d) }
        return SuggestionTask(
            itemIds = List(n) { "i$it" },
            inputs = listOf(block("text:title", 5), block("text:annotation", 3)),
            values = names,
            valueKeys = List(m) { "v$it" },
            assigned = List(n) { i -> if (i % 4 == 0) IntArray(0) else intArrayOf(i % m) },
            confirmed = List(n) { IntArray(0) },
            rejected = List(n) { i -> if (i == 3) intArrayOf(1) else IntArray(0) },
            context = List(n) { i -> intArrayOf(i % 2) },
            contextKeys = listOf("f\u0001a", "f\u0001b"),
            mentions = List(n) { i -> if (i % 3 == 0) mapOf(i % m to 2) else null },
            lackWeight = 0.5,
            examples = List(n) { i -> if (i % 2 == 0) intArrayOf(i % m) else IntArray(0) },
            hints = IntArray(m) { it }.let { all -> List(6) { k -> NameHint(k % m, all) } },
            hintTexts = Matrix(6, d).also { t -> for (k in 0 until 6) t.held.put(FloatArray(d) { random.nextGaussian().toFloat() }, 0, t.row(k), d) },
            // Values some items may have, one array for them all, one of them barred to an item; a draft not confirmed
            allowed = intArrayOf(2, 0, 1).let { shared -> List(n) { i -> if (i % 3 == 1) shared else null } },
            barred = List(n) { i -> if (i == 7) intArrayOf(0) else IntArray(0) },
            drafts = List(n) { i -> if (i == 5) intArrayOf(i % m) else IntArray(0) },
            draftWeight = 0.5,
        )
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
                for (v in 0 until m) if ((allowed == null || v in allowed) && v !in task.barred[i]) weight[v] = task.lackWeight.toFloat()
            }
            task.rejected[i].forEach { weight[it] = 1f }
            task.assigned[i].forEach { weight[it] = 1f; label[it] = 1f }
            task.drafts[i].forEach { if (label[it] > 0f && it !in task.confirmed[i]) weight[it] = task.draftWeight.toFloat() }
            val expected = (0 until m).filter { weight[it] != 0f }.map { Triple(it, weight[it], label[it]) }
            val read = ArrayList<Triple<Int, Float, Float>>()
            pairs.forEach(i) { v, w, y -> read += Triple(v, w, y) }
            assertEquals(expected, read, "item $i")
            assertEquals(expected.size, pairs.count[i], "item $i")
            assertEquals(expected.sumOf { it.second.toDouble() }.toFloat(), pairs.rowWeight[i], "item $i")
        }
    }

    @Test
    fun `a step on the card is the processor's`() {
        val task = task()
        val problem = Problem(task, Design.of(task), BilinearSuggester.FOLDS)
        val card = StepOnCard.open(Onnx(defaultModelsDir(), gpu = true), problem, 3)
        assumeTrue(card != null, "no card to run on")
        card.use {
            val onProcessor = Bilinear(problem, 3)
            val onCard = Bilinear(problem, 3, card)
            val random = Random(11)
            val theta = FloatArray(onProcessor.size) { (random.nextGaussian() * 0.3).toFloat() }
            val trains: (Int) -> Boolean = { problem.foldOf[it] != 0 }
            val g1 = FloatArray(onProcessor.size)
            val g2 = FloatArray(onProcessor.size)
            val l1 = onProcessor.lossAndGradient(theta, g1, 0.01f, trains)
            val l2 = onCard.lossAndGradient(theta, g2, 0.01f, trains)
            assertTrue(abs(l1 - l2) < 1e-4 * abs(l1), "loss $l1 on the processor, $l2 on the card")
            // Floats added in another order on the card: the last digits differ, where a wrong graph
            // would be wrong by the whole
            val worst = g1.indices.maxOf { abs(g1[it] - g2[it]) / max(1e-4f, abs(g1[it])) }
            assertTrue(worst < 1e-3, "the worst relative difference of the gradient $worst")
        }
    }

    @Test
    fun `every model in one product has the loss and the gradient it has alone, without names`() {
        val t = task()
        val task = SuggestionTask(
            t.itemIds, t.inputs, t.values, t.valueKeys, t.assigned, t.confirmed, t.rejected, t.context, t.contextKeys,
            t.mentions, t.allowed, t.groupings, t.lackWeight, t.examples, emptyList(), Matrix(0, 0), t.drafts, t.draftWeight,
        )
        compareTogether(task)
    }

    @Test
    fun `every model in one product has the loss and the gradient it has alone`() = compareTogether(task())

    @Test
    fun `every model in one product on the card has the loss and the gradient it has alone`() {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val cublas = Cublas.open(dir!!)
        assumeTrue(cublas != null, "no cuBLAS to run on")
        compareTogether(task(), cublas)
        // And of the rank the application fits by: its kernels compiled with the rank known
        compareTogether(task(), cublas, rank = 16)
    }

    private fun compareTogether(task: SuggestionTask, card: Cublas? = null, rank: Int = 3) {
        val problem = Problem(task, Design.of(task), BilinearSuggester.FOLDS)
        val lambdas = floatArrayOf(0.1f, 0.01f, 0.001f, 0.01f)
        val folds = intArrayOf(0, 1, 2, 4)
        val ensemble = Ensemble(problem, rank, lambdas, folds, card)
        if (card != null) assertTrue(ensemble.featuresOnCard && ensemble.pairsOnCardUsed, "the features or the pairs not on the card")
        val alone = Bilinear(problem, rank)
        val random = Random(17)
        val thetas = List(lambdas.size) { FloatArray(alone.size) { (random.nextGaussian() * 0.3).toFloat() } }
        val (losses, gradients) = ensemble.use { it.lossesAndGradients(thetas) }
        thetas.forEachIndexed { k, theta ->
            val grad = FloatArray(alone.size)
            val loss = alone.lossAndGradient(theta, grad, lambdas[k]) { problem.foldOf[it] != folds[k] }
            assertTrue(abs(loss - losses[k]) < 1e-4 * abs(loss), "model $k: loss $loss alone, ${losses[k]} together")
            val at = grad.indices.maxBy { abs(grad[it] - gradients[k][it]) / max(1e-4f, abs(grad[it])) }
            val worst = abs(grad[at] - gradients[k][at]) / max(1e-4f, abs(grad[at]))
            val dx = problem.design.itemWidth * rank
            val dz = problem.design.valueWidth * rank
            val part = when {
                at < dx -> "A"
                at < dx + dz -> "B"
                at < dx + dz + problem.m * rank -> "E"
                at < dx + dz + problem.m * rank + problem.m -> "bias"
                else -> "pair"
            }
            assertTrue(worst < 1e-3, "model $k: the worst relative difference of the gradient $worst, at $at of $part: ${grad[at]} alone, ${gradients[k][at]} together")
        }
    }

    @Test
    fun `models fitted together come out as fitted alone`() = fitTogether(task(), null)

    @Test
    fun `models fitted together with their searches on the card come out as fitted alone`() {
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val cublas = Cublas.open(dir!!)
        assumeTrue(cublas != null, "no cuBLAS to run on")
        fitTogether(task(), cublas)
        val t = task()
        // And of a task without names: the weights A and their gradient never leave the card
        fitTogether(
            SuggestionTask(
                t.itemIds, t.inputs, t.values, t.valueKeys, t.assigned, t.confirmed, t.rejected, t.context, t.contextKeys,
                t.mentions, t.allowed, t.groupings, t.lackWeight, t.examples, emptyList(), Matrix(0, 0), t.drafts, t.draftWeight,
            ),
            cublas,
        )
    }

    private fun fitTogether(task: SuggestionTask, card: Cublas?) {
        val problem = Problem(task, Design.of(task), BilinearSuggester.FOLDS)
        val rank = 3
        val lambdas = floatArrayOf(0.1f, 0.01f)
        val folds = intArrayOf(1, 3)
        val alone = Bilinear(problem, rank)
        val starts = List(lambdas.size) { alone.start(it + 1L) }
        val ensemble = Ensemble(problem, rank, lambdas, folds, card)
        val fits = ensemble.use { it.fit(starts, Bilinear.MAX_STEPS) { false } }
        if (card != null) assertTrue(ensemble.searchesOnCard, "the searches not on the card")
        starts.forEachIndexed { k, start ->
            val theta = alone.fit(start, lambdas[k], folds[k])
            val loss = alone.lossAndGradient(theta, FloatArray(alone.size), lambdas[k]) { problem.foldOf[it] != folds[k] }
            val together = alone.lossAndGradient(fits.weights[k], FloatArray(alone.size), lambdas[k]) { problem.foldOf[it] != folds[k] }
            // The same search on floats added in another order: the same minimum to the float's resolution of the loss
            assertTrue(abs(loss - together) < 1e-3 * abs(loss), "model $k: loss $loss alone, $together together")
        }
    }

    @Test
    fun `the features' products are those of the features laid out whole`() {
        val task = task()
        val design = Design.of(task)
        val width = design.itemWidth
        val rank = 3
        val random = Random(13)
        listOf(IntArray(task.itemCount) { it }, intArrayOf(7, 2, 11, 5)).forEach { rows ->
            val x = design.features(task, rows)
            val n = rows.size
            // Laid out whole: the constant, every block's mark and its vector times its scale, the values of the context
            val base = 1 + design.blocks.sumOf { 1 + it.dim }
            val dense = Array(n) { r ->
                val i = rows[r]
                FloatArray(width).also { row ->
                    row[0] = 1f
                    design.blocks.forEachIndexed { k, b ->
                        val block = task.inputs.first { it.name == b.name }
                        if (!block.present[i]) return@forEachIndexed
                        row[design.blockOffsets[k]] = 1f
                        for (q in 0 until b.dim) row[design.blockOffsets[k] + 1 + q] = block.vectors.held[block.vectors.row(i) + q] * b.scale
                    }
                    task.context[i].forEach { row[base + design.contextKeys.indexOf(task.contextKeys[it])] = 1f }
                }
            }
            fun close(expected: FloatArray, actual: Floats, what: String) = expected.indices.forEach {
                assertTrue(abs(expected[it] - actual[it]) < 1e-4f * max(1f, abs(expected[it])), "$what at $it: ${expected[it]}, made ${actual[it]}")
            }
            val a = FloatArray(width * rank) { random.nextGaussian().toFloat() }
            val du = FloatArray(n * rank) { random.nextGaussian().toFloat() }
            val u = Floats(n * rank).also { x.times(Floats.of(a), rank, it) }
            close(FloatArray(n * rank) { k -> (0 until width).sumOf { c -> (dense[k / rank][c] * a[c * rank + k % rank]).toDouble() }.toFloat() }, u, "X A")
            val da = Floats(width * rank).also { x.transposedTimes(Floats.of(du), rank, it) }
            close(FloatArray(width * rank) { k -> (0 until n).sumOf { i -> (dense[i][k / rank] * du[i * rank + k % rank]).toDouble() }.toFloat() }, da, "Xᵀ dU")
            // Of two models one under another: Aᵀ and dUᵀ
            val k = 2 * rank
            val aT = FloatArray(k * width) { random.nextGaussian().toFloat() }
            val duT = FloatArray(k * n) { random.nextGaussian().toFloat() }
            val uT = Floats(k * n).also { x.transposedCoordinates(Floats.of(aT), k, it) }
            close(FloatArray(k * n) { t -> (0 until width).sumOf { c -> (aT[t / n * width + c] * dense[t % n][c]).toDouble() }.toFloat() }, uT, "Aᵀ Xᵀ")
            val gA = Floats(k * width).also { x.transposedGradient(Floats.of(duT), k, it) }
            close(FloatArray(k * width) { t -> (0 until n).sumOf { i -> (duT[t / width * n + i] * dense[i][t % width]).toDouble() }.toFloat() }, gA, "dUᵀ X")
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
            val numeric = (model.lossAndGradient(plus, scratch, 0.01f, trains) - model.lossAndGradient(minus, scratch, 0.01f, trains)).toDouble() / (2 * eps)
            worst = max(worst, abs(numeric - grad[k]) / max(1e-3, abs(numeric) + abs(grad[k])))
        }
        assertTrue(worst < 0.02, "the worst relative error $worst")
    }
}
