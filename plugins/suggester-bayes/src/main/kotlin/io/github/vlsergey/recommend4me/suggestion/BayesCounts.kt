package io.github.vlsergey.recommend4me.suggestion

import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.exp
import kotlin.math.ln

/**
 * WHAT ONE WORK ADDS TO THE COUNTS: the [uniform] weight of every value it lacks (its lacking weight when it has any
 * value), its pairs that differ from it — their [values], the weights of 0 ([zeros], over the uniform) and of 1
 * ([ones]) — and the values of its other facets it is counted under ([contexts], ascending).
 */
internal class BayesWork(
    val uniform: Float,
    val values: IntArray,
    val zeros: FloatArray,
    val ones: FloatArray,
    val contexts: IntArray,
) {
    fun write(out: DataOutputStream) {
        out.writeFloat(uniform)
        out.writeInt(values.size)
        values.forEach(out::writeInt)
        zeros.forEach(out::writeFloat)
        ones.forEach(out::writeFloat)
        out.writeInt(contexts.size)
        contexts.forEach(out::writeInt)
    }

    companion object {
        /**
         * Of every work of the [task], what it adds. Of a work that may have only some values, the others are counted
         * as lacking too: an approximation of the method's own — what it is judged by ([SuggestionPairs]) is exact.
         */
        fun of(task: SuggestionTask, pairs: SuggestionPairs): List<BayesWork> =
            List(task.itemCount) { i ->
                val uniform = if (task.assigned[i].isNotEmpty()) task.lackWeight.toFloat() else 0f
                val values = ArrayList<Int>()
                val zeros = ArrayList<Float>()
                val ones = ArrayList<Float>()
                // A pair labelled 1 weighs as 1, and no longer as one lacking; one labelled 0 weighs its own weight
                // instead of the uniform
                pairs.forEach(i) { v, w, y ->
                    if (y > 0f || w != uniform) {
                        values += v
                        zeros += if (y > 0f) -uniform else w - uniform
                        ones += if (y > 0f) w else 0f
                    }
                }
                BayesWork(
                    uniform,
                    values.toIntArray(),
                    zeros.toFloatArray(),
                    ones.toFloatArray(),
                    task.context[i].distinct().sorted().toIntArray(),
                )
            }

        fun read(input: DataInputStream): BayesWork {
            val uniform = input.readFloat()
            val size = input.readInt()
            val values = IntArray(size) { input.readInt() }
            val zeros = FloatArray(size) { input.readFloat() }
            val ones = FloatArray(size) { input.readFloat() }
            return BayesWork(uniform, values, zeros, ones, IntArray(input.readInt()) { input.readInt() })
        }
    }
}

/**
 * THE WEIGHTS OF THE PAIRS OF EVERY VALUE, labelled 1 and 0 — of all the works counted, and of every value of the other
 * facets of the works with it. Every work's pairs are a uniform weight over every value (its lacking ones, when it has
 * any) and the few that differ ([BayesWork]): the uniform part is summed apart, so a context's map holds only the
 * values its works have, rejected or were given. Of [m] values and [contextCount] values of the other facets.
 */
