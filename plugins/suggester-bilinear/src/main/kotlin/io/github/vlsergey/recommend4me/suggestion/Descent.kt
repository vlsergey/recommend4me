package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Matrix
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * THE DESCENT OF EVERY MODEL OF A FITTING OVER THE PORTIONS OF THE ITEMS ([TaskPortions]): a portion at a time, its
 * items' every model's loss and gradient in one product ([Ensemble]) and the points moved by it ([Adam]) — a few steps
 * a portion, as many walks over them all as the [plan] says. Nothing of a portion outlives its turn: what the descent
 * keeps is the points of the models ([point]), their running means, and a few numbers of every value.
 *
 * Of [lambdas] and [folds], a model each: its penalty and the fold it is held out of. EVERY ITEM IS JUDGED BY A MODEL
 * THAT DID NOT LEARN FROM IT: on the last walk, every portion's items of a fold are scored by that fold's model before
 * its steps on the portion ([held], [unseen]) — the model never stepped on them, in this walk or before.
 *
 * THE NAMES OF THE VALUES AS TEXTS ([SuggestionTask.hints]) learnt from with the items, a share of them a portion — as
 * many as the portion's share of the items: a step's loss is a mean over the pairs it reads, and the names weigh in it
 * as they weigh in the whole.
 */
internal class Descent(
    private val portions: TaskPortions,
    private val design: Design,
    private val rank: Int,
    private val lambdas: FloatArray,
    private val folds: IntArray,
    private val card: Cublas?,
    private val plan: Plan,
    private val stop: () -> Boolean,
) {
    private val values = portions.values
    private val m = values.valueCount
    private val count = lambdas.size

    /** The features of every value, made once for every portion. */
    private val z: Matrix = design.values(values)

    /** The point of every model and its gradient, kept over the portions. */
    val point = EnsemblePoint(count, rank, design.itemWidth, design.valueWidth, m)
    private val adam = Adam(point)

    /** Where the time of the steps went. */
    val timing = FitTiming()

    /** Of every fold, the values an item outside it has: marked as the walks pass the items. */
    private val seen = Array(BilinearSuggester.FOLDS) { BooleanArray(m) }

    /** Whether any item fitted on has a value of the facet. */
    var assigned = false
        private set

    /** Where the last portion's products were made, as the log tells it. */
    var where = ""
        private set

    /**
     * The walks of the [plan] from the [starts] (of [Bilinear]'s layout, a model each), every model's held-out loss on
     * the last walk added to its sums of [held] — of every pair — and of [unseen] — of the values no item outside its
     * fold has: a sum each, the folds of a penalty sharing one.
     */
    fun run(starts: List<FloatArray>, held: List<Bilinear.Held>, unseen: List<Bilinear.Held>) {
        starts.forEachIndexed { model, theta -> point.put(model, theta) }
        val losses = FloatArray(count)
        var step = 0
        for (walk in 0 until plan.walks) {
            val last = walk == plan.walks - 1
            var offset = 0L
            var owed = 0.0
            portions.forEach { portion ->
                if (stop()) throw CancellationException("the fitting is given up")
                val n = portion.itemCount
                if (n == 0) return@forEach
                // The steps of the walk shared among its portions by their items, every portion one at least
                owed += plan.stepsPerWalk.toDouble() * n / portions.items
                val steps = maxOf(1, owed.roundToInt())
                owed -= steps
                assigned = assigned || portion.assigned.any { it.isNotEmpty() }
                val problem = Problem(withHints(portion, offset), design, BilinearSuggester.FOLDS, z)
                problem.markSeen(seen)
                Ensemble(problem, rank, lambdas, folds, card, point, timing).use { ensemble ->
                    if (last) ensemble.heldOut(held, unseen, ::unseenOutside)
                    repeat(steps) {
                        if (stop()) throw CancellationException("the fitting is given up")
                        ensemble.step(losses)
                        val started = System.nanoTime()
                        adam.step(losses, plan.rate(step++))
                        timing.moves += System.nanoTime() - started
                        timing.rounds++
                    }
                    where = placeOf(ensemble)
                }
                offset += n
            }
        }
    }

    /** The values no item outside the [fold] has, of the items the walks passed. */
    private fun unseenOutside(fold: Int): BooleanArray = BooleanArray(m) { !seen[fold][it] }

    /**
     * The [portion], its items from the [offset]th of the walk, with its share of the names of the values: those whose
     * place among them is the place of its items among every item.
     */
    private fun withHints(portion: SuggestionTask, offset: Long): SuggestionTask {
        val h = values.hints.size
        val to = ((offset + portion.itemCount) * h / portions.items).toInt().coerceAtMost(h)
        val from = (offset * h / portions.items).toInt().coerceAtMost(to)
        val texts = if (to > from) values.hintTexts.gather(IntArray(to - from) { from + it }) else Matrix(0, 0)
        return SuggestionTask(
            portion.itemIds,
            portion.inputs,
            portion.values,
            portion.valueKeys,
            portion.assigned,
            portion.confirmed,
            portion.rejected,
            portion.context,
            portion.contextKeys,
            portion.allowed,
            portion.groupings,
            portion.lackWeight,
            portion.examples,
            values.hints.subList(from, to),
            texts,
            portion.drafts,
            portion.draftWeight,
            portion.barred,
        )
    }

    /** Where the [ensemble] made its products, as the log tells it. */
    private fun placeOf(ensemble: Ensemble): String =
        (if (ensemble.featuresOnCard) "the items' features on the card (cuBLAS)" else "on the processor (OpenBLAS)") +
            (if (ensemble.pairsOnCardUsed) ", the pairs and names on the card" else "")
}

