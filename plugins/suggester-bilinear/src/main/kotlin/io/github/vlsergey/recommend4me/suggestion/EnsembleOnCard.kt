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

    /**
     * The held-out loss of the models in the first [k] slots, of their points in the main memory ([points], every
     * slot's, A there too), into [into], and of the values no item outside a model's fold has ([Problem.unseenOutside]
     * of [p]) into [unseen] besides — as [Bilinear.heldOutLoss] makes it: the items' and the values' coordinates made
     * of them here, then every pair of the items of each fold at once ([PairsOnCard.heldOut]), where the processor went
     * through them one by one — of the tags of 75 707 works and 9871 values, a minute and more on one thread. Of the
     * pairs the user answered on ([Problem.answered]), the score every model gives those of the items of its fold into
     * its array of [answers], at their places among them, made there too.
     */
    fun heldOut(
        p: Problem,
        k: Int,
        points: Array<Pieces>,
        into: List<Bilinear.Held>,
        unseen: List<Bilinear.Held>,
        answers: List<FloatArray>?,
    ) {
        val m = p.m
        val fresh = HashMap<Int, BooleanArray>()
        val unseenOf = IntArray(k * m)
        for (slot in 0 until k) {
            val fold = heldOut[slotModel[slot]]
            val of = fresh.getOrPut(fold) { p.unseenOutside(fold) }
            for (v in 0 until m) if (of[v]) unseenOf[slot * m + v] = 1
        }
        slots.send(k, points, withA = false)
        products.coordinates(k * r, aOnCard = false, keep = true)
        pairs.coordinates(k, onCard.aT)
        val held = pairs.heldOut(k, slotModel, heldOut, unseenOf, onCard.uT)
        val count = p.answered.size
        for (slot in 0 until k) {
            val at = slot * PairsOnCard.HELD_SUMS
            into[slotModel[slot]].add(held.sums, at)
            unseen[slotModel[slot]].add(held.sums, at + PairsOnCard.HELD_SUMS / 2)
            answers?.let { a ->
                p.answered.scoreFold(heldOut[slotModel[slot]], p.foldOf, a[slotModel[slot]]) { place, _, _, _ ->
                    held.answers[slot * count + place]
                }
            }
        }
    }

    /** The card waited for, the time since [at] told to [into]: the time now. */
    private inline fun timed(at: Long, into: (Long) -> Unit): Long {
        onCard.cublas.synchronize()
        val now = System.nanoTime()
        into(now - at)
        return now
    }
}
