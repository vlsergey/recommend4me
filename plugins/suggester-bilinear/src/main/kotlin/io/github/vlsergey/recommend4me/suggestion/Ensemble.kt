package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Simd
import java.util.concurrent.CancellationException
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Ensemble::class.java)

/**
 * EVERY MODEL OF A FITTING IN ONE PRODUCT ([Bilinear], the same model and the same loss): the folds and the penalties
 * tried, side by side. Their weights A lie one under another, transposed ([TransposedLayout]) — of a model its rank's
 * rows of the item's features — so the coordinates of every item by every model are ONE product of the items' features,
 * Uᵀ = Aᵀ Xᵀ, and the gradient back ONE more, dAᵀ = dUᵀ X ([ItemProducts]): the features of the items, a gigabyte and a
 * half of a catalogue of games, read twice a step for all the models, not twice for each.
 *
 * THE PAIRS OF THE ITEMS AND THEIR VALUES BY PRODUCTS TOO: a block of the items' rows against every value, S = U Vᵀ,
 * the slopes of the pairs that weigh anything written over it, and the gradient back by two products, dU = S V and dV =
 * Sᵀ U. The names as texts of a work ([Problem.hints]) the same, a group of them at a time ([NamesOfEnsemble]).
 *
 * ONE THREAD, THE LIBRARY SHARING EVERY PRODUCT OUT AMONG ITS OWN ([Blas.wide]): no thread of ours. EVERY NUMBER WHERE
 * THE PRODUCTS READ IT, made once for the fitting: nothing copied out and back between them, no array made a step.
 *
 * The models step together, each by a search of its own ([Search]): a step works out the function at the point every
 * one of them asks for. A model whose search has ended leaves the product; the last one takes its place, so the
 * products are of the models still searching alone.
 */
