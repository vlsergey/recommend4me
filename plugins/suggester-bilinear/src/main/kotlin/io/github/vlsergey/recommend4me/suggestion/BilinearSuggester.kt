package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.onnx.Onnx

/**
 * THE VALUES OF A FACET BY ONE MODEL ([Bilinear]) of every item and every value — the tags, the universes, the
 * characters, the pairings alike: every input of the item the model of the grades reads, the item's other facets, the
 * value's name and groups, the pair's own features.
 *
 * LEARNT A PORTION OF THE ITEMS AT A TIME ([TaskPortions]): no fitting holds more than a portion's items, of a source
 * of a million works (user, 2026-11-17). A walk over the portions reads every work anew, so a fitting walks them a few
 * times ([PortionFitting]): from scratch once to see them ([Survey]), a few times to descend ([Descent]: every model's
 * points moved by the gradient of a portion at a time, [Plan]), and once to score them by the model kept
 * ([ScoredPortions]). A facet of no more works than a portion holds is fitted over them whole instead: every model's
 * minimum searched by L-BFGS ([WholeSearch]) — of author.today's tags a held-out loss of 0.0007 where the descent held
 * 0.002 at its best, in a tenth of the time (2026-11-19).
 *
 * EVERY ITEM IS JUDGED BY A MODEL THAT DID NOT LEARN FROM IT. The items fall into [FOLDS] folds by their ids; a model
 * is fitted on the items of all folds but one, and judges the items of that one. What a work's labels say of it does
 * not vouch for itself: an answer on a work does not move its own chances — the next likeliest are offered after it.
 * The scores the folds of the chosen setting give the values the user answered on are handed over with the fitting
 * ([FittedSuggester.heldOut]): what the application calibrates the chances by.
 *
 * NO NUMBER OF THE MODEL IS CHOSEN BY HAND: the penalty is chosen among the penalties from [STRONGEST] down by the
 * held-out loss of every fold summed — the strongest penalty within one standard error of the least —, every penalty's
 * every fold descending together, in the same walks.
 *
 * ONE VERSION (owner, 2026-11-19: "merge bilinear into one version"): "bilinear-2", the one that searched a portion's
 * works whole beside "bilinear-1", which descended them as any other.
 *
 * A REFIT GOES ON FROM THE STATE FITTED LAST: its weights carried to the facet as it is now, by the keys of the
 * features and the values, its layout's scales kept, and the penalty chosen again beside the last one — three walks
 * where a fitting from scratch takes five to eight. From scratch only the first time, or when the last state does not
 * read the facet.
 */
class BilinearSuggester
internal constructor(
    /** The runtime whose libraries of the card the products go through, when there is one: on the processor without. */
    private val onnx: Onnx?
) : FacetSuggester {
    constructor() : this(null)

    override val id = "bilinear-2"

    override fun fit(portions: TaskPortions, previous: FittedSuggester?, stop: () -> Boolean): FittedSuggester? {
        if (portions.values.valueCount == 0 || portions.items == 0) return null
        // GONE ON FROM THE STATE FITTED LAST: a few answers or works more move the optimum a little, and the walk to it
        // from the last is short
        val last = (previous as? FittedBilinear)?.takeIf { it.folds.size == FOLDS && it.rank == RANK }
        return PortionFitting(portions, last, onnx?.cudaLibraries()?.let(Cublas::open), stop).fitted()
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? =
        FittedBilinear.read(bytes, task, FOLDS)

    /** A work is scored of its own features by the coordinates of the values kept: no other work read. */
    override fun scoring(model: ByteArray, task: SuggestionTask): SuggestionScores? =
        Scoring.read(model)?.takeIf { it.valueKeys == task.valueKeys && it.design.readsItems(task) }?.on(task)

    /**
     * Every value's share of the works the model was fitted on, kept with the scoring; none of a scoring kept before it
     * was — till the next fitting.
     */
    override fun weights(model: ByteArray): DoubleArray? = Scoring.read(model)?.weights()

    companion object {
        /** The folds an item is held out in: four fifths of the items teach the model judging the fifth. */
        const val FOLDS = 5

        /** The rank: how many coordinates the items and the values meet in. */
        const val RANK = 16

        /**
         * The penalties tried ([PortionFitting]): from [STRONGEST] — at which every weight is all but nought — down
         * tenfold at a time to [WEAKEST].
         */
        const val STRONGEST = 10.0
        const val WEAKEST = 1e-7
    }
}
