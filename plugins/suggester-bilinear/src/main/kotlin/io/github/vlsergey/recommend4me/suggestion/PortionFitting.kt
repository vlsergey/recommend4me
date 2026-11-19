package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.ln
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(PortionFitting::class.java)

/**
 * ONE FITTING OF THE BILINEAR MODEL ([BilinearSuggester]) OVER THE PORTIONS OF A FACET'S ITEMS, as its walks go: from
 * scratch a walk to see the items ([Survey]) — gone on from the [last] state its layout's blocks and scales kept, no
 * such walk, unless the items have a block it does not know ([UnknownBlock]) —; the walks of the descent of every
 * penalty's every fold together ([Descent]); the penalty chosen by the held-out losses of the descent's last walk; a
 * last walk to score every item by the model kept ([ScoredPortions]). Of the products on the [card] when there is one;
 * [stop] asked between the steps.
 */
internal class PortionFitting(
    private val portions: TaskPortions,
    private val last: FittedBilinear?,
    private val card: Cublas?,
    private val stop: () -> Boolean,
) {
    private val values = portions.values
    private val folds = BilinearSuggester.FOLDS
    private val rank = BilinearSuggester.RANK

    private val started = System.currentTimeMillis()

    /** Where the descent's products were made, and where its time went: as the log tells them. */
    private var where = ""
    private var timing = ""

    /** The walks over the portions: as the log tells them. */
    private var walks = 0

    /** The state fitted; null when no item fitted on has a value of the facet. */
    fun fitted(): FittedBilinear? {
        // The layout's scales are of every item: seen in a walk of their own, unless the last state's layout knows
        // every block of the items — seen as the descent's first walk reads them: the value side has no item, and
        // tells no block. Its blocks and their scales kept, the typical lengths moving little with a few works more
        val known = last?.design?.let { Design.of(values, it.blocks) }
        if (known != null)
            try {
                val plan = Plan.goneOn((portions.items + TaskPortions.MOST - 1) / TaskPortions.MOST, portions.items)
                walks = 1
                return descended(known, null, penaltiesTried(), plan)?.let { kept(known, it) }
            } catch (e: UnknownBlock) {
                log.info("{}: the items seen anew", e.message)
                walks = 1
            }
        val survey = Survey.of(portions, stop)
        val plan = Plan.fromScratch(survey.portions, portions.items)
        walks += 2
        val descended = if (survey.assigned == 0) null else descended(survey.design, survey, penaltiesTried(), plan)
        return descended?.let { kept(survey.design, it) }
    }

    /**
     * Of the penalties [tried] on the [design], the state kept: the one chosen, the items scored by it in the last
     * walk.
     */
    private fun kept(design: Design, tried: List<Tried>): FittedBilinear {
        // The one-standard-error rule (Breiman et al.): of the penalties that hold out no worse than the best by more
        // than its standard error, the strongest — the data cannot tell them apart, and the stronger guesses less
        // where an item is unlike those it learnt from
        val least = tried.minBy { it.held.mean }
        val chosen = tried.filter { it.held.mean <= least.held.mean + least.held.error }.maxBy { it.lambda }
        val fitted = FittedBilinear(design, rank, chosen.lambda, chosen.folds, beside(chosen, tried))
        val millis = System.currentTimeMillis() - started
        val scored = scored(fitted)
        log.info(
            "{} items × {} values, {} features of an item, {} of a value, {} names, {}: {} walks, {}; {}: {}; " +
                "chosen λ={} — in {} ms; of the values no item of the other folds has: {}",
            portions.items,
            values.valueCount,
            design.itemWidth,
            design.valueWidth,
            values.hints.size,
            if (last == null) "from scratch" else "from the last state",
            walks,
            where,
            timing,
            tried.joinToString("; ") { it.told() },
            chosen.lambda,
            System.currentTimeMillis() - started,
            tried.joinToString("; ") { it.unseenTold() },
        )
        return fitted.handedOver(reportOf(chosen, tried, millis), scored.heldOut(), scored.shares())
    }

    /**
     * Every penalty of [lambdas], every fold of each, descended together over the portions by the [plan] on the
     * [design]: what each held out, its folds' weights; null when no item has a value of the facet. From the [survey]'s
     * start from scratch, else from the last state's weights.
     */
    private fun descended(design: Design, survey: Survey?, lambdas: List<Double>, plan: Plan): List<Tried>? {
        val count = lambdas.size * folds
        val layout = ModelLayout(design, rank)
        // Every penalty from its own last weights carried to this facet — those of the penalty nearest to it when it
        // had none — or from a fresh start: from scratch, or when the last weights are all nought, where the two
        // sides' products have no gradient and stay
        val used = survey?.used ?: BooleanArray(values.valueCount) { true }
        fun start(lambda: Double, k: Int): FloatArray =
            last
                ?.let { l -> layout.carried(l.nearest(lambda)[k], l.design) }
                ?.takeIf { theta -> theta.any { it != 0f } } ?: layout.start(k + 1L, used, survey?.common ?: 0f)
        // Model q: the penalty q / folds, the fold q % folds
        val starts = List(count) { q -> start(lambdas[q / folds], q % folds) }
        val models = Models(lambdas, starts, layout)
        // Of no more items than a portion holds — of the survey's one portion, or the last state's — every model
        // searched over them whole; else the descent over the portions
        val alone = survey == null || survey.portions == 1
        if (alone && portions.items <= TaskPortions.MOST)
            searchedWhole(design, survey, models)?.let {
                return it.tried
            }
        val descent = Descent(portions, design, rank, models.penalties, models.ofFold, card, plan, survey == null, stop)
        descent.run(starts, models.heldOf, models.unseenOf)
        walks += plan.walks
        where = descent.where
        timing = descent.timing.told()
        return if (!descent.assigned) null else models.tried(descent.point)
    }

    /**
     * Every one of the [models] searched over the items whole ([WholeSearch]) on the [design]; null when they came in
     * more than one portion after all, their sums held out back to nought for the descent.
     */
    private fun searchedWhole(design: Design, survey: Survey?, models: Models): Searched? =
        try {
            val search =
                WholeSearch(portions, design, rank, models.penalties, models.ofFold, card, survey == null, stop)
            search.run(models.starts, models.heldOf, models.unseenOf)
            walks += 1
            where = search.where
            timing = search.timing.told()
            Searched(if (search.assigned) models.tried(search.point) else null)
        } catch (e: NotWhole) {
            log.info("{}: descended over the portions", e.message)
            walks += 1
            models.cleared()
            null
        }

    /** What a search of the items whole made: what every penalty held out, null when no item has a value. */
    private class Searched(val tried: List<Tried>?)

    /**
     * The models of a fitting of [lambdas] and [starts]: model q of the penalty q / folds and the fold q % folds, every
     * penalty's sums of what its folds held out of every pair ([held]) and of the values unseen ([unseen]).
     */
    private inner class Models(val lambdas: List<Double>, val starts: List<FloatArray>, val layout: ModelLayout) {
        private val f = this@PortionFitting.folds
        private val count = lambdas.size * f
        val penalties = FloatArray(count) { lambdas[it / f].toFloat() }
        val ofFold = IntArray(count) { it % f }
        private val held = List(lambdas.size) { Bilinear.Held() }
        private val unseen = List(lambdas.size) { Bilinear.Held() }
        val heldOf = List(count) { held[it / f] }
        val unseenOf = List(count) { unseen[it / f] }

        /** Every sum held out back to nought. */
        fun cleared() = (held + unseen).forEach { it.clear() }

        /** What every penalty held out and its folds' weights of the [point]. */
        fun tried(point: EnsemblePoint): List<Tried> = lambdas.mapIndexed { j, lambda ->
            Tried(lambda, held[j], unseen[j], List(f) { layout.weights(point.take(j * f + it)) })
        }
    }

    /**
     * THE LAST WALK: every item scored by the model [fitted] kept, as it will be scored — the pairs the user answered
     * on by the folds that held them out, the values' shares of the works.
     */
    private fun scored(fitted: FittedBilinear): ScoredPortions {
        val scored = ScoredPortions(fitted.scoringOf(values))
        portions.forEach { portion ->
            if (stop()) throw CancellationException("the fitting is given up")
            scored.add(portion)
        }
        return scored
    }

    /** What the fitting tried and chose, by their settings — the rank and the penalty — and held-out losses. */
    private fun reportOf(chosen: Tried, tried: List<Tried>, millis: Long): FitReport {
        fun settings(t: Tried) = mapOf(RANK_NAME to rank.toString(), PENALTY to t.lambda.toString())
        return FitReport(
            settings(chosen),
            chosen.held.mean,
            chosen.held.error,
            tried.map { FitTry(settings(it), it.held.mean, it.held.error, millis) },
            fromScratch = last == null,
        )
    }

    /** A penalty tried: what its folds held out, of every value and of those unseen, and the folds' weights. */
    private class Tried(
        val lambda: Double,
        val held: Bilinear.Held,
        val unseen: Bilinear.Held,
        val folds: List<Weights>,
    ) {
        /** As the log tells it: the penalty and the held-out loss. */
        fun told() = "λ=$lambda: %.4f ± %.4f".format(held.mean, held.error)

        /** As the log tells it: the held-out loss of the values no item of the other folds has. */
        fun unseenTold() =
            "λ=$lambda: " + if (unseen.weight > 0) "%.4f ± %.4f".format(unseen.mean, unseen.error) else "none"
    }

    /**
     * The penalties tried: every one of the grid when fitted from scratch; gone on from the [last] state, its penalty
     * and the two beside it on the grid — the choice moves with the data, a step at a time — and the strongest besides,
     * whose model is all but the values' base rates: of a choice gone where every model held out worse than it — the
     * descent over the portions overshooting at the weak penalties — the next is the strongest, and the refits walk
     * back from there (2026-11-19: of author.today's tags λ = 1e-7 chosen of 1e-6 and 1e-7 at a held-out loss of 0.42,
     * a hundred times the base rates', every refit after the same).
     */
    private fun penaltiesTried(): List<Double> {
        val l = last ?: return GRID
        val beside =
            GRID.indices
                .minBy { abs(ln(GRID[it] / l.lambda)) }
                .let { at -> GRID.subList(maxOf(0, at - 1), minOf(GRID.size, at + 2)) }
        return (listOf(GRID.first()) + beside).distinct()
    }

    /**
     * Kept with the chosen weights: the folds' weights of the penalties beside the [chosen] one on the grid, each of
     * the next refit's to go on from.
     */
    private fun beside(chosen: Tried, tried: List<Tried>): Map<Double, List<Weights>> {
        val at = GRID.indices.minBy { abs(ln(GRID[it] / chosen.lambda)) }
        val beside = setOfNotNull(GRID.getOrNull(at - 1), GRID.getOrNull(at + 1))
        return tried.filter { it.lambda in beside }.associate { it.lambda to it.folds }
    }

    private companion object {
        /** The names the settings are reported by ([FitReport]): the rank of the factorisation, the penalty. */
        const val RANK_NAME = "r"
        const val PENALTY = "λ"

        /**
         * The penalties tried: from [BilinearSuggester.STRONGEST] — at which every weight is all but nought — down
         * tenfold at a time to [BilinearSuggester.WEAKEST], every one of them when fitted from scratch; the last one
         * chosen and the two beside it when gone on from the state fitted last. Every one down to the weakest: the
         * held-out loss is not a valley — the characters' was flat from 10 to 0.1, worse at 0.01 and least at 1e-4 —
         * and a search stopped at the first rise never reaches the better side of it.
         */
        val GRID =
            generateSequence(BilinearSuggester.STRONGEST) { it / 10 }
                .takeWhile { it >= BilinearSuggester.WEAKEST }
                .toList()
    }
}