internal class Ensemble(
    private val p: Problem,
    val rank: Int,
    private val lambdas: FloatArray,
    private val heldOut: IntArray,
    /** The card, when the launch asks for it: the items' features kept there and both their products made there. */
    card: Cublas? = null,
) : AutoCloseable {
    private val n = p.n
    private val m = p.m
    private val dx = p.design.itemWidth
    private val dz = p.design.valueWidth
    private val r = rank

    /** How many models. */
    val count = lambdas.size

    /** A model's weights as the products read them, and as [Bilinear] does. */
    private val layout = TransposedLayout(dx, dz, m, r)

    /** The numbers of one model, in the order its search reads them: Aᵀ, Bᵀ, Eᵀ, the biases, the pair's own weights. */
    val size = layout.size

    // The point every model's search asks for, a piece of each kind one model after another — and the gradient there
    private val aT = Floats(count * r * dx)
    private val bT = Floats(count * r * dz)
    private val eT = Floats(count * r * m)
    private val bias = Floats(count * m)
    private val pair = Floats(count * Bilinear.PAIR)
    private val gA = Floats(count * r * dx)
    private val gB = Floats(count * r * dz)
    /** The gradient of E, which is dV: the values' coordinates' slopes, carried back to B from here. */
    private val gE = Floats(count * r * m)
    private val gBias = Floats(count * m)
    private val gPair = Floats(count * Bilinear.PAIR)

    /** The items' and the values' coordinates of every model, transposed: rank rows of every item, of every value. */
    private val uT = Floats(count * r * n)
    private val duT = Floats(count * r * n)
    private val vT = Floats(count * r * m)

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

    /** The model each slot of the products holds now: the models still searching, the first [active] slots. */
    private val slotModel = IntArray(count) { it }
    private val modelSlot = IntArray(count) { it }
    private var active = count

    /** Where the pieces of the model in [slot] lie, in the point or in the gradient. */
    private fun pieces(slot: Int, gradient: Boolean) =
        Pieces(
            if (gradient) arrayOf(gA, gB, gE, gBias, gPair) else arrayOf(aT, bT, eT, bias, pair),
            intArrayOf(slot * r * dx, slot * r * dz, slot * r * m, slot * m, slot * Bilinear.PAIR),
            intArrayOf(r * dx, r * dz, r * m, m, Bilinear.PAIR),
        )

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

    /** Of every slot, its pieces of the point and of the gradient: told once. */
    private val points = Array(count) { pieces(it, gradient = false) }
    private val grads = Array(count) { pieces(it, gradient = true) }

    /**
     * What a fitting of every model gives: the weights of each, in [Bilinear]'s layout, how many losses each took, when
     * each ended.
     */
    class Fits(val weights: List<FloatArray>, val losses: IntArray, val endedAt: LongArray)

    // Where a fitting's time went, summed over its rounds: told in the log of the fitting ([timing])
    private var rounds = 0
    private var searchNanos = 0L
    private var featuresNanos = 0L
    private var pairsNanos = 0L
    private var restNanos = 0L

    /**
     * Where the time of the fitting went: its rounds; the searches' own work (the directions, the steps); the two
     * products of the items' features with what they send and take back; the pairs and the names; the rest — the
     * values' products, the penalty.
     */
    val timing: String
        get() =
            "%d rounds: the searches %d ms, the items' features %d ms, the pairs and names %d ms, the rest %d ms"
                .format(
                    rounds,
                    searchNanos / NANOS_IN_MILLI,
                    featuresNanos / NANOS_IN_MILLI,
                    pairsNanos / NANOS_IN_MILLI,
                    restNanos / NANOS_IN_MILLI,
                )

    /** Whether the searches of the last fitting were on the card. */
    var searchesOnCard = false
        private set

    /**
     * Every model fitted from its start ([starts], each of [Bilinear]'s layout), [maxSteps] steps at most. THE SEARCHES
     * ON THE CARD when the features are there and it has room for their vectors — the weights A written and read there,
     * never leaving it; else in the main memory.
     */
    fun fit(starts: List<FloatArray>, maxSteps: Int, stop: () -> Boolean): Fits = onTheCard {
        Blas.wide {
            val c = onCard
            if (c != null) {
                SearchOnCard(c.cublas).use { space ->
                    try {
                        val scratch = space.make(size - r * dx)
                        fun place(slot: Int, gradient: Boolean) =
                            CardPieces(
                                c.cublas,
                                if (gradient) c.gA else c.aT,
                                slot.toLong() * r * dx,
                                r * dx,
                                // The pieces but A, which lies on the card: B, E, the biases, the pair's own
                                (if (gradient) grads else points)[slot].let { all ->
                                    Pieces(
                                        all.floats.copyOfRange(1, PIECES),
                                        all.at.copyOfRange(1, PIECES),
                                        all.count.copyOfRange(1, PIECES),
                                    )
                                },
                                scratch,
                            )
                        val searches = List(count) { Search(space, size, maxSteps) }
                        searchesOnCard = true
                        return@wide fitIn(
                            searches,
                            Array(count) { place(it, false) },
                            Array(count) { place(it, true) },
                            aOnCard = true,
                            starts,
                            stop,
                        )
                    } catch (e: NoRoomOnCard) {
                        // Left in the main memory: the vectors made on the card freed as the space closes
                        log.info("the searches of {} models in the main memory: {}", count, e.message)
                    }
                }
            }
            searchesOnCard = false
            fitIn(List(count) { Search(OnHost, size, maxSteps) }, points, grads, aOnCard = false, starts, stop)
        }
    }

    /**
     * Every model fitted by its search of [searches], its point written into [places] and its gradient read from
     * [gradients] of its slot.
     */
    private fun <V> fitIn(
        searches: List<Search<V>>,
        places: Array<out Place<V>>,
        gradients: Array<out Place<V>>,
        aOnCard: Boolean,
        starts: List<FloatArray>,
        stop: () -> Boolean,
    ): Fits {
        searches.forEachIndexed { k, s -> s.begin(layout.ordered(starts[k])) }
        val losses = FloatArray(count)
        val endedAt = LongArray(count)
        while (active > 0) {
            if (stop()) throw CancellationException("the search is given up")
            var at = System.nanoTime()
            for (slot in 0 until active) searches[slotModel[slot]].propose(places[slot])
            searchNanos += System.nanoTime() - at
            evaluate(losses, aOnCard)
            at = System.nanoTime()
            for (slot in 0 until active) searches[slotModel[slot]].answer(losses[slot], places[slot], gradients[slot])
            searchNanos += System.nanoTime() - at
            rounds++
            // A model whose search ended leaves the products: the last one still searching takes its slot
            var slot = 0
            while (slot < active) {
                val model = slotModel[slot]
                if (searches[model].done) {
                    endedAt[model] = System.currentTimeMillis()
                    leave(slot)
                } else slot++
            }
        }
        return Fits(
            searches.map { layout.standard(it.result()) },
            IntArray(count) { searches[it].evaluations },
            endedAt,
        )
    }

    /**
     * The model in [slot] leaves the products, the last of those still searching moved into its place — nothing of it
     * copied: its search writes its whole point there at the next step.
     */
    private fun leave(slot: Int) {
        val last = active - 1
        if (slot != last) {
            val moved = slotModel[last]
            val gone = slotModel[slot]
            slotModel[slot] = moved
            modelSlot[moved] = slot
            slotModel[last] = gone
            modelSlot[gone] = last
        }
        active--
    }

    /**
     * The loss of the model in every slot still searching at its point, into [losses]; its gradient into the gradient's
     * pieces. [Bilinear.lossAndGradient] of every model at once. [aOnCard]: the searches are on the card — the weights
     * A are read there, and their gradient left there.
     */
    internal fun evaluate(losses: FloatArray, aOnCard: Boolean = false) {
        val onPairs = pairsOnCard
        if (onPairs != null) {
            evaluateOnCard(onPairs, losses, aOnCard)
            return
        }
        val k = active
        val kr = k * r
        // Uᵀ = Aᵀ Xᵀ and Vᵀ = Bᵀ Zᵀ + Eᵀ of every model, one product each
        var at = System.nanoTime()
        products.coordinates(kr, aOnCard)
        featuresNanos += System.nanoTime() - at
        at = System.nanoTime()
        Blas.gemm(false, true, kr, m, dz, bT, 0, dz, p.z.held, 0, dz, vT, 0, m)
        Simd.axpy(1f, eT, 0, vT, 0, kr * m)
        if (h > 0) names.coordinates(k)
        restNanos += System.nanoTime() - at
        at = System.nanoTime()
        for (slot in 0 until k) {
            val model = slotModel[slot]
            val scale = 1 / totals[model]
            val common = pair[slot * Bilinear.PAIR + 1]
            var loss = onProcessor.loss(slot, model, scale, common)
            if (h > 0) loss += names.loss(slot, scale, common)
            losses[slot] = loss * scale
        }
        pairsNanos += System.nanoTime() - at
        // dBᵀ = dVᵀ Z and dAᵀ = dUᵀ X of every model, one product each; the names' part added to dA after
        at = System.nanoTime()
        Blas.gemm(false, false, kr, dz, m, gE, 0, m, p.z.held, 0, dz, gB, 0, dz)
        restNanos += System.nanoTime() - at
        at = System.nanoTime()
        products.gradient(kr, aOnCard)
        featuresNanos += System.nanoTime() - at
        at = System.nanoTime()
        if (h > 0) {
            names.gradient(k)
            // The gradient of A with the names' part, back where the searches read it
            if (aOnCard) onCard!!.gA.upload(gA, 0, 0, kr * dx)
        }
        addPenalty(k, aOnCard, losses)
        restNanos += System.nanoTime() - at
    }

    /**
     * [evaluate] with the pairs and the names on the card ([PairsOnCard]): the items' coordinates and their slopes, the
     * values' and the names' coordinates, the names' slopes there and never brought over; the slopes of A taken back
     * only when the searches are not there. The card waited for before every reading of the time: what it was handed
     * runs while the processor goes on, and its time would be told of whatever waits for it next.
     */
    private fun evaluateOnCard(onPairs: PairsOnCard, losses: FloatArray, aOnCard: Boolean) {
        val k = active
        val kr = k * r
        val c = onCard!!
        var at = System.nanoTime()
        products.coordinates(kr, aOnCard, keep = true)
        c.cublas.synchronize()
        featuresNanos += System.nanoTime() - at
        at = System.nanoTime()
        onPairs.coordinates(k, bT, eT, c.aT)
        c.cublas.synchronize()
        restNanos += System.nanoTime() - at
        at = System.nanoTime()
        onPairs.evaluate(k, slotModel, heldOut, totals, c.uT, c.duT, pair, bias, gBias, gPair, losses)
        for (slot in 0 until k) losses[slot] = losses[slot] / totals[slotModel[slot]]
        c.cublas.synchronize()
        pairsNanos += System.nanoTime() - at
        // dAᵀ = dUᵀ X, then the names' part onto it there; dBᵀ = dVᵀ Z
        at = System.nanoTime()
        products.gradient(kr, aOnCard, duOnCard = true, bringBack = false)
        onPairs.gradient(k, gE, gB, c.gA)
        if (!aOnCard) c.gA.download(gA, 0, 0, kr * dx)
        c.cublas.synchronize()
        featuresNanos += System.nanoTime() - at
        at = System.nanoTime()
        addPenalty(k, aOnCard, losses)
        c.cublas.synchronize()
        restNanos += System.nanoTime() - at
    }

    /**
     * The penalty on every weight of the first [k] slots, once the products have written the gradient: added to
     * [losses], its slope to the gradient — the pair's own weights too, but the common bias, the base rate of every
     * value. Of A on the card when it is there ([aOnCard]).
     */
    private fun addPenalty(k: Int, aOnCard: Boolean, losses: FloatArray) {
        for (slot in 0 until k) {
            val lambda = lambdas[slotModel[slot]]
            val common = pair[slot * Bilinear.PAIR + 1]
            val theta = points[slot]
            val gradient = grads[slot]
            var penalty = 0f
            val first = if (aOnCard) 1 else 0
            if (aOnCard) {
                val c = onCard!!
                val aAt = slot.toLong() * r * dx
                penalty += c.cublas.dot(c.aT, aAt, c.aT, aAt, r * dx)
                c.cublas.axpy(2 * lambda, c.aT, aAt, c.gA, aAt, r * dx)
            }
            for (q in first until theta.floats.size) {
                penalty += Simd.dot(theta.floats[q], theta.at[q], theta.floats[q], theta.at[q], theta.count[q])
                Simd.axpy(2 * lambda, theta.floats[q], theta.at[q], gradient.floats[q], gradient.at[q], theta.count[q])
            }
            gPair[slot * Bilinear.PAIR + 1] = gPair[slot * Bilinear.PAIR + 1] - 2 * lambda * common
            losses[slot] += lambda * (penalty - common * common)
        }
    }

    /**
     * The held-out loss of every model, the pairs of the items of its fold that weigh anything, as
     * [Bilinear.heldOutLoss] takes them — of the weights [fitted] ([Bilinear]'s layout), into [into] and, of the values
     * no item outside the fold has, [unseen].
     */
    fun heldOut(fitted: List<FloatArray>, into: List<Bilinear.Held>, unseen: List<Bilinear.Held>) = onTheCard {
        Blas.wide {
            active = count
            for (model in 0 until count) {
                slotModel[model] = model
                modelSlot[model] = model
                points[model].write(Floats.of(layout.ordered(fitted[model])))
            }
            val kr = count * r
            products.coordinates(kr, aOnCard = false)
            Blas.gemm(false, true, kr, m, dz, bT, 0, dz, p.z.held, 0, dz, vT, 0, m)
            Simd.axpy(1f, eT, 0, vT, 0, kr * m)
            for (model in 0 until count) {
                val fold = heldOut[model]
                val fresh = p.unseenOutside(fold)
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
                        into[model].add(pairRow.weights[j].toDouble(), l)
                        if (fresh[v]) unseen[model].add(pairRow.weights[j].toDouble(), l)
                    }
                }
            }
        }
    }

    /**
     * The loss and the gradient of every model at [thetas] ([Bilinear]'s layout) in one step of the products: what
     * [Bilinear.lossAndGradient] gives each — the measure this is checked against.
     */
    internal fun lossesAndGradients(thetas: List<FloatArray>): Pair<FloatArray, List<FloatArray>> = onTheCard {
        Blas.wide {
            active = count
            for (model in 0 until count) {
                slotModel[model] = model
                modelSlot[model] = model
                points[model].write(Floats.of(layout.ordered(thetas[model])))
            }
            val losses = FloatArray(count)
            evaluate(losses)
            val own = Floats(size)
            losses to
                List(count) { model ->
                    grads[model].read(own)
                    layout.standard(own.toArray())
                }
        }
    }

    companion object {
        /** The pieces of a model's point ([pieces]): A, B, E, the biases, the pair's own weights. */
        private const val PIECES = 5

        /** The nanoseconds of a millisecond: the log tells the times in milliseconds. */
        private const val NANOS_IN_MILLI = 1_000_000L
    }
}
