package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Floats
import java.util.concurrent.CancellationException

/**
 * THE FITTING OF EVERY MODEL OVER THE ITEMS OF A FACET HELD WHOLE — of no more items than a portion holds, handed over
 * as one ([TaskPortions]): every model's minimum searched by L-BFGS ([ModelSearch]) on the loss of every item, as the
 * bilinear suggester fitted before it was fitted a portion at a time (2026-11-19). The descent over the portions
 * ([Descent]) — Adam, a step a portion — reached no optimum of a source of one portion: of author.today's tags a
 * held-out loss of 0.0276 at λ = 0.1 where this search held 0.0046 out, of 0.846 at λ = 1e-5 where it held 0.00087, ten
 * times as long. Every penalty's folds searched together, a product of them all a step, the penalties one after
 * another.
 *
 * Of [lambdas] and [folds], a model each: its penalty and the fold it is held out of. Every item judged by the model of
 * its fold, which did not learn from it ([held], [unseen]).
 */
internal class WholeSearch(
    private val portions: TaskPortions,
    private val design: Design,
    private val rank: Int,
    private val lambdas: FloatArray,
    private val folds: IntArray,
    private val card: Cublas?,
    /** Whether the [design] is the last state's: a block of the items it does not know ends it ([UnknownBlock]). */
    private val layoutCarried: Boolean,
    private val stop: () -> Boolean,
) {
    private val m = portions.values.valueCount
    private val count = lambdas.size

    /** The point of every model, as the descent keeps it. */
    val point = EnsemblePoint(count, rank, design.itemWidth, design.valueWidth, m)

    /** Where the time of the steps went. */
    val timing = FitTiming()

    /** Whether any item fitted on has a value of the facet. */
    var assigned = false
        private set

    /** Where the products were made, as the log tells it. */
    var where = ""
        private set

    /**
     * Every model searched from its start of [starts] (of [Bilinear]'s layout) over the one portion of the items, its
     * held-out loss added to its sums of [held] — of every pair — and of [unseen] — of the values no item outside its
     * fold has. A second portion of items ends it ([NotWhole]).
     */
    fun run(starts: List<FloatArray>, held: List<Bilinear.Held>, unseen: List<Bilinear.Held>) {
        var searched = false
        portions.forEach { portion ->
            if (stop()) throw CancellationException("the fitting is given up")
            if (portion.itemCount == 0) return@forEach
            fitsWhole(portion, searched)
            assigned = portion.assigned.any { it.isNotEmpty() }
            val problem = Problem(Descent.withHints(portions, portion, 0L), design, BilinearSuggester.FOLDS)
            val seen = Array(BilinearSuggester.FOLDS) { BooleanArray(m) }
            problem.markSeen(seen)
            searchAll(problem, starts, held, unseen) { fold -> BooleanArray(m) { !seen[fold][it] } }
            searched = true
        }
    }

    /**
     * The [portion] one the search can go on with: the first of items ([searched] none before), of the blocks the
     * layout carried knows.
     */
    private fun fitsWhole(portion: SuggestionTask, searched: Boolean) {
        if (searched) throw NotWhole()
        val unknown = if (layoutCarried) design.unknownBlocks(portion) else emptyList()
        if (unknown.isNotEmpty()) throw UnknownBlock(unknown)
    }

    /**
     * Every penalty's models searched together on the [problem], one penalty after another in one ensemble — its
     * penalties set anew for each: a product of them all a step, the searches done keeping their points.
     */
    private fun searchAll(
        problem: Problem,
        starts: List<FloatArray>,
        held: List<Bilinear.Held>,
        unseen: List<Bilinear.Held>,
        unseenOf: (Int) -> BooleanArray,
    ) {
        val groups = (0 until count).groupBy { lambdas[it] }.values.toList()
        val width = groups.maxOf { it.size }
        check(groups.all { g -> g.indices.all { folds[g[it]] == folds[groups[0][it]] } }) {
            "every penalty's models of the same folds in the same order"
        }
        val penalties = FloatArray(width)
        val own = EnsemblePoint(width, rank, design.itemWidth, design.valueWidth, m)
        val ofGroup = IntArray(width) { folds[groups[0][it]] }
        Ensemble(problem, rank, penalties, ofGroup, card, own, timing).use { ensemble ->
            where = Descent.placeOf(ensemble)
            groups.forEach { models ->
                models.forEachIndexed { k, q -> penalties[k] = lambdas[q] }
                search(ensemble, own, models, starts)
                ensemble.heldOut(models.map { held[it] }, models.map { unseen[it] }, unseenOf)
                models.forEachIndexed { k, q -> point.put(q, own.take(k)) }
            }
        }
    }

    /**
     * The [models]' searches from their [starts] in the [ensemble] of the point [own], a slot each, till every one is
     * done: their minima left in their slots.
     */
    private fun search(ensemble: Ensemble, own: EnsemblePoint, models: List<Int>, starts: List<FloatArray>) {
        val size = own.layout.size
        val points = Array(models.size) { own.pieces(it, gradient = false) }
        val grads = Array(models.size) { own.pieces(it, gradient = true) }
        val searches = models.map { q ->
            ModelSearch(size, MAX_STEPS).also { s ->
                s.begin(Floats(size).also { it.put(own.layout.ordered(starts[q]), 0, 0, size) })
            }
        }
        val losses = FloatArray(ensemble.count)
        while (searches.any { !it.done }) {
            if (stop()) throw CancellationException("the fitting is given up")
            var at = System.nanoTime()
            searches.forEachIndexed { k, s -> s.propose(points[k]) }
            timing.moves += System.nanoTime() - at
            ensemble.step(losses)
            at = System.nanoTime()
            searches.forEachIndexed { k, s -> s.answer(losses[k], points[k], grads[k]) }
            timing.moves += System.nanoTime() - at
            timing.rounds++
        }
        // The minima written back: the last points proposed were the searches' trials
        searches.forEachIndexed { k, s -> s.propose(points[k]) }
    }

    private companion object {
        /** The steps of a search at most: of author.today's tags the weakest penalty's took 140. */
        const val MAX_STEPS = 300
    }
}

/** The items came in more than one portion ([WholeSearch]): the descent over the portions fits them. */
internal class NotWhole : IllegalStateException("the items are of more than one portion")
