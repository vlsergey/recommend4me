package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.scorer.RankingTask
import io.github.vlsergey.recommend4me.scorer.Scorer
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Out-of-fold quality of a ranking. */
data class RankingMetrics(
    val folds: Int,
    /** Share of pairs of works of different grades put in the right order. */
    val concordance: Double,
    /** Standard error of [concordance] over the folds: differences smaller than it are noise. */
    val concordanceSe: Double,
    /** Spearman's rank correlation of the scores and the grades. */
    val spearman: Double?,
    /** ROC AUC of liked (above the middle grade) against disliked (below it). */
    val auc: Double?,
)

/** One value of the scorer's parameter with its out-of-fold quality: what the model keeps of its choice. */
data class RankingResult(val parameter: Double, val metrics: RankingMetrics)

/** One value of the parameter with its out-of-fold quality and the out-of-fold standardised scores of the rated rows. */
class RankingCandidate(val parameter: Double, val metrics: RankingMetrics, val scores: DoubleArray) {
    fun result() = RankingResult(parameter, metrics)
}

/**
 * THE SCORER'S PARAMETER IS CHOSEN BY THE CONCORDANCE ON CROSS-VALIDATION over works (two ratings
 * of one work never fall into different folds), under the one-standard-error rule: of the values no
 * further from the best than its standard error, the most cautious — the first of the scorer's list.
 *
 * THE SCALE COMES AFTERWARDS, FROM THE DATA ([ladder], [Scale]): on the scorer's line every grade
 * is a cloud of its works; the centre of the lowest grade becomes 0, that of the highest 10, and
 * every other grade lands where its centre lies between them. The centres are taken from
 * cross-validation — from works each fold's model had not seen — because on its own training works
 * a model spreads the clouds wider than they will be on new ones.
 */
object CrossValidation {

    /** Fewer rated works than this are not split into folds. */
    const val MIN_WORKS = 6
    private const val MAX_FOLDS = 5

    /** Fold of every rating: works dealt round-robin by their last grade, so every fold sees every grade. */
    fun folds(grades: IntArray, works: LongArray): IntArray {
        val distinct = works.distinct()
        val k = minOf(MAX_FOLDS, distinct.size / 2)
        val folds = IntArray(grades.size)
        if (distinct.size < MIN_WORKS || k < 2) return folds
        val lastGrade = HashMap<Long, Int>()
        works.forEachIndexed { i, id -> lastGrade[id] = grades[i] }
        val foldOf = HashMap<Long, Int>()
        var next = 0
        val random = Random(0)
        Grades.RANGE.forEach { g ->
            distinct.filter { lastGrade[it] == g }.shuffled(random).forEach { foldOf[it] = next++ % k }
        }
        works.forEachIndexed { i, id -> folds[i] = foldOf.getValue(id) }
        return folds
    }

    /** Share of the pairs of [rows] of different grades and works put in the right order by [scores] (ties count half). */
    fun concordance(grades: IntArray, works: LongArray, rows: IntArray, scores: DoubleArray): Double? {
        var right = 0.0
        var pairs = 0
        for (a in rows) for (b in rows) {
            if (grades[a] > grades[b] && works[a] != works[b]) {
                val d = scores[a] - scores[b]
                right += if (d > 0) 1.0 else if (d == 0.0) 0.5 else 0.0
                pairs++
            }
        }
        return if (pairs == 0) null else right / pairs
    }

    /**
     * Cross-validates [scorer] with [parameter] on the rows [xFor] gives (the works under test given,
     * for features that depend on them; [task]'s own when they do not). A fold's scores are
     * standardised by the fold's model on its own training rows, so all folds share one scale; the
     * concordance is counted within every fold and averaged.
     */
    fun measure(task: RankingTask, scorer: Scorer, parameter: Double, xFor: ((Set<Long>) -> Matrix)? = null): RankingCandidate? {
        val grades = task.grades
        val works = task.works
        val folds = folds(grades, works)
        val k = (folds.maxOrNull() ?: 0) + 1
        if (k < 2) return null
        val scores = DoubleArray(grades.size)
        val perFold = (0 until k).mapNotNull { fold ->
            val train = grades.indices.filter { folds[it] != fold }.toIntArray()
            val test = grades.indices.filter { folds[it] == fold }.toIntArray()
            val rows = xFor?.invoke(test.map { works[it] }.toSet()) ?: task.x
            val all = scorer.fit(RankingTask(rows, grades, works), train, parameter).scores(rows)
            val (mean, sd) = standardisation(train.map { all[it].toDouble() })
            test.forEach { scores[it] = (all[it] - mean) / sd }
            concordance(grades, works, test, scores)
        }
        if (perFold.isEmpty()) return null
        val mean = perFold.average()
        val se = if (perFold.size > 1) sqrt(perFold.sumOf { (it - mean).pow(2) } / (perFold.size - 1) / perFold.size) else 0.0
        val decided = grades.indices.filter { grades[it] != Grades.MIDDLE }
        return RankingCandidate(
            parameter,
            RankingMetrics(
                folds = k,
                concordance = mean,
                concordanceSe = se,
                spearman = spearman(scores.toList(), grades.map { it.toDouble() }),
                auc = auc(decided.map { grades[it] > Grades.MIDDLE }, decided.map { scores[it] }),
            ),
            scores,
        )
    }

