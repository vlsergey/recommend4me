package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Simd
import kotlin.concurrent.withLock

/**
 * EVERY MODEL OF A FITTING IN ONE PRODUCT ([Bilinear], the same model and the same loss), OF THE ITEMS OF ONE PORTION
 * ([Problem]): the folds and the penalties tried, side by side, at their points kept beyond the portion ([at]). Their
 * weights A lie one under another, transposed ([TransposedLayout]) — of a model its rank's rows of the item's features
 * — so the coordinates of every item by every model are ONE product of the items' features, Uᵀ = Aᵀ Xᵀ, and the
 * gradient back ONE more, dAᵀ = dUᵀ X ([ItemProducts]): the features of the items read twice a step for all the models,
 * not twice for each.
 *
 * THE PAIRS OF THE ITEMS AND THEIR VALUES BY PRODUCTS TOO: a block of the items' rows against every value, S = U Vᵀ,
 * the slopes of the pairs that weigh anything written over it, and the gradient back by two products, dU = S V and dV =
 * Sᵀ U. The names as texts of a work ([Problem.hints]) the same, a group of them at a time ([NamesOfEnsemble]).
 *
 * ONE THREAD, THE LIBRARY SHARING EVERY PRODUCT OUT AMONG ITS OWN ([Blas.wide]): no thread of ours. Every number of the
 * portion where the products read it, made once for the portion.
 */
