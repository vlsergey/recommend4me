package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test

/**
 * One step of fifteen models in one product on the card, of a real task kept by the application
 * (BENCH_TASK names the file): with its names and without, the pairs alone. Not a test — a measure,
 * run by hand.
 */
class PairsOnCardBench {
    @Test
    fun `a step of fifteen models on the card, with the names and without`() {
        val file = System.getenv("BENCH_TASK")
        assumeTrue(file != null, "BENCH_TASK names a kept task")
        val dir = Onnx(defaultModelsDir(), gpu = true).cudaLibraries()
        assumeTrue(dir != null, "no CUDA libraries")
        val cublas = Cublas.open(dir!!)
        assumeTrue(cublas != null, "no cuBLAS")
        val t = SuggestionTaskFile.read(Path.of(file!!))
        val bare = SuggestionTask(
            t.itemIds, t.inputs, t.values, t.valueKeys, t.assigned, t.confirmed, t.rejected, t.context, t.contextKeys,
            t.mentions, t.allowed, t.groupings, t.lackWeight, t.examples, emptyList(), Matrix(0, 0), t.drafts, t.draftWeight,
        )
        for ((what, task) in listOf("with names" to t, "pairs alone" to bare)) {
            val problem = Problem(task, Design.of(task), BilinearSuggester.FOLDS)
            val count = 3 * BilinearSuggester.FOLDS
            val alone = Bilinear(problem, 16)
            val thetas = List(count) { alone.start(it + 1L) }
            Ensemble(problem, 16, FloatArray(count) { 1e-4f }, IntArray(count) { it % BilinearSuggester.FOLDS }, cublas).use { ensemble ->
                repeat(2) { ensemble.lossesAndGradients(thetas) }
                val started = System.nanoTime()
                repeat(5) { ensemble.lossesAndGradients(thetas) }
                println(
                    "BENCH $what: ${problem.n} items × ${problem.m} values, ${problem.hintPairs} pairs of names in ${problem.hintGroups.size} groups; " +
                        "pairs on the card ${ensemble.pairsOnCardUsed}; a step of $count models ${(System.nanoTime() - started) / 5 / 1_000_000} ms; ${ensemble.timing}",
                )
            }
        }
    }
}
