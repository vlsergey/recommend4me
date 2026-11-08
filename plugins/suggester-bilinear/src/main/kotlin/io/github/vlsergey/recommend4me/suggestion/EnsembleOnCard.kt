package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Floats

/**
 * A STEP OF EVERY MODEL OF AN ENSEMBLE ON THE CARD ([Ensemble] with its items' features and its pairs there): the loss
 * and the gradient of every slot still searching, every number of it there — the items' features' products
 * ([ItemProducts]), the values' and the names' ([PairsOnCard]), the pairs and the names, the penalty — of the point in
 * the slots ([slots]), the gradient left there. The card waited for before every reading of the time ([timing]): what
 * it was handed runs while the processor goes on, and its time would be told of whatever waits for it next.
 */
internal class EnsembleOnCard(
    private val r: Int,
    private val products: ItemProducts,
    private val onCard: ItemProducts.OnCard,
    private val pairs: PairsOnCard,
    /** The model each slot holds, the held-out fold, the training's weight and the penalty of every model. */
    private val slotModel: IntArray,
    private val heldOut: IntArray,
    private val totals: FloatArray,
    private val lambdas: FloatArray,
    private val timing: FitTiming,
) {
    /** The point and the gradient of every slot on the card. */
    val slots = pairs.slots(onCard.aT, onCard.gA)

    /**
     * The loss of the first [k] slots into [losses], of their points on the card, their gradients left there — A there
     * unless [aOnCard] is false, when the items' features' product sends it from the main memory.
     */
    fun step(k: Int, losses: FloatArray, aOnCard: Boolean = true) {
        val kr = k * r
        var at = System.nanoTime()
        products.coordinates(kr, aOnCard, keep = true)
        at = timed(at) { timing.features += it }
        pairs.coordinates(k, onCard.aT)
        at = timed(at) { timing.rest += it }
        pairs.evaluate(k, slotModel, heldOut, totals, onCard.uT, onCard.duT)
        at = timed(at) { timing.pairs += it }
        // dAᵀ = dUᵀ X, then the names' part onto it there; dBᵀ = dVᵀ Z
        products.gradient(kr, aOnCard, duOnCard = true, bringBack = false)
        pairs.gradient(k, onCard.gA)
        at = timed(at) { timing.features += it }
        pairs.finish(k, slotModel, lambdas, slots, losses)
        timed(at) { timing.rest += it }
    }

    /**
     * [step] of the points in the main memory ([points], every slot's, [Ensemble]'s — A there too unless [aOnCard]),
     * their gradients taken back into [grads] and the slopes of A into [gA] unless they stay on the card.
     */
    fun step(k: Int, losses: FloatArray, aOnCard: Boolean, points: Array<Pieces>, grads: Array<Pieces>, gA: Floats) {
        slots.send(k, points, withA = false)
        step(k, losses, aOnCard)
        slots.bring(k, grads, withA = false)
        if (!aOnCard) onCard.gA.download(gA, 0, 0, k * slots.sizes[0])
    }

    /** The card waited for, the time since [at] told to [into]: the time now. */
    private inline fun timed(at: Long, into: (Long) -> Unit): Long {
        onCard.cublas.synchronize()
        val now = System.nanoTime()
        into(now - at)
        return now
    }
}
