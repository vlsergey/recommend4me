package io.github.vlsergey.recommend4me.universe

import java.util.IdentityHashMap
import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(FandomLikenessDescent::class.java)

/**
 * THE DESCENT THAT LEARNS THE WEIGHTS OF THE LIKENESS OF THE FANDOMS' NAMES of [pairs] ([FandomLikeness.fit]): the loss
 * of a pair log(1 + e^(−(cos(a, near) − cos(a, far)) / [TEMPERATURE])) of the weighed cosine A / √(B C) — A = Σ w a y,
 * B = Σ w a², C = Σ w y² —, Adam, the weights pulled toward 1 and none below nought.
 *
 * Made for speed (user, 2026-11-14: every name of every universe, none left out). Every name once ([names]); at every
 * step of each its C and its vector weighed, w ⊙ y, once. The pairs gathered by their anchor: of an anchor and every
 * name it is set nearer or farther than ([candidates]) the product A once a step, not once a pair — an anchor of a
 * franchise is set nearer to hundreds of names, each against several far ones —, the pairs then plain numbers of the
 * cosines; ∂cos/∂w = a y / √(BC) − A / (2 √(BC)) (a² / B + y² / C), the part of a y summed of every pair as a number a
 * candidate, Σ (number) y once a candidate and times a once an anchor; those of a² and y² a number a name, added once a
 * name. The anchors cut among the processors, each part its own sums, added after.
 *
 * On the processor, in its threads: the vectors in single precision, as the encoder gives them, the sums and the
 * weights in double.
 */
internal class FandomLikenessDescent(pairs: List<NamePair>) {
    private val names = ArrayList<FloatArray>()
    private val dim = pairs.first().anchor.size

    /** Every anchor's name, and where its candidates and its pairs start in [candidates] and the pairs' arrays. */
    private val anchors: IntArray
    private val candidateStart: IntArray
    private val pairStart: IntArray

    /** The names every anchor is set nearer or farther than, an anchor's together: name indices. */
    private val candidates: IntArray

    /** Of every pair, its near and far candidate — places in [candidates] — and its weight, an anchor's together. */
    private val near: IntArray
    private val far: IntArray
    private val weight: DoubleArray

    init {
        val index = IdentityHashMap<FloatArray, Int>()
        fun of(v: FloatArray) = index.getOrPut(v) { names.size.also { names += v } }
        val total = pairs.sumOf { it.weight }
        val byAnchor = pairs.groupBy { of(it.anchor) }
        anchors = byAnchor.keys.toIntArray()
        candidateStart = IntArray(anchors.size + 1)
        pairStart = IntArray(anchors.size + 1)
        val cand = ArrayList<Int>()
        near = IntArray(pairs.size)
        far = IntArray(pairs.size)
        weight = DoubleArray(pairs.size)
        var p = 0
        anchors.forEachIndexed { i, a ->
            candidateStart[i] = cand.size
            pairStart[i] = p
            val slot = HashMap<Int, Int>()
            fun place(v: FloatArray) = slot.getOrPut(of(v)) { cand.size.also { cand += of(v) } }
            byAnchor.getValue(a).forEach { pair ->
                near[p] = place(pair.near)
                far[p] = place(pair.far)
                weight[p] = pair.weight / total
                p++
            }
        }
        candidateStart[anchors.size] = cand.size
        pairStart[anchors.size] = p
        candidates = cand.toIntArray()
    }

    /** How long each part of the steps took, summed over them: nanoseconds. */
    private var weighing = 0L
    private var paired = 0L
    private var summed = 0L
    private var stepped = 0L

    /** The weights learnt. */
    fun run(): FloatArray {
        val started = System.nanoTime()
        val w = DoubleArray(dim) { 1.0 }
        val m = DoubleArray(dim)
        val v = DoubleArray(dim)
        for (t in 1..STEPS) {
            val grad = gradient(w)
            val step = System.nanoTime()
            for (k in 0 until dim) {
                val g = grad[k] + L2 * (w[k] - 1)
                m[k] = BETA1 * m[k] + (1 - BETA1) * g
                v[k] = BETA2 * v[k] + (1 - BETA2) * g * g
                val mh = m[k] / (1 - BETA1.pow(t))
                val vh = v[k] / (1 - BETA2.pow(t))
                w[k] = (w[k] - RATE * mh / (sqrt(vh) + EPSILON)).coerceAtLeast(0.0)
            }
            stepped += System.nanoTime() - step
        }
        log.info(
            "The likeness of the fandoms' names: {} steps over {} pairs of {} anchors, " +
                "{} products an anchor and a name, {} names of {} numbers in {} ms — the names weighed {} ms, " +
                "the anchors {} ms on {} processors, their sums added {} ms, the steps {} ms",
            STEPS,
            near.size,
            anchors.size,
            candidates.size,
            names.size,
            dim,
            (System.nanoTime() - started) / NANOS,
            weighing / NANOS,
            paired / NANOS,
            Runtime.getRuntime().availableProcessors(),
            summed / NANOS,
            stepped / NANOS,
        )
        return FloatArray(dim) { w[it].toFloat() }
    }

