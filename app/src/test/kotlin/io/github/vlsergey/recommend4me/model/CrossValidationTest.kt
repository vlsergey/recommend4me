package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.scorer.FittedScorer
import io.github.vlsergey.recommend4me.scorer.RankingTask
import io.github.vlsergey.recommend4me.scorer.Scorer
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossValidationTest {

    @Test
    fun `every row has a fold, and a work keeps to one`() {
        val grades = IntArray(12) { 1 + it % 5 }
        // Rows 10 and 11 are one work
        val works = LongArray(12) { if (it == 11) 10L else it.toLong() }
        val folds = CrossValidation.folds(grades, works)
        assertEquals(grades.size, folds.size)
        assertEquals(folds[10], folds[11])
        assertTrue(folds.distinct().size > 1)
    }

    /**
     * Weights every feature by how it goes with the grades of the rows learnt, shrunk by the parameter: a scorer whose
     * every parameter is a fit of its own.
     */
    private class Leaning : Scorer {
        override val id = "leaning"
        override val candidates = listOf(0.1, 1.0, 10.0)
        override val defaultParameter = 1.0

        override fun fit(task: RankingTask, rows: IntArray, parameter: Double): FittedScorer {
            val w =
                FloatArray(task.x.cols) { f ->
                    (rows.sumOf { (task.grades[it] - 3) * task.x.held[it * task.x.cols + f].toDouble() } /
                            (rows.size + parameter))
                        .toFloat()
                }
            return object : FittedScorer {
                override fun scores(x: Matrix, rows: Int) =
                    FloatArray(rows) { i ->
                        (0 until x.cols).fold(0f) { s, f ->
                            s + w[f] * x.held[i * x.cols + f] * if (f % 2 == 0) 1f else w[f]
                        }
                    }

                override fun pack() = ByteArray(0)
            }
        }

        override fun unpack(bytes: ByteArray) = error("not kept")
    }

    /** The measuring of one parameter as it was made before every fold fitted every parameter at once. */
    private fun measuredAlone(
        task: RankingTask,
        scorer: Scorer,
        parameter: Double,
        xFor: ((Set<Long>) -> Matrix)?,
    ): RankingCandidate? {
        val grades = task.grades
        val works = task.works
        val folds = CrossValidation.folds(grades, works)
        val k = (folds.maxOrNull() ?: 0) + 1
        val scores = DoubleArray(grades.size)
        // Of fewer than two folds, none measured
        val perFold =
            (if (k >= 2) 0 until k else IntRange.EMPTY).mapNotNull { fold ->
                val train = grades.indices.filter { folds[it] != fold }.toIntArray()
                val test = grades.indices.filter { folds[it] == fold }.toIntArray()
                val rows = xFor?.invoke(test.map { works[it] }.toSet()) ?: task.x
                val all = scorer.fit(RankingTask(rows, grades, works, task.items), train, parameter).scores(rows)
                val (mean, sd) = CrossValidation.standardisation(train.map { all[it].toDouble() })
                test.forEach { scores[it] = (all[it] - mean) / sd }
                CrossValidation.concordance(grades, works, test, scores)
            }
        if (perFold.isEmpty()) return null
        val mean = perFold.average()
        val se =
            if (perFold.size > 1) sqrt(perFold.sumOf { (it - mean).pow(2) } / (perFold.size - 1) / perFold.size)
            else 0.0
        val decided = grades.indices.filter { grades[it] != Grades.MIDDLE }
        return RankingCandidate(
            parameter,
            RankingMetrics(
                k,
                mean,
                se,
                CrossValidation.spearman(scores.toList(), grades.map { it.toDouble() }),
                CrossValidation.auc(decided.map { grades[it] > Grades.MIDDLE }, decided.map { scores[it] }),
            ),
            scores,
        )
    }

    @Test
    fun `the folds fitting every parameter at once measure each as measured alone`() {
        val random = Random(2)
        val n = 40
        val d = 5
        val x = Matrix(n, d)
        for (k in 0 until n * d) x.held[k] = random.nextFloat() * 2 - 1
        val grades = IntArray(n) { 1 + random.nextInt(5) }
        // Two ratings of some works
        val works = LongArray(n) { (it % 33).toLong() }
        val task = RankingTask(x, grades, works, works)
        // A fold's own rows: the features of the works under test changed, as the likeness to the marks is
        val xFor = { underTest: Set<Long> ->
            Matrix(n, d).also { m ->
                for (i in 0 until n) for (f in 0 until d) m.held[i * d + f] =
                    x.held[i * d + f] * if (works[i] in underTest) 0.5f else 1f
            }
        }
        listOf(null, xFor).forEach { rows ->
            val measured = CrossValidation.measureAll(task, Leaning(), rows)
            assertEquals(Leaning().candidates, measured.map { it.parameter })
            measured.forEach { m ->
                val alone = measuredAlone(task, Leaning(), m.parameter, rows)!!
                assertEquals(alone.metrics, m.metrics)
                assertContentEquals(alone.scores, m.scores)
            }
        }
    }
}
