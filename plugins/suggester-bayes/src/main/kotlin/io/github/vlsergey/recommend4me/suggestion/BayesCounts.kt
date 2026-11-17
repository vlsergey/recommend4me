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
    companion object {
        /**
         * Of every work of the [task] — a portion — what it adds, its other values numbered as the counts number them
         * ([column]: of every value of the task's context, its number of the counts, -1 of one they never saw; null:
         * the task's own numbers). Of a work that may have only some values, the others are counted as lacking too: an
         * approximation of the method's own — what it is judged by ([SuggestionPairs]) is exact.
         */
        fun of(task: SuggestionTask, pairs: SuggestionPairs, column: IntArray? = null): List<BayesWork> =
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
                val own = task.context[i]
                val contexts = if (column == null) own else IntArray(own.size) { column[own[it]] }
                BayesWork(
                    uniform,
                    values.toIntArray(),
                    zeros.toFloatArray(),
                    ones.toFloatArray(),
                    contexts.filter { it >= 0 }.distinct().sorted().toIntArray(),
                )
            }
    }
}

/**
 * THE WEIGHTS OF THE PAIRS OF EVERY VALUE, labelled 1 and 0 — of all the works counted, and of every value of the other
 * facets of the works with it. Every work's pairs are a uniform weight over every value (its lacking ones, when it has
 * any) and the few that differ ([BayesWork]): the uniform part is summed apart, so a context's map holds only the
 * values its works have, rejected or were given. Of [m] values and as many contexts as [contextUniform] has; summed
 * over the portions by [BayesTally].
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
     * ascending): its own pairs taken out of the counts ([own]) — a work is not its own witness — and the counts of
     * [less] taken out too: of a work held out with its fold, the fold's counts, the chances by the other folds alone.
     *
     *     logit P(v | i) = ln((N₁ᵥ + α) / (N₀ᵥ + α))
     *                    + s · Σ_{c ∈ context(i)} ln( (n₁(c,v) + α·q_c) / (N₁ᵥ + α) · (N₀ᵥ + α) / (n₀(c,v) + α·q_c) )
     */
    fun chances(
        contexts: IntArray,
        own: BayesWork?,
        alpha: Double,
        scale: Double,
        less: BayesCounts? = null,
    ): FloatArray {
        val self = Witness(own, this, less, alpha)
        val u = own?.uniform?.toDouble() ?: 0.0
        val total = (works - (less?.works ?: 0) - if (own != null) 1 else 0).coerceAtLeast(1)
        // Whether the work was counted under each of its contexts: its own pairs taken out of those alone
        val counted = BooleanArray(contexts.size) { own != null && own.contexts.binarySearch(contexts[it]) >= 0 }
        // A context's term of a value none of its works has, rejected or was given — most of them:
        //   ln(α·q) − ln(cu + α·q) + a[v]
        // summed over the contexts at once; the values its works touch corrected after
        val qs = DoubleArray(contexts.size)
        val cus = DoubleArray(contexts.size)
        var constant = 0.0
        contexts.forEachIndexed { k, c ->
            val others = contextWorks[c] - (less?.contextWorks?.get(c) ?: 0) - if (counted[k]) 1 else 0
            qs[k] = (others.coerceAtLeast(0) + 1.0) / (total + 2.0)
            val uniform = contextUniform[c] - (less?.contextUniform?.get(c) ?: 0.0) - if (counted[k]) u else 0.0
            cus[k] = uniform.coerceAtLeast(0.0)
            constant += ln(alpha * qs[k]) - ln(cus[k] + alpha * qs[k])
        }
        val a = self.a
        val logit = DoubleArray(m) { v -> -a[v] + scale * (constant + contexts.size * a[v]) }
        contexts.forEachIndexed { k, c ->
            val sparse = Sparse(contextZero[c], contextOne[c], less?.contextZero?.get(c), less?.contextOne?.get(c))
            Context(sparse, qs[k], cus[k], counted[k]).correct(logit, self, alpha, scale)
        }
        return FloatArray(m) { (1.0 / (1.0 + exp(-logit[it]))).toFloat() }
    }

    /**
     * A work's own pairs, taken out of the counts it was counted in — none of a work not counted — and of every value
     * the weights of 0 and 1 of the other works ([n0], [n1]), the counts of [less] taken out too, and ln(N₀ᵥ + α) −
     * ln(N₁ᵥ + α) of them ([a]): the prior's log odds negated, and the part of every context's term that is the value's
     * alone.
     */
    private class Witness(own: BayesWork?, counts: BayesCounts, less: BayesCounts?, alpha: Double) {
        val zeros = HashMap<Int, Double>()
        val ones = HashMap<Int, Double>()
        val n0 = DoubleArray(counts.m)
        val n1 = DoubleArray(counts.m)
        val a = DoubleArray(counts.m)

        init {
            own?.values?.forEachIndexed { k, v ->
                zeros[v] = own.zeros[k].toDouble()
                ones[v] = own.ones[k].toDouble()
            }
            val u = own?.uniform?.toDouble() ?: 0.0
            for (v in 0 until counts.m) {
                val zero = counts.zero[v] - (less?.zero?.get(v) ?: 0.0)
                val one = counts.one[v] - (less?.one?.get(v) ?: 0.0)
                n0[v] = (zero - u - (zeros[v] ?: 0.0)).coerceAtLeast(0.0)
                n1[v] = (one - (ones[v] ?: 0.0)).coerceAtLeast(0.0)
                a[v] = ln(n0[v] + alpha) - ln(n1[v] + alpha)
            }
        }
    }

    /**
     * The sparse weights of 0 and 1 of the values a context's works touch ([zero], [one]), less those of the counts
     * taken out ([lessZero], [lessOne]) — a subset of them.
     */
    private class Sparse(
        val zero: Map<Int, Double>?,
        val one: Map<Int, Double>?,
        val lessZero: Map<Int, Double>?,
        val lessOne: Map<Int, Double>?,
    ) {
        fun zeroOf(v: Int) = (zero?.get(v) ?: 0.0) - (lessZero?.get(v) ?: 0.0)

        fun oneOf(v: Int) = (one?.get(v) ?: 0.0) - (lessOne?.get(v) ?: 0.0)

        /** The values touched: every value of the counts taken out is among the counts' own. */
        fun touched(into: HashSet<Int>) {
            zero?.keys?.let(into::addAll)
            one?.keys?.let(into::addAll)
        }
    }

    /**
     * One context of a work: the [sparse] weights of the values its works touch, its share of the works [q] and their
     * uniform weight [cu], and whether the work was counted under it ([mine]).
     */
    private class Context(val sparse: Sparse, val q: Double, val cu: Double, val mine: Boolean) {
        /** The context's term of every value its works touch, put in place of the term of a value none touches. */
        fun correct(logit: DoubleArray, self: Witness, alpha: Double, scale: Double) {
            val touched = HashSet<Int>()
            sparse.touched(touched)
            if (mine) touched.addAll(self.zeros.keys)
            touched.forEach { v ->
                val ownZero = if (mine) self.zeros[v] ?: 0.0 else 0.0
                val ownOne = if (mine) self.ones[v] ?: 0.0 else 0.0
                val c0 = (cu + sparse.zeroOf(v) - ownZero).coerceAtLeast(0.0)
                val c1 = (sparse.oneOf(v) - ownOne).coerceAtLeast(0.0)
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
