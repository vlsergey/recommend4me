package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.onnx.Onnx
import io.github.vlsergey.recommend4me.onnx.defaultModelsDir
import java.nio.file.Path
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Of a real task kept by the application (BENCH_TASK), one portion: a model of every penalty of BENCH_LAMBDAS fitted on
 * the items outside fold 0 by the search over the portion whole ([WholeSearch]) and by the descent over it cut in
 * BENCH_SPLIT portions ([Descent]; BENCH_PLAN "walks,steps,rate" in place of the plan from scratch), their held-out
 * losses told. A measure run by hand.
 */
class DescentBench {
    private val card: Cublas? by lazy { Onnx(defaultModelsDir(), gpu = true).cudaLibraries()?.let(Cublas::open) }

    @Test
    fun `the descent over portions against the search of them whole`() {
        val file = System.getenv("BENCH_TASK")
        assumeTrue(file != null, "BENCH_TASK names a kept task")
        val task = SuggestionTaskFile.read(Path.of(file!!))
        val portion = task.ofItems(IntArray(minOf(task.itemCount, TaskPortions.MOST)) { it })
        val whole = TaskPortions.of(portion)
        val survey = Survey.of(whole) { false }
        val layout = ModelLayout(survey.design, BilinearSuggester.RANK)
        val lambdas = (System.getenv("BENCH_LAMBDAS") ?: "1e-1,1e-3,1e-5").split(",").map { it.trim().toFloat() }
        val ways = (System.getenv("BENCH_WAYS") ?: "whole,descent").split(",")
        val split = split(portion, System.getenv("BENCH_SPLIT")?.toInt() ?: 2)
        println("BENCH ${portion.itemCount} items × ${portion.valueCount} values, common ${survey.common}")
        for (lambda in lambdas) {
            val start = layout.start(1L, survey.used, survey.common)
            if ("whole" in ways) whole(whole, survey, lambda, start)
            if ("descent" in ways) descent(split, survey, lambda, start)
        }
    }

    /** The items of the [task] in [parts] portions alike. */
    private fun split(task: SuggestionTask, parts: Int): TaskPortions =
        object : TaskPortions {
            override val values = task.ofItems(IntArray(0))
            override val items = task.itemCount

            override fun forEach(action: (SuggestionTask) -> Unit) {
                for (k in 0 until parts) {
                    val from = task.itemCount * k / parts
                    val to = task.itemCount * (k + 1) / parts
                    action(task.ofItems(IntArray(to - from) { from + it }))
                }
            }
        }

    private fun whole(portions: TaskPortions, survey: Survey, lambda: Float, start: FloatArray) {
        val search =
            WholeSearch(
                portions,
                survey.design,
                BilinearSuggester.RANK,
                floatArrayOf(lambda),
                intArrayOf(0),
                card,
                layoutCarried = false,
                stop = { false },
            )
        val held = Bilinear.Held()
        val began = System.nanoTime()
        search.run(listOf(start), listOf(held), listOf(Bilinear.Held()))
        println(
            "BENCH λ=$lambda whole: held ${"%.5f".format(held.mean)}, ${search.timing.rounds} rounds, " +
                "${(System.nanoTime() - began) / 1_000_000} ms"
        )
    }

    private fun descent(portions: TaskPortions, survey: Survey, lambda: Float, start: FloatArray) {
        val plan =
            System.getenv("BENCH_PLAN")?.split(",")?.let { Plan(it[0].toInt(), it[1].toInt(), it[2].toFloat()) }
                ?: Plan.fromScratch(maxOf(survey.portions, 2), portions.items)
        val descent =
            Descent(
                portions,
                survey.design,
                BilinearSuggester.RANK,
                floatArrayOf(lambda),
                intArrayOf(0),
                card,
                plan,
                stop = { false },
            )
        val held = Bilinear.Held()
        val began = System.nanoTime()
        descent.run(listOf(start), listOf(held), listOf(Bilinear.Held()))
        println(
            "BENCH λ=$lambda descent ${plan.walks}×${plan.stepsPerWalk}: held ${"%.5f".format(held.mean)}, " +
                "${(System.nanoTime() - began) / 1_000_000} ms"
        )
    }
}