    /** Every candidate parameter of the scorer, measured in parallel, in the scorer's order. */
    fun measureAll(task: RankingTask, scorer: Scorer, xFor: ((Set<Long>) -> Matrix)? = null): List<RankingCandidate> =
        scorer.candidates.parallelStream().map { measure(task, scorer, it, xFor) }.toList().filterNotNull()
            .sortedBy { scorer.candidates.indexOf(it.parameter) }

    /** The first — most cautious — candidate within one standard error of the best concordance. */
    fun choose(candidates: List<RankingCandidate>): RankingCandidate? {
        val best = candidates.maxByOrNull { it.metrics.concordance } ?: return null
        val limit = best.metrics.concordance - best.metrics.concordanceSe
        return candidates.first { it.metrics.concordance >= limit }
    }

    fun standardisation(values: List<Double>): Pair<Double, Double> {
        val mean = values.average()
        val sd = sqrt(values.sumOf { (it - mean).pow(2) } / values.size).coerceAtLeast(1e-9)
        return mean to sd
    }

    /**
     * Where every grade lies on the standardised line: the median of its works' [scores], made to
     * rise with the grade — adjacent grades whose medians do not rise are pooled into their weighted
     * mean. A grade without works has no place.
     */
    fun ladder(grades: IntArray, scores: DoubleArray): Map<Int, Double> {
        val groups = grades.indices.groupBy { grades[it] }.toSortedMap()
        class Block(val grades: MutableList<Int>, var value: Double, var weight: Int)
        val blocks = ArrayList<Block>()
        groups.forEach { (g, rows) ->
            val s = rows.map { scores[it] }.sorted()
            blocks += Block(mutableListOf(g), (s[(s.size - 1) / 2] + s[s.size / 2]) / 2, rows.size)
            while (blocks.size > 1 && blocks[blocks.size - 2].value >= blocks.last().value) {
                val b = blocks.removeAt(blocks.size - 1)
                val a = blocks.last()
                a.value = (a.value * a.weight + b.value * b.weight) / (a.weight + b.weight)
                a.weight += b.weight
                a.grades += b.grades
            }
        }
        return blocks.flatMap { b -> b.grades.map { it to b.value } }.toMap()
    }

    /** Spearman's rank correlation: Pearson's of the ranks, ties sharing their mean rank; null when one side is constant. */
    fun spearman(a: List<Double>, b: List<Double>): Double? {
        val ra = ranks(a)
        val rb = ranks(b)
        val ma = ra.average()
        val mb = rb.average()
        var cov = 0.0
        var va = 0.0
        var vb = 0.0
        for (i in ra.indices) {
            cov += (ra[i] - ma) * (rb[i] - mb)
            va += (ra[i] - ma).pow(2)
            vb += (rb[i] - mb).pow(2)
        }
        return if (va == 0.0 || vb == 0.0) null else cov / sqrt(va * vb)
    }

    private fun ranks(values: List<Double>): DoubleArray {
        val order = values.indices.sortedBy { values[it] }
        val ranks = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            for (t in i..j) ranks[order[t]] = (i + j) / 2.0 + 1
            i = j + 1
        }
        return ranks
    }

    /** ROC AUC by the rank-sum statistic; null when only one class is present. */
    fun auc(positive: List<Boolean>, scores: List<Double>): Double? {
        val pos = positive.count { it }
        val neg = positive.size - pos
        if (pos == 0 || neg == 0) return null
        val r = ranks(scores)
        val rankSum = positive.indices.filter { positive[it] }.sumOf { r[it] }
        return (rankSum - pos * (pos + 1) / 2.0) / (pos.toDouble() * neg)
    }
}

/**
 * A scorer's score of a work on 0..10: standardised as on the training rows ([mean], [sd]), then
 * placed by the [ladder] — the lowest grade's centre is 0, the highest's 10. Works beyond the
 * extreme grades go beyond 0..10: the order among the very best is kept, and a work past 10 is one
 * the model is surer of than of a typical work of the highest grade.
 */
class Scale(val mean: Double, val sd: Double, val ladder: Map<Int, Double>) {
    private val low = ladder.values.minOrNull() ?: -1.0
    private val high = ladder.values.maxOrNull() ?: 1.0
    private val span = if (high > low) high - low else 1.0

    fun score(raw: Double): Double = Grades.MAX_SCORE * ((raw - mean) / sd - low) / span

    /** Where every grade stands on 0..10. */
    fun grades(): Map<Int, Double> = ladder.mapValues { Grades.MAX_SCORE * (it.value - low) / span }
}
