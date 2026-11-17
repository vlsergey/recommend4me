package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The fitting of a real task kept by the application ([SuggestionTaskFile]), measured: BENCH_TASK names the file. Not a
 * test of anything — a measure, run by hand: a step of the models of a refit over a portion of the task, then — of
 * BENCH_WHOLE — the fitting from scratch in portions and the refit after one answer more, gone on from it, as the
 * application makes them.
 */
class BilinearBench {
    @Test
    fun `a kept task fitted, and fitted again after one answer more`() {
        val file = System.getenv("BENCH_TASK")
        assumeTrue(file != null, "BENCH_TASK names a kept task")
        val task = SuggestionTaskFile.read(Path.of(file!!))
        val portion = task.ofItems(IntArray(minOf(task.itemCount, TaskPortions.MOST)) { it })
        val problem = Problem(portion, Design.of(task), BilinearSuggester.FOLDS)
        println(
            "BENCH ${task.itemCount} items × ${task.valueCount} values, a portion of ${portion.itemCount}: " +
                "${problem.pairs.count.sumOf { it.toLong() }} pairs that weigh anything, " +
                "${problem.hintPairs} pairs of names in ${problem.hintGroups.size} groups"
        )
        ensembleStep(problem)
        if (System.getenv("BENCH_WHOLE") != null)
            fittedAndRefitted(BilinearSuggester(Onnx(defaultModelsDir(), gpu = true)), task)
    }

    /** One step of two penalties' five folds each in one product, as a refit takes them, of a portion's items. */
    private fun ensembleStep(problem: Problem) {
        val layout = ModelLayout(problem.design, BilinearSuggester.RANK)
        val count = 2 * BilinearSuggester.FOLDS
        val ensemble =
            Ensemble(
                problem,
                BilinearSuggester.RANK,
                FloatArray(count) { if (it < BilinearSuggester.FOLDS) 1e-6f else 1e-7f },
                IntArray(count) { it % BilinearSuggester.FOLDS },
            )
        val thetas = List(count) { layout.start(it + 1L, BooleanArray(problem.m) { true }, 0f) }
        repeat(2) { ensemble.lossesAndGradients(thetas) }
        val t = System.nanoTime()
        repeat(STEPS) { ensemble.lossesAndGradients(thetas) }
        println(
            "BENCH ten models in one product: one step of them all ${(System.nanoTime() - t) / STEPS / 1_000_000} ms"
        )
    }

    /** The whole fitting from scratch, then the refit after one answer more, gone on from it. */
    private fun fittedAndRefitted(suggester: BilinearSuggester, task: SuggestionTask) {
        var started = System.nanoTime()
        val scratch = suggester.fit(TaskPortions.of(task))!!
        println("BENCH from scratch: ${(System.nanoTime() - started) / 1_000_000} ms")
        val changed = withOneAnswerMore(task)
        started = System.nanoTime()
        suggester.fit(TaskPortions.of(changed), suggester.unpack(scratch.pack(), changed.ofItems(IntArray(0))))
        println("BENCH after one answer more, from the last state: ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    /** The [task] with one answer more: a value the user gives a work of a universe that it had not. */
    private fun withOneAnswerMore(task: SuggestionTask): SuggestionTask {
        val item = task.assigned.indices.first { task.assigned[it].isNotEmpty() && task.allowed[it] != null }
        val value = task.allowed[item]!!.first { it !in task.assigned[item] }
        val assigned = task.assigned.toMutableList().also { it[item] = it[item] + value }
        return SuggestionTask(
            task.itemIds,
            task.inputs,
            task.values,
            task.valueKeys,
            assigned,
            task.confirmed,
            task.rejected,
            task.context,
            task.contextKeys,
            task.allowed,
            task.groupings,
            task.lackWeight,
            task.examples,
            task.hints,
            task.hintTexts,
            task.drafts,
            task.draftWeight,
        )
    }

    private companion object {
        /** The steps measured. */
        const val STEPS = 5
    }
}