    /** The gradient of the mean loss of the pairs along the weights [w]. */
    private fun gradient(w: DoubleArray): DoubleArray {
        val started = System.nanoTime()
        val wf = FloatArray(dim) { w[it].toFloat() }
        // Of every name its C and its vector weighed, once
        val weighed = arrayOfNulls<FloatArray>(names.size)
        val c = DoubleArray(names.size)
        IntStream.range(0, names.size).parallel().forEach { j ->
            val y = names[j]
            val out = FloatArray(dim)
            var s = 0.0
            for (k in 0 until dim) {
                out[k] = wf[k] * y[k]
                s += out[k] * y[k]
            }
            weighed[j] = out
            c[j] = s.coerceAtLeast(LEAST)
        }
        val namesDone = System.nanoTime()
        weighing += namesDone - started
        // More parts than processors: an anchor of a franchise is far more work than another
        val parts = Runtime.getRuntime().availableProcessors().coerceAtLeast(1) * PARTS_A_PROCESSOR
        val sums = Array(parts) { Sums() }
        IntStream.range(0, parts).parallel().forEach { part ->
            var i = part
            while (i < anchors.size) {
                sums[part].anchor(i, weighed, c)
                i += parts
            }
        }
        val anchorsDone = System.nanoTime()
        paired += anchorsDone - namesDone
        val grad = DoubleArray(dim)
        val ofName = DoubleArray(names.size)
        sums.forEach { s ->
            for (k in 0 until dim) grad[k] += s.grad[k]
            for (j in names.indices) ofName[j] += s.ofName[j]
        }
        // The parts of a² and y², a number a name
        for (j in names.indices) {
            val f = ofName[j]
            if (f == 0.0) continue
            val y = names[j]
            for (k in 0 until dim) grad[k] += f * y[k] * y[k]
        }
        summed += System.nanoTime() - anchorsDone
        return grad
    }

    /** What one part of the anchors adds to the gradient: along the weights, and of a² and y² a number a name. */
    private inner class Sums {
        val grad = DoubleArray(dim)
        val ofName = DoubleArray(names.size)

        /** Σ (number) y of an anchor's candidates. */
        private val along = DoubleArray(dim)

        /** The anchor [i] and its pairs added, of the names [weighed] and their [c]. */
        fun anchor(i: Int, weighed: Array<FloatArray?>, c: DoubleArray) {
            val a = anchors[i]
            val wa = weighed[a]!!
            val b = c[a]
            val from = candidateStart[i]
            val count = candidateStart[i + 1] - from
            // Of every candidate its product with the anchor and its cosine, once
            val product = DoubleArray(count)
            val root = DoubleArray(count)
            for (j in 0 until count) {
                val y = names[candidates[from + j]]
                var s = 0.0
                for (k in 0 until dim) s += wa[k] * y[k]
                product[j] = s
                root[j] = sqrt(b * c[candidates[from + j]])
            }
            // The pairs: plain numbers
            val ofCandidate = DoubleArray(count)
            val ofSquare = DoubleArray(count)
            var ofAnchor = 0.0
            for (p in pairStart[i] until pairStart[i + 1]) {
                val n = near[p] - from
                val f = far[p] - from
                val margin = (product[n] / root[n] - product[f] / root[f]) / TEMPERATURE
                // d/dm log(1 + e^(−m)) = −σ(−m), of the near's cosine +, of the far's −
                val g = -weight[p] / (1 + exp(margin)) / TEMPERATURE
                ofCandidate[n] += g / root[n]
                ofCandidate[f] -= g / root[f]
                ofAnchor += -g * product[n] / (2 * root[n] * b) + g * product[f] / (2 * root[f] * b)
                ofSquare[n] += -g * product[n] / (2 * root[n])
                ofSquare[f] += g * product[f] / (2 * root[f])
            }
            along.fill(0.0)
            for (j in 0 until count) {
                val name = candidates[from + j]
                ofName[name] += ofSquare[j] / c[name]
                val x = ofCandidate[j]
                if (x == 0.0) continue
                val y = names[name]
                for (k in 0 until dim) along[k] += x * y[k]
            }
            val ya = names[a]
            for (k in 0 until dim) grad[k] += ya[k] * along[k]
            ofName[a] += ofAnchor
        }
    }

    private companion object {
        /** The steps of the descent. */
        const val STEPS = 100

        /** How far a step of Adam goes. */
        const val RATE = 0.02
        const val BETA1 = 0.9
        const val BETA2 = 0.999
        const val EPSILON = 1e-8

        /** The pull of the weights toward 1, the encoder's likeness: a few hundred universes tell no dimension sure. */
        const val L2 = 0.01

        /** The scale of the cosines' difference: the cosines of two names differ by tenths. */
        const val TEMPERATURE = 0.1

        /** The parts of the anchors a processor takes in turn: of uneven anchors, none waits for one long part. */
        const val PARTS_A_PROCESSOR = 4

        /** Nanoseconds to a millisecond. */
        const val NANOS = 1_000_000L

        /** The least weighed square length of a name divided by. */
        const val LEAST = 1e-12
    }
}