internal class Ensemble(
    private val p: Problem,
    val rank: Int,
    private val lambdas: FloatArray,
    private val heldOut: IntArray,
    /** The card, when the launch asks for it: the items' features kept there and both their products made there. */
    card: Cublas? = null,
    /** The point and the gradient of every model: kept beyond the portion when given, else made for it alone. */
    private val at: EnsemblePoint = EnsemblePoint(lambdas.size, rank, p.design.itemWidth, p.design.valueWidth, p.m),
    /** Where the time of the steps went, summed over the portions. */
    private val spent: FitTiming = FitTiming(),
) : AutoCloseable {
    private val n = p.n
    private val m = p.m
    private val dz = p.design.valueWidth
    private val r = rank

    /** How many models. */
    val count = lambdas.size

    init {
        require(at.count == count && at.r == r && at.dx == p.design.itemWidth && at.dz == dz && at.m == m) {
            "a point of other models"
        }
    }

    /** The numbers of one model, in the order of [TransposedLayout]: Aᵀ, Bᵀ, Eᵀ, the biases, the pair's own weights. */
    val size = at.layout.size

    private val aT = at.aT
    private val bT = at.bT
    private val eT = at.eT
    private val bias = at.bias
    private val pair = at.pair
    private val gA = at.gA
    private val gB = at.gB
    private val gE = at.gE
    private val gBias = at.gBias
    private val gPair = at.gPair
    private val vT = at.vT

    /** The items' coordinates of every model and their slopes, transposed: rank rows of every item. */
    private val uT = Floats(count * r * n)
    private val duT = Floats(count * r * n)

    /** A row of the pairs that weigh anything, read by the held-out loss: made once. */
    private val pairRow = p.row()

    /** The pairs on the processor, when they are not on the card: made at the first need. */
    private val onProcessor by lazy { PairsOnProcessor(p, r, heldOut, uT, vT, bias, pair, duT, gE, gBias, gPair) }

    /** The names as texts of a work: their coordinates of every model, their loss and their slopes. */
    private val h = p.hints.size
    private val names = NamesOfEnsemble(p, count, r, aT, vT, bias, gA, gE, gBias, gPair)

    /** The total weight of every model's training: the loss a mean over it, the penalty the same at any size. */
    private val totals =
        FloatArray(count) { k ->
            var total = 0f
            for (i in 0 until n) if (p.foldOf[i] != heldOut[k]) total += p.rowWeight[i]
            p.hints.forEach { total += it.among.size }
            if (total == 0f) 1f else total
        }

    /** The model each slot of the products holds: every model in its own. */
    private val slotModel = IntArray(count) { it }

    /** The two products of the items' features: on the card when it is asked for and has room. */
    private val products = ItemProducts(p, count, r, card, aT, uT, duT, gA)
    private val onCard = products.onCard

    /** [work] holding the card's lock when the products are made there: one thread at a time on it. */
    private fun <T> onTheCard(work: () -> T): T =
        onCard?.cublas?.lock.let { lock -> if (lock == null) work() else lock.withLock(work) }

    /** Whether the items' features and their products are on the card. */
    val featuresOnCard: Boolean
        get() = onCard != null

    /**
     * The pairs and the names on the card too, beside the items' features there ([PairsOnCard]): null when the kernels
     * cannot be made or the card has no room — the processor's then.
     */
    private val pairsOnCard: PairsOnCard? = onCard?.let { PairsOnCard.open(p, it.cublas, count, r) }

    /** Whether the pairs and the names are worked out on the card. */
    val pairsOnCardUsed: Boolean
        get() = pairsOnCard != null

    override fun close() {
        pairsOnCard?.close()
        products.close()
    }

    /** Of every model, its pieces of the point and of the gradient: told once. */
    private val points = Array(count) { at.pieces(it, gradient = false) }
    private val grads = Array(count) { at.pieces(it, gradient = true) }

    /** A step of every model on the card, when the items' features and the pairs are there. */
    private val stepOnCard: EnsembleOnCard? = pairsOnCard?.let {
        EnsembleOnCard(r, products, onCard!!, it, slotModel, heldOut, totals, lambdas, spent)
    }

    /**
     * The loss of every model at its point into [losses], its gradient into the gradient of the point ([at]):
     * [Bilinear.lossAndGradient] of every model at once, of the items of the portion.
     */
    fun step(losses: FloatArray) = onTheCard { Blas.wide { evaluate(losses) } }

    private fun evaluate(losses: FloatArray) {
        val onCardStep = stepOnCard
        if (onCardStep != null) {
            onCardStep.step(count, losses, points, grads, gA)
            return
        }
        val kr = count * r
        // Uᵀ = Aᵀ Xᵀ and Vᵀ = Bᵀ Zᵀ + Eᵀ of every model, one product each
        var started = System.nanoTime()
        products.coordinates(kr)
        spent.features += System.nanoTime() - started
        started = System.nanoTime()
        values()
        if (h > 0) names.coordinates(count)
        spent.rest += System.nanoTime() - started
        started = System.nanoTime()
        for (model in 0 until count) {
            val scale = 1 / totals[model]
            val common = pair[model * Bilinear.PAIR + 1]
            var loss = onProcessor.loss(model, model, scale, common)
            if (h > 0) loss += names.loss(model, scale, common)
            losses[model] = loss * scale
        }
        spent.pairs += System.nanoTime() - started
        // dBᵀ = dVᵀ Z and dAᵀ = dUᵀ X of every model, one product each; the names' part added to dA after
        started = System.nanoTime()
        Blas.gemm(false, false, kr, dz, m, gE, 0, m, p.z.held, 0, dz, gB, 0, dz)
        spent.rest += System.nanoTime() - started
        started = System.nanoTime()
        products.gradient(kr)
        spent.features += System.nanoTime() - started
        started = System.nanoTime()
        if (h > 0) names.gradient(count)
        addPenalty(losses)
        spent.rest += System.nanoTime() - started
    }

    /** Vᵀ = Bᵀ Zᵀ + Eᵀ of every model, one product. */
    private fun values() {
        val kr = count * r
        Blas.gemm(false, true, kr, m, dz, bT, 0, dz, p.z.held, 0, dz, vT, 0, m)
        Simd.axpy(1f, eT, 0, vT, 0, kr * m)
    }

    /**
     * The penalty on every weight of every model, once the products have written the gradient: added to [losses], its
     * slope to the gradient — the pair's own weights too, but the common bias, the base rate of every value.
     */
    private fun addPenalty(losses: FloatArray) {
        for (model in 0 until count) {
            val lambda = lambdas[model]
            val common = pair[model * Bilinear.PAIR + 1]
            val theta = points[model]
            val gradient = grads[model]
            var penalty = 0f
            for (q in theta.floats.indices) {
                penalty += Simd.dot(theta.floats[q], theta.at[q], theta.floats[q], theta.at[q], theta.count[q])
                Simd.axpy(2 * lambda, theta.floats[q], theta.at[q], gradient.floats[q], gradient.at[q], theta.count[q])
            }
            gPair[model * Bilinear.PAIR + 1] = gPair[model * Bilinear.PAIR + 1] - 2 * lambda * common
            losses[model] += lambda * (penalty - common * common)
        }
    }

    /**
     * The held-out loss of every model at its point, the pairs of the items of its fold that weigh anything, as
     * [Bilinear.heldOutLoss] takes them, into [into] and, of the values [unseenOf] its fold — no item outside it has
     * them — into [unseen].
     */
    fun heldOut(
        into: List<Bilinear.Held>,
        unseen: List<Bilinear.Held>,
        unseenOf: (Int) -> BooleanArray,
        /** Of the pairs on the card, there; false: on the processor, of the same coordinates — the measure of it. */
        pairsThere: Boolean = true,
    ) = onTheCard {
        Blas.wide {
            val card = stepOnCard?.takeIf { pairsThere }
            if (card != null) {
                card.heldOut(m, count, points, into, unseen, unseenOf)
                return@wide
            }
            products.coordinates(count * r)
            values()
            for (model in 0 until count) heldOutOf(model, into[model], unseen[model], unseenOf(heldOut[model]))
        }
    }

    /** [heldOut] of the [model] on the processor, of the items' and the values' coordinates made. */
    private fun heldOutOf(model: Int, into: Bilinear.Held, unseen: Bilinear.Held, fresh: BooleanArray) {
        val fold = heldOut[model]
        val uAt = model * r * n
        val vAt = model * r * m
        val example = pair[model * Bilinear.PAIR]
        val common = pair[model * Bilinear.PAIR + 1]
        for (i in 0 until n) {
            if (p.foldOf[i] != fold) continue
            val len = p.pairsOf(i, pairRow)
            for (j in 0 until len) {
                val v = pairRow.values[j]
                var dot = bias[model * m + v] + common + example * pairRow.examples[j]
                for (q in 0 until r) dot += uT[uAt + q * n + i] * vT[vAt + q * m + v]
                val l = (Bilinear.softplus(dot) - pairRow.labels[j] * dot).toDouble()
                into.add(pairRow.weights[j].toDouble(), l)
                if (fresh[v]) unseen.add(pairRow.weights[j].toDouble(), l)
            }
        }
    }

    /**
     * The loss and the gradient of every model at [thetas] ([Bilinear]'s layout) in one step of the products: what
     * [Bilinear.lossAndGradient] gives each — the measure this is checked against.
     */
    internal fun lossesAndGradients(thetas: List<FloatArray>): Pair<FloatArray, List<FloatArray>> {
        thetas.forEachIndexed { model, theta -> at.put(model, theta) }
        val losses = FloatArray(count)
        step(losses)
        return losses to List(count) { at.slope(it) }
    }

    /** Where the time of the steps went ([FitTiming]). */
    val timing: String
        get() = spent.told()
}
