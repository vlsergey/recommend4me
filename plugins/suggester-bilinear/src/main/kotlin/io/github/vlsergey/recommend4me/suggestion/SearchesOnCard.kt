package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.Floats
import java.lang.foreign.MemorySegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * THE SEARCHES OF EVERY MODEL OF AN ENSEMBLE ON THE CARD AT ONCE: what [Search] does of one model, of every model by
 * one launch of a kernel of our own (`searches.cu`) — the point x, the gradient g, the direction d and the remembered
 * steps and changes of every model there, [size] numbers each; the point proposed written straight into the slots the
 * products read ([SlotsOnCard]), the gradient read straight from them. The two loops of the direction a remembered step
 * at a time for every model together, each product's sum kept there for the sum after it: of a step of the characters'
 * forty-five models some fifty launches and three waits for the card, where a search of each was tens of products and
 * twenty waits a model. What the searches decide — a step taken or tried shorter, a search ended — of the few numbers
 * brought back, here, as [Search] decides.
 */
internal class SearchesOnCard(
    private val kernels: CudaKernels,
    room: CardRoom,
    private val count: Int,
    private val size: Int,
    private val maxSteps: Int,
) {
    private val memory = Lbfgs.MEMORY
    private val x = room.floats(count.toLong() * size)
    private val g = room.floats(count.toLong() * size)
    private val d = room.floats(count.toLong() * size)
    // The remembered steps and changes in brain floating point, two bytes a number (`searches.cu`)
    private val steps = room.bytes(count.toLong() * memory * size * HALF)
    private val changes = room.bytes(count.toLong() * memory * size * HALF)

    // What a round sends and takes back: the models of a list, their slots and places of the ring, their sums; the
    // ring's places and the ρ of every remembered step of every model of the list, the sums of its loops
    private val list = room.bytes(count.toLong() * Int.SIZE_BYTES)
    private val slots = room.bytes(count.toLong() * Int.SIZE_BYTES)
    private val ring = room.bytes(count.toLong() * Int.SIZE_BYTES)
    private val slotModel = room.bytes(count.toLong() * Int.SIZE_BYTES)
    private val sums = room.bytes(count.toLong() * SUMS * Double.SIZE_BYTES)
    private val rings = room.bytes(memory.toLong() * count * Int.SIZE_BYTES)
    private val rhosOf = room.bytes(memory.toLong() * count * Float.SIZE_BYTES)
    private val alphaDots = room.bytes(memory.toLong() * count * Double.SIZE_BYTES)
    private val betaDots = room.bytes(memory.toLong() * count * Double.SIZE_BYTES)
    private val alphas = room.bytes(memory.toLong() * count * Float.SIZE_BYTES)
    private val gammas = room.bytes(count.toLong() * Float.SIZE_BYTES)
    private val inverses = room.bytes(count.toLong() * Float.SIZE_BYTES)
    private val slopes = room.bytes(count.toLong() * Double.SIZE_BYTES)

    /** Of every model of the list, no remembered step: a step that moves or sums nothing of it. */
    private val nothing = room.bytes(count.toLong() * Int.SIZE_BYTES)
    private val stepOf = room.bytes(count.toLong() * Float.SIZE_BYTES)
    private val begunOf = room.bytes(count.toLong() * Int.SIZE_BYTES)

    // Of every model, as its Search keeps it
    private val fx = FloatArray(count)
    private val slope = FloatArray(count)
    private val t = FloatArray(count) { 1f }
    private val step = IntArray(count)
    private val remembered = IntArray(count)
    private val oldest = IntArray(count)
    private val rho = Array(count) { FloatArray(memory) }
    private val syOf = Array(count) { FloatArray(memory) }
    private val yyOf = Array(count) { FloatArray(memory) }
    private val gNorm = FloatArray(count)
    private val fell = FloatArray(count)
    private val begun = BooleanArray(count)
    private val done = BooleanArray(count)

    /** How many times the function was worked out for every model. */
    val evaluations = IntArray(count)

    /**
     * Whether the search of the [model] has ended: the value no longer falls, the gradient is nought, or it ran out.
     */
    fun done(model: Int): Boolean = done[model]

    /** Every model's search begun at its start ([starts], each contiguous, of [size] numbers). */
    fun begin(starts: List<FloatArray>) {
        starts.forEachIndexed { q, start -> x.upload(Floats.of(start), 0, q.toLong() * size, size) }
    }

    /** The point every model reached: the minimum once its search is done. */
    fun result(model: Int): FloatArray = Floats(size).also { x.download(it, 0, model.toLong() * size, size) }.toArray()

    /**
     * The point the search of the model in each of the first [k] slots ([slotModel]) asks for, into the [slotsOnCard].
     */
    fun propose(k: Int, slotModel: IntArray, slotsOnCard: SlotsOnCard) {
        this.slotModel.upload(slotModel)
        stepOf.upload(t)
        begunOf.upload(IntArray(count) { if (begun[it]) 1 else 0 })
        kernels.launch(
            "propose",
            k.toLong() * size,
            *(listOf(k, size, this.slotModel, x, d, stepOf, begunOf) + pieces(slotsOnCard, gradient = false))
                .toTypedArray(),
        )
    }

    /**
     * The function at the point of the model in each of the first [k] slots: its loss of [losses], its gradient in the
     * [slotsOnCard]. A step too short to move the point by what a float can tell is the last one tried; a step taken is
     * remembered — of positive curvature alone — and the next direction made.
     */
    fun answer(k: Int, slotModel: IntArray, losses: FloatArray, slotsOnCard: SlotsOnCard) {
        val taken = ArrayList<Taken>()
        for (slot in 0 until k) {
            val q = slotModel[slot]
            val loss = losses[slot]
            evaluations[q]++
            if (!begun[q]) {
                begun[q] = true
                fx[q] = loss
                taken += Taken(q, slot, -1)
            } else if (!(loss <= fx[q] + Lbfgs.SUFFICIENT * t[q] * slope[q] || t[q] < Lbfgs.FLOAT_RESOLUTION)) {
                t[q] *= Lbfgs.BACKTRACK
            } else {
                taken += Taken(q, slot, (oldest[q] + remembered[q]) % memory)
                fell[q] = fx[q] - loss
                fx[q] = loss
                step[q]++
            }
        }
        if (taken.isEmpty()) return
        val got = remembered(taken, slotsOnCard)
        val directed = ArrayList<Int>()
        taken.forEachIndexed { idx, s ->
            val q = s.model
            gNorm[q] = sqrt(got[idx * SUMS + 2]).toFloat()
            if (s.ring >= 0) {
                val sy = got[idx * SUMS].toFloat()
                if (sy > 0f) {
                    rho[q][s.ring] = 1f / sy
                    syOf[q][s.ring] = sy
                    yyOf[q][s.ring] = got[idx * SUMS + 1].toFloat()
                    if (remembered[q] < memory) remembered[q]++ else oldest[q] = (oldest[q] + 1) % memory
                }
                done[q] =
                    (step[q] > 1 && abs(fell[q]) <= Lbfgs.FLOAT_RESOLUTION * max(1f, abs(fx[q]))) || step[q] >= maxSteps
            }
            if (!done[q]) if (gNorm[q] == 0f) done[q] = true else directed += q
        }
        if (directed.isNotEmpty()) directions(directed)
    }

    /** A model whose step was taken: its slot, its place of the ring (−1: its search begins). */
    private class Taken(val model: Int, val slot: Int, val ring: Int)

    /** The steps [taken] remembered — x and g made the new ones — and their sums s·y, y·y, g·g brought back. */
    private fun remembered(taken: List<Taken>, slotsOnCard: SlotsOnCard): DoubleArray {
        val c = taken.size
        list.upload(IntArray(c) { taken[it].model })
        slots.upload(IntArray(c) { taken[it].slot })
        ring.upload(IntArray(c) { taken[it].ring })
        sums.zero()
        kernels.launch(
            "remember",
            c.toLong() * PER_MODEL * CudaKernels.BLOCK,
            *(listOf(c, size, list, slots, ring, memory, x, g, steps, changes) +
                    pieces(slotsOnCard, gradient = false).dropLast(SlotsOnCard.PIECES) +
                    pieces(slotsOnCard, gradient = true) +
                    sums)
                .toTypedArray(),
        )
        return sums.doubles(c * SUMS)
    }

    /**
     * The next direction of every model of [models]: minus the gradient times the inverse Hessian its remembered steps
     * stand for (two loops, algorithm 7.4), the step back to one; not a descent, the history forgotten and the gradient
     * followed.
     */
    private fun directions(models: List<Int>) {
        val c = models.size
        list.upload(models.toIntArray())
        val most = models.maxOf { remembered[it] }
        val ringOf = IntArray(maxOf(1, most) * c)
        val rhoOf = FloatArray(maxOf(1, most) * c)
        models.forEachIndexed { idx, q ->
            for (r in 0 until most) {
                val k = if (r < remembered[q]) (oldest[q] + r) % memory else -1
                ringOf[r * c + idx] = k
                rhoOf[r * c + idx] = if (k >= 0) rho[q][k] else 0f
            }
        }
        rings.upload(ringOf)
        rhosOf.upload(rhoOf)
        nothing.upload(IntArray(c) { -1 })
        alphaDots.zero()
        betaDots.zero()
        // d = g, with the product the first loop's first step moves by; then a step a remembered step, the newest
        // first, each with the product of the step before it
        step(c, Step(dotted = if (most > 0) most - 1 else null, w = steps, dotsOut = alphaDots), begin = true)
        for (r in most - 1 downTo 0) step(
            c,
            Step(along = r, v = changes, dotsIn = alphaDots, dotted = (r - 1).takeIf { it >= 0 }, w = steps),
        )
        // d ·= γ, with the product the second loop's first step moves by; then a step a remembered step, the oldest
        // first
        gammas.upload(
            FloatArray(c) { idx ->
                val q = models[idx]
                if (remembered[q] == 0) 1f / gNorm[q]
                else ((oldest[q] + remembered[q] - 1) % memory).let { k -> syOf[q][k] / yyOf[q][k] }
            }
        )
        step(c, Step(dotted = if (most > 0) 0 else null, w = changes, dotsOut = betaDots), scale = true)
        for (r in 0 until most) step(
            c,
            Step(along = r, v = steps, dotsIn = betaDots, dotted = (r + 1).takeIf { it < most }, w = changes),
            second = 1,
        )
        slopes.zero()
        kernels.launch("direction_end", c.toLong() * PER_MODEL * CudaKernels.BLOCK, c, size, list, g, d, slopes)
        val got = slopes.doubles(c)
        val reset = ArrayList<Int>()
        models.forEachIndexed { idx, q ->
            slope[q] = got[idx].toFloat()
            t[q] = 1f
            if (slope[q] >= 0f) {
                remembered[q] = 0
                oldest[q] = 0
                slope[q] = -gNorm[q]
                reset += q
            }
        }
        if (reset.isNotEmpty()) {
            list.upload(reset.toIntArray())
            inverses.upload(FloatArray(reset.size) { 1f / gNorm[reset[it]] })
            kernels.launch("direction_reset", reset.size.toLong() * size, reset.size, size, list, inverses, g, d)
        }
    }

    /**
     * A step of a loop of the direction ([step]): d moved [along] the remembered step of that place (of the steps of
     * the loop, its vectors [v] — the changes or the steps — the products it moves by in [dotsIn]), and the product of
     * the [dotted] one's vectors [w] with d as it is then into [dotsOut]; null: no move, no product.
     */
    private class Step(
        val along: Int? = null,
        val v: Cublas.DeviceBytes? = null,
        val dotsIn: Cublas.DeviceBytes? = null,
        val dotted: Int? = null,
        val w: Cublas.DeviceBytes? = null,
        val dotsOut: Cublas.DeviceBytes? = null,
    )

    /**
     * The [step] of every model of the list of [c] in one pass over d (`ring_step`): of the first loop ([second] 0) d
     * moved by −α, of the second by α − β; d made the gradient first ([begin]) or multiplied by γ first ([scale]).
     */
    private fun step(c: Int, step: Step, second: Int = 0, begin: Boolean = false, scale: Boolean = false) {
        fun at(table: Cublas.DeviceBytes, r: Int?, bytes: Int) =
            if (r == null) MemorySegment.NULL else table.at(r.toLong() * c * bytes)
        fun ringAt(r: Int?) = if (r == null) nothing.at(0) else rings.at(r.toLong() * c * Int.SIZE_BYTES)
        val outAt = step.dotsOut ?: if (second == 0) alphaDots else betaDots
        kernels.launch(
            "ring_step",
            c.toLong() * PER_MODEL * CudaKernels.BLOCK,
            c,
            size,
            list,
            memory,
            ringAt(step.along),
            step.v ?: steps,
            at(rhosOf, step.along, Float.SIZE_BYTES),
            at(step.dotsIn ?: alphaDots, step.along, Double.SIZE_BYTES),
            at(alphas, step.along, Float.SIZE_BYTES),
            second,
            ringAt(step.dotted),
            step.w ?: steps,
            at(outAt, step.dotted, Double.SIZE_BYTES),
            if (scale) gammas.at(0) else MemorySegment.NULL,
            if (begin) 1 else 0,
            g,
            d,
        )
    }

    /** The five pieces of the point or of the gradient of the slots, then their sizes, as a kernel reads them. */
    private fun pieces(slotsOnCard: SlotsOnCard, gradient: Boolean): List<Any> =
        (if (gradient) slotsOnCard.gradient else slotsOnCard.point) + slotsOnCard.sizes.toList()

    companion object {
        /** The bytes of a number of brain floating point. */
        private const val HALF = 2

        /** The sums of a model a launch brings back: s·y, y·y, g·g. */
        private const val SUMS = 3

        /** The blocks of a model of a launch that sums (`PER_MODEL` of `searches.cu`). */
        private const val PER_MODEL = 64

        private val NAMES =
            listOf(
                "propose",
                "remember",
                "ring_step",
                "direction_end",
                "direction_reset",
            )

        // The kernels compiled for the card of the process, once: the code, not anything of the data
        private var compiledFor: Cublas? = null
        private var compiled: CudaKernels? = null

        /** The kernels of the searches for the card of [cublas]; null when they cannot be made. */
        @Synchronized
        fun kernels(cublas: Cublas): CudaKernels? {
            if (compiledFor !== cublas) {
                compiledFor = cublas
                compiled = cublas.kernels(SearchesOnCard::class.java.getResource("searches.cu")!!.readText(), NAMES)
            }
            return compiled
        }
    }
}