/**
 * HOW FAR A DESCENT GOES ([Descent]): how many [walks] over the portions, how many steps a walk — shared among the
 * portions by their items —, and the rate of every step: from its [rate] at the start down to a hundredth of it at the
 * end along half a cosine — the far moves first, the noise of the portions against the whole quieted at the end.
 */
internal class Plan(val walks: Int, val stepsPerWalk: Int, private val rate: Float) {
    /** The steps of the descent in all. */
    val steps = walks * stepsPerWalk

    /** The rate of the step [step]. */
    fun rate(step: Int): Float {
        val done = (step.toDouble() / steps).coerceIn(0.0, 1.0)
        return (rate * (FLOOR + (1 - FLOOR) * (1 + cos(PI * done)) / 2)).toFloat()
    }

    companion object {
        /** The least share of the first rate the last step is taken at. */
        private const val FLOOR = 0.01

        /**
         * The steps a descent from scratch takes in all, at least: a portion of every item takes them as a whole's
         * descent; the more portions, the more steps — a step a portion of every walk.
         */
        private const val STEPS = 600

        /**
         * The walks of a descent from scratch: a source of many portions — every walk reading every work of it anew —
         * walks them fewer times, each a step of each portion; of a few, as many as [STEPS] asks, [MOST_WALKS] at most,
         * the steps of a walk shared among them.
         */
        private const val LEAST_WALKS = 3
        private const val MOST_WALKS = 6

        /** The first rate from scratch: a weight moves by about as much a step. */
        private const val RATE = 0.03f

        /**
         * Gone on from the state fitted last: two walks — the held-out losses are of the second, the values each fold
         * has not seen known of the first — of a third of the steps, at a third of the rate: a few works or answers
         * more move the optimum a little.
         */
        private const val ON_WALKS = 2
        private const val ON_STEPS = STEPS / 3
        private const val ON_RATE = RATE / 3

        /** The plan from scratch of a facet of [portions] portions. */
        fun fromScratch(portions: Int): Plan {
            val walks = ((STEPS + portions - 1) / maxOf(1, portions)).coerceIn(LEAST_WALKS, MOST_WALKS)
            return Plan(walks, maxOf(portions, (STEPS + walks - 1) / walks), RATE)
        }

        /** The plan gone on from the state fitted last, of a facet of [portions] portions. */
        fun goneOn(portions: Int): Plan = Plan(ON_WALKS, maxOf(portions, ON_STEPS / ON_WALKS), ON_RATE)
    }
}