internal class BayesCounts(
    val m: Int,
    val works: Int,
    /** Of every value: the weights of 0 and 1 over every work counted. */
    val zero: DoubleArray,
    val one: DoubleArray,
    /** Of every context: the uniform weight of its works, and how many works it has. */
    val contextUniform: DoubleArray,
    val contextWorks: IntArray,
    /** Of every context, the sparse weights of 0 and 1 of the values that differ. */
    val contextZero: Map<Int, Map<Int, Double>>,
    val contextOne: Map<Int, Map<Int, Double>>,
) {
    /**
     * THE CHANCE OF EVERY VALUE of a work of the values of other facets [contexts] (indices of these counts,
     * ascending): its own pairs taken out of the counts when it was counted ([own]) — a work is not its own witness.
     *
     *     logit P(v | i) = ln((N₁ᵥ + α) / (N₀ᵥ + α))
     *                    + s · Σ_{c ∈ context(i)} ln( (n₁(c,v) + α·q_c) / (N₁ᵥ + α) · (N₀ᵥ + α) / (n₀(c,v) + α·q_c) )
     */
    fun chances(contexts: IntArray, own: BayesWork?, alpha: Double, scale: Double): FloatArray {
        val self = Witness(own, m, zero, one, alpha)
        val u = own?.uniform?.toDouble() ?: 0.0
        val total = (works - if (own != null) 1 else 0).coerceAtLeast(1)
        // Whether the work was counted under each of its contexts: its own pairs taken out of those alone
        val counted = BooleanArray(contexts.size) { own != null && own.contexts.binarySearch(contexts[it]) >= 0 }
        // A context's term of a value none of its works has, rejected or was given — most of them:
        //   ln(α·q) − ln(cu + α·q) + a[v]
        // summed over the contexts at once; the values its works touch corrected after
        val qs = DoubleArray(contexts.size)
        val cus = DoubleArray(contexts.size)
        var constant = 0.0
        contexts.forEachIndexed { k, c ->
            val worksWith = (contextWorks[c] - if (counted[k]) 1 else 0).coerceAtLeast(0)
            qs[k] = (worksWith + 1.0) / (total + 2.0)
            cus[k] = (contextUniform[c] - if (counted[k]) u else 0.0).coerceAtLeast(0.0)
            constant += ln(alpha * qs[k]) - ln(cus[k] + alpha * qs[k])
        }
        val a = self.a
        val logit = DoubleArray(m) { v -> -a[v] + scale * (constant + contexts.size * a[v]) }
        contexts.forEachIndexed { k, c ->
            val corrections = Context(contextZero[c], contextOne[c], qs[k], cus[k], counted[k])
            corrections.correct(logit, self, alpha, scale)
        }
        return FloatArray(m) { (1.0 / (1.0 + exp(-logit[it]))).toFloat() }
    }

    /**
     * A work's own pairs, taken out of the counts it was counted in — none of a work not counted — and of every value
     * the weights of 0 and 1 of the other works ([n0], [n1]) and ln(N₀ᵥ + α) − ln(N₁ᵥ + α) of them ([a]): the prior's
     * log odds negated, and the part of every context's term that is the value's alone.
     */
    private class Witness(own: BayesWork?, m: Int, zero: DoubleArray, one: DoubleArray, alpha: Double) {
        val zeros = HashMap<Int, Double>()
        val ones = HashMap<Int, Double>()
        val n0 = DoubleArray(m)
        val n1 = DoubleArray(m)
        val a = DoubleArray(m)

        init {
            own?.values?.forEachIndexed { k, v ->
                zeros[v] = own.zeros[k].toDouble()
                ones[v] = own.ones[k].toDouble()
            }
            val u = own?.uniform?.toDouble() ?: 0.0
            for (v in 0 until m) {
                n0[v] = (zero[v] - u - (zeros[v] ?: 0.0)).coerceAtLeast(0.0)
                n1[v] = (one[v] - (ones[v] ?: 0.0)).coerceAtLeast(0.0)
                a[v] = ln(n0[v] + alpha) - ln(n1[v] + alpha)
            }
        }
    }

    /**
     * One context of a work: the sparse weights of 0 and 1 of the values its works touch ([z], [o]), its share of the
     * works [q] and their uniform weight [cu], and whether the work was counted under it ([mine]).
     */
    private class Context(
        val z: Map<Int, Double>?,
        val o: Map<Int, Double>?,
        val q: Double,
        val cu: Double,
        val mine: Boolean,
    ) {
        /** The context's term of every value its works touch, put in place of the term of a value none touches. */
        fun correct(logit: DoubleArray, self: Witness, alpha: Double, scale: Double) {
            val touched = HashSet<Int>()
            z?.keys?.let(touched::addAll)
            o?.keys?.let(touched::addAll)
            if (mine) touched.addAll(self.zeros.keys)
            touched.forEach { v ->
                val ownZero = if (mine) self.zeros[v] ?: 0.0 else 0.0
                val ownOne = if (mine) self.ones[v] ?: 0.0 else 0.0
                val c0 = (cu + (z?.get(v) ?: 0.0) - ownZero).coerceAtLeast(0.0)
                val c1 = ((o?.get(v) ?: 0.0) - ownOne).coerceAtLeast(0.0)
                val actual = ln((c1 + alpha * q) / (self.n1[v] + alpha)) - ln((c0 + alpha * q) / (self.n0[v] + alpha))
                val default = ln(alpha * q) - ln(cu + alpha * q) + self.a[v]
                logit[v] += scale * (actual - default)
            }
        }
    }

    fun write(out: DataOutputStream) {
        out.writeInt(m)
        out.writeInt(works)
        zero.forEach(out::writeDouble)
        one.forEach(out::writeDouble)
        out.writeInt(contextUniform.size)
        contextUniform.forEach(out::writeDouble)
        contextWorks.forEach(out::writeInt)
        listOf(contextZero, contextOne).forEach { sparse ->
            out.writeInt(sparse.size)
            sparse.forEach { (c, values) ->
                out.writeInt(c)
                out.writeInt(values.size)
                values.forEach { (v, w) ->
                    out.writeInt(v)
                    out.writeDouble(w)
                }
            }
        }
    }

    companion object {
        /** The counts of the [works] that [included] names, of [m] values and [contextCount] contexts. */
        fun of(m: Int, contextCount: Int, works: List<BayesWork>, included: (Int) -> Boolean): BayesCounts {
            val zero = DoubleArray(m)
            val one = DoubleArray(m)
            val contextUniform = DoubleArray(contextCount)
            val contextWorks = IntArray(contextCount)
            val contextZero = HashMap<Int, HashMap<Int, Double>>()
            val contextOne = HashMap<Int, HashMap<Int, Double>>()
            var counted = 0
            var uniformTotal = 0.0
            works.forEachIndexed { i, w ->
                if (!included(i)) return@forEachIndexed
                counted++
                uniformTotal += w.uniform
                w.values.forEachIndexed { k, v ->
                    zero[v] += w.zeros[k]
                    one[v] += w.ones[k]
                }
                w.contexts.forEach { c ->
                    contextWorks[c]++
                    contextUniform[c] += w.uniform.toDouble()
                    if (w.values.isEmpty()) return@forEach
                    val z = contextZero.getOrPut(c) { HashMap() }
                    val o = contextOne.getOrPut(c) { HashMap() }
                    w.values.forEachIndexed { k, v ->
                        if (w.zeros[k] != 0f) z.merge(v, w.zeros[k].toDouble(), Double::plus)
                        if (w.ones[k] != 0f) o.merge(v, w.ones[k].toDouble(), Double::plus)
                    }
                }
            }
            for (v in 0 until m) zero[v] += uniformTotal
            return BayesCounts(m, counted, zero, one, contextUniform, contextWorks, contextZero, contextOne)
        }

        fun read(input: DataInputStream): BayesCounts {
            val m = input.readInt()
            val works = input.readInt()
            val zero = DoubleArray(m) { input.readDouble() }
            val one = DoubleArray(m) { input.readDouble() }
            val contexts = input.readInt()
            val contextUniform = DoubleArray(contexts) { input.readDouble() }
            val contextWorks = IntArray(contexts) { input.readInt() }
            val (contextZero, contextOne) =
                List(2) {
                    HashMap<Int, Map<Int, Double>>().also { sparse ->
                        repeat(input.readInt()) {
                            val c = input.readInt()
                            val values = HashMap<Int, Double>()
                            repeat(input.readInt()) { values[input.readInt()] = input.readDouble() }
                            sparse[c] = values
                        }
                    }
                }
            return BayesCounts(m, works, zero, one, contextUniform, contextWorks, contextZero, contextOne)
        }
    }
}
