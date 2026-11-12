package io.github.vlsergey.recommend4me.suggestion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.IdentityHashMap

/**
 * WHAT THE WORKS THE MODEL KNOWS SAY OF THE VALUES, as the pairs every suggester is judged by ([SuggestionPairs]) keep
 * it: of every work, the weight of a value it may have and merely lacks ([lack]), the values it may have ([mayOf], an
 * index of [mays] — one array for every work of the same; -1: every value), and its own pairs apart from those ([own],
 * [ownWeight], [ownLabel]). Of [m] values. Kept in the model: a work's neighbours are read of it, not of the task of
 * the works asked.
 */
internal class Labels(
    val m: Int,
    val lack: FloatArray,
    val mays: List<IntArray>,
    val mayOf: IntArray,
    val own: Array<IntArray>,
    val ownWeight: Array<FloatArray>,
    val ownLabel: Array<FloatArray>,
) {
    val n: Int
        get() = lack.size

    /** Every pair of the work [i], its values ascending: [action] of its value, weight and label. */
    inline fun forEach(i: Int, action: (value: Int, weight: Float, label: Float) -> Unit) {
        val values = own[i]
        val weights = ownWeight[i]
        val labels = ownLabel[i]
        var k = 0
        val lacking = lack[i]
        if (lacking > 0f) {
            val allowed = mayOf[i].takeIf { it >= 0 }?.let { mays[it] }
            val size = allowed?.size ?: m
            for (t in 0 until size) {
                val v = allowed?.get(t) ?: t
                while (k < values.size && values[k] < v) {
                    if (weights[k] != 0f) action(values[k], weights[k], labels[k])
                    k++
                }
                if (k < values.size && values[k] == v) {
                    if (weights[k] != 0f) action(v, weights[k], labels[k])
                    k++
                } else action(v, lacking, 0f)
            }
        }
        while (k < values.size) {
            if (weights[k] != 0f) action(values[k], weights[k], labels[k])
            k++
        }
    }

    /** Of every value, its weighted share labelled 1 among the pairs of the works [counted]. */
    fun share(counted: (Int) -> Boolean): DoubleArray {
        val one = DoubleArray(m)
        val all = DoubleArray(m)
        for (i in 0 until n) {
            if (!counted(i)) continue
            forEach(i) { v, w, y ->
                all[v] += w.toDouble()
                one[v] += (w * y).toDouble()
            }
        }
        return DoubleArray(m) { if (all[it] > 0) one[it] / all[it] else 0.0 }
    }

    /**
     * THE CHANCE OF EVERY VALUE of a work whose nearest works are [rows] — the nearest first, alike by [likeness] — of
     * its [k] nearest that [usable] allows (those of the other folds, of a held-out one): the weighted share labelled 1
     * of their pairs with the value, each by its likeness, drawn towards the value's [share] among all by [PRIOR].
     */
    fun chances(
        rows: IntArray,
        likeness: FloatArray,
        share: DoubleArray,
        k: Int,
        usable: (Int) -> Boolean,
    ): FloatArray {
        val one = DoubleArray(m)
        val all = DoubleArray(m)
        var taken = 0
        var t = 0
        while (t < rows.size && taken < k) {
            val j = rows[t]
            if (usable(j)) {
                taken++
                val s = likeness[t].coerceAtLeast(0f).toDouble()
                if (s != 0.0) {
                    forEach(j) { v, weight, y ->
                        val w = s * weight
                        all[v] += w
                        one[v] += w * y
                    }
                }
            }
            t++
        }
        return FloatArray(m) { v -> ((one[v] + PRIOR * share[v]) / (all[v] + PRIOR)).toFloat() }
    }

    fun write(out: DataOutputStream) {
        out.writeInt(m)
        out.writeInt(n)
        lack.forEach(out::writeFloat)
        out.writeInt(mays.size)
        mays.forEach { a ->
            out.writeInt(a.size)
            a.forEach(out::writeInt)
        }
        mayOf.forEach(out::writeInt)
        for (i in 0 until n) {
            out.writeInt(own[i].size)
            own[i].forEach(out::writeInt)
            ownWeight[i].forEach(out::writeFloat)
            ownLabel[i].forEach(out::writeFloat)
        }
    }

    companion object {
        /** How much the value's share among all the works weighs against the neighbours': one work's worth. */
        private const val PRIOR = 1.0

        /** The pairs of every work of a task as the model keeps them. */
        fun of(pairs: SuggestionPairs): Labels {
            val index = IdentityHashMap<IntArray, Int>()
            val mays = ArrayList<IntArray>()
            val mayOf =
                IntArray(pairs.n) { i ->
                    pairs.may[i]?.let { a -> index.getOrPut(a) { mays.add(a).let { mays.size - 1 } } } ?: -1
                }
            return Labels(pairs.m, pairs.lack, mays, mayOf, pairs.own, pairs.ownWeight, pairs.ownLabel)
        }

        fun read(input: DataInputStream): Labels {
            val m = input.readInt()
            val n = input.readInt()
            val lack = FloatArray(n) { input.readFloat() }
            val mays = List(input.readInt()) { IntArray(input.readInt()) { input.readInt() } }
            val mayOf = IntArray(n) { input.readInt() }
            val own = arrayOfNulls<IntArray>(n)
            val weights = arrayOfNulls<FloatArray>(n)
            val labels = arrayOfNulls<FloatArray>(n)
            for (i in 0 until n) {
                val size = input.readInt()
                own[i] = IntArray(size) { input.readInt() }
                weights[i] = FloatArray(size) { input.readFloat() }
                labels[i] = FloatArray(size) { input.readFloat() }
            }
            return Labels(
                m,
                lack,
                mays,
                mayOf,
                Array(n) { own[it]!! },
                Array(n) { weights[it]!! },
                Array(n) { labels[it]!! },
            )
        }
    }
}
