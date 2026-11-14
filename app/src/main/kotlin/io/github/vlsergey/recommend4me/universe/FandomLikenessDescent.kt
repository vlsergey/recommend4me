package io.github.vlsergey.recommend4me.universe

import java.util.IdentityHashMap
import java.util.stream.IntStream
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * THE DESCENT THAT LEARNS THE WEIGHTS OF THE LIKENESS OF THE FANDOMS' NAMES of [pairs] ([FandomLikeness.fit]): the loss
 * of a pair log(1 + e^(−(cos(a, near) − cos(a, far)) / [TEMPERATURE])) of the weighed cosine A / √(B C) — A = Σ w a y,
 * B = Σ w a², C = Σ w y² —, Adam, the weights pulled toward 1 and none below nought.
 *
 * Made for speed (user, 2026-11-14: every name of every universe, none left out): every name once ([names]), and at
 * every step of each its C and its vector weighed, w ⊙ y, once — of a pair two products of [dim] numbers are left, and
 * one pass of [dim] adding its gradient; ∂cos/∂w = a y / √(BC) − A / (2 √(BC)) (a² / B + y² / C), the parts of a² and
 * y² summed of every pair as a number a name, and added once a name. The pairs are cut among the processors, each part
 * its own sums, added after.
 */
internal class FandomLikenessDescent(pairs: List<NamePair>) {
    private val names = ArrayList<FloatArray>()
    private val anchor: IntArray
    private val near: IntArray
    private val far: IntArray
    private val weight: DoubleArray
    private val dim = pairs.first().anchor.size

    init {
        val index = IdentityHashMap<FloatArray, Int>()
        fun of(v: FloatArray) = index.getOrPut(v) { names.size.also { names += v } }
        anchor = IntArray(pairs.size) { of(pairs[it].anchor) }
        near = IntArray(pairs.size) { of(pairs[it].near) }
        far = IntArray(pairs.size) { of(pairs[it].far) }
        val total = pairs.sumOf { it.weight }
        weight = DoubleArray(pairs.size) { pairs[it].weight / total }
    }

    /** The weights learnt. */
    fun run(): FloatArray {
        val w = DoubleArray(dim) { 1.0 }
        val m = DoubleArray(dim)
        val v = DoubleArray(dim)
        for (t in 1..STEPS) {
            val grad = gradient(w)
            for (k in 0 until dim) {
                val g = grad[k] + L2 * (w[k] - 1)
                m[k] = BETA1 * m[k] + (1 - BETA1) * g
                v[k] = BETA2 * v[k] + (1 - BETA2) * g * g
                val mh = m[k] / (1 - BETA1.pow(t))
                val vh = v[k] / (1 - BETA2.pow(t))
                w[k] = (w[k] - RATE * mh / (sqrt(vh) + EPSILON)).coerceAtLeast(0.0)
            }
        }
        return FloatArray(dim) { w[it].toFloat() }
    }

    /** The gradient of the mean loss of the pairs along the weights [w]. */
    private fun gradient(w: DoubleArray): DoubleArray {
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
        val parts = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val sums = Array(parts) { Sums(dim, names.size) }
        IntStream.range(0, parts).parallel().forEach { part ->
            val s = sums[part]
            var p = part
            while (p < anchor.size) {
                s.add(p, weighed, c)
                p += parts
            }
        }
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
        return grad
    }

    /** What one part of the pairs adds to the gradient: along the weights, and of a² and y² a number a name. */
    private inner class Sums(dim: Int, count: Int) {
        val grad = DoubleArray(dim)
        val ofName = DoubleArray(count)

        /** The pair [p] added, of the names [weighed] and their [c]. */
        fun add(p: Int, weighed: Array<FloatArray?>, c: DoubleArray) {
            val a = anchor[p]
            val n = near[p]
            val f = far[p]
            val wa = weighed[a]!!
            val yn = names[n]
            val yf = names[f]
            var an = 0.0
            var af = 0.0
            for (k in 0 until dim) {
                an += wa[k] * yn[k]
                af += wa[k] * yf[k]
            }
            val b = c[a]
            val rn = sqrt(b * c[n])
            val rf = sqrt(b * c[f])
            val margin = (an / rn - af / rf) / TEMPERATURE
            // d/dm log(1 + e^(−m)) = −σ(−m), of the near's cosine +, of the far's −
            val g = -weight[p] / (1 + exp(margin)) / TEMPERATURE
            val ya = names[a]
            val gn = g / rn
            val gf = g / rf
            for (k in 0 until dim) grad[k] += ya[k] * (gn * yn[k] - gf * yf[k])
            ofName[a] += -g * an / (2 * rn * b) + g * af / (2 * rf * b)
            ofName[n] += -g * an / (2 * rn * c[n])
            ofName[f] += g * af / (2 * rf * c[f])
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

        /** The least weighed square length of a name divided by. */
        const val LEAST = 1e-12
    }
}
