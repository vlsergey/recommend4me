package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Simd
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ONE MODEL'S SEARCH FOR ITS MINIMUM BY LIMITED-MEMORY BFGS (Nocedal and Wright, "Numerical Optimization", algorithms
 * 7.4 and 7.5), A POINT AT A TIME: it names the point it wants the function at ([propose]) and is told what the
 * function is there ([answer]) — so that every model's point of a fitting is worked out in one product of them all
 * ([Ensemble]). The last [MEMORY] steps and changes of the gradient stand for the inverse Hessian; each step is
 * searched back from the full one until the function falls enough (Armijo). In single precision, as the model is, its
 * numbers off the heap, made once for the whole search — not the scorers' search of `libs/optim`, of doubles on the
 * heap: of a model of 700 thousand numbers, every fold's history of ten steps, half a gigabyte of the heap and every
 * point copied between the two precisions a step.
 *
 * It ends when the value falls by less than a float can tell of the value itself, when the gradient is nought, or after
 * [maxSteps] steps — of the value itself, not of one, as the search of a whole problem ended before it was fitted a
 * portion at a time: of a loss of 0.005, as of most facets, that ended it at its sixth step, of author.today's tags at
 * λ = 0.1 a held-out loss of 0.0046 for 0.0035.
 */
internal class ModelSearch(private val size: Int, private val maxSteps: Int) {
    /** The point reached: the minimum once [done]. */
    private val x = Floats(size)
    private val g = Floats(size)
    private val d = Floats(size)
    private val trial = Floats(size)
    private val steps = List(MEMORY) { Floats(size) }
    private val changes = List(MEMORY) { Floats(size) }
    private val rhos = FloatArray(MEMORY)
    private val alphas = FloatArray(MEMORY)

    /** How many steps are remembered, and where the oldest of them is in the ring. */
    private var remembered = 0
    private var oldest = 0
    private var fx = 0f
    private var slope = 0f
    private var t = 1f
    private var step = 0
    private var started = false

    /** Whether the search has ended: the value no longer falls, the gradient is nought, or [maxSteps] were taken. */
    var done = false
        private set

    /** The search begun at [start], of the order of the model's pieces ([Pieces]). */
    fun begin(start: Floats) = x.copy(start, 0, 0, size)

    /** The point the search wants the function at — the point reached once it is [done] — written into [point]. */
    fun propose(point: Pieces) {
        if (!started || done) {
            point.write(x)
            return
        }
        trial.copy(x, 0, 0, size)
        Simd.axpy(t, d, 0, trial, 0, size)
        point.write(trial)
    }

    /** The function at the point proposed: [loss], its gradient in [grad]; the [point] still holding it. */
    fun answer(loss: Float, point: Pieces, grad: Pieces) {
        when {
            done -> {}
            !started -> first(loss, grad)
            // A step too short to move the point by what a float can tell is the last one tried; a value no float
            // holds is no fall, and of the shortest step it ends the search where it is
            loss > fx + SUFFICIENT * t * slope || !loss.isFinite() ->
                if (t >= RESOLUTION && loss.isFinite()) t *= BACKTRACK else done = true
            else -> taken(loss, point, grad)
        }
    }

    /** The function at the start: [loss], its gradient in [grad]. */
    private fun first(loss: Float, grad: Pieces) {
        started = true
        fx = loss
        grad.read(g)
        if (!loss.isFinite() || !direction()) done = true
    }

    /** The step to the [point] taken, the function there [loss], its gradient in [grad]. */
    private fun taken(loss: Float, point: Pieces, grad: Pieces) {
        // The step taken and the change of the gradient, into the ring's next place: only a step of positive curvature
        // stands for the Hessian
        val at = (oldest + remembered) % MEMORY
        val s = steps[at]
        val y = changes[at]
        point.read(s)
        Simd.axpy(-1f, x, 0, s, 0, size)
        grad.read(y)
        Simd.axpy(-1f, g, 0, y, 0, size)
        val sy = dot(s, y)
        if (sy > 0f) {
            rhos[at] = 1f / sy
            if (remembered < MEMORY) remembered++ else oldest = (oldest + 1) % MEMORY
        }
        point.read(x)
        grad.read(g)
        val fell = fx - loss
        fx = loss
        step++
        done = (step > 1 && abs(fell) <= RESOLUTION * abs(fx)) || step >= maxSteps || !direction()
    }

    private fun dot(a: Floats, b: Floats) = Simd.dot(a, 0, b, 0, size)

    private fun scale(c: Float, v: Floats) {
        for (k in 0 until size) v[k] = v[k] * c
    }

    /**
     * The next direction: minus the gradient times the inverse Hessian the remembered steps stand for (two loops,
     * algorithm 7.4), the step back to one; false when the gradient is nought.
     */
    private fun direction(): Boolean {
        val gNorm = sqrt(dot(g, g))
        if (gNorm == 0f || !gNorm.isFinite()) return false
        d.copy(g, 0, 0, size)
        for (r in remembered - 1 downTo 0) {
            val k = (oldest + r) % MEMORY
            alphas[r] = rhos[k] * dot(steps[k], d)
            Simd.axpy(-alphas[r], changes[k], 0, d, 0, size)
        }
        val gamma =
            if (remembered == 0) 1f / gNorm
            else {
                val k = (oldest + remembered - 1) % MEMORY
                dot(steps[k], changes[k]) / dot(changes[k], changes[k])
            }
        scale(gamma, d)
        for (r in 0 until remembered) {
            val k = (oldest + r) % MEMORY
            val beta = rhos[k] * dot(changes[k], d)
            Simd.axpy(alphas[r] - beta, steps[k], 0, d, 0, size)
        }
        scale(-1f, d)
        slope = dot(g, d)
        if (slope >= 0) {
            // Not a descent: the history is forgotten, the gradient followed
            remembered = 0
            oldest = 0
            d.copy(g, 0, 0, size)
            scale(-1f / gNorm, d)
            slope = dot(g, d)
        }
        t = 1f
        return true
    }

    private companion object {
        /** The steps remembered: Nocedal and Wright advise 3 to 20; the middle of it. */
        const val MEMORY = 10

        /** The sufficient fall of the Armijo condition, c₁ of Nocedal and Wright (3.4). */
        const val SUFFICIENT = 1e-4f

        /** The least relative difference of two floats: a fall below it of the value is no fall a float can tell. */
        val RESOLUTION = Math.ulp(1f)

        /** Backtracking by halves: ρ = ½ of Nocedal and Wright, algorithm 3.1. */
        const val BACKTRACK = 0.5f
    }
}
