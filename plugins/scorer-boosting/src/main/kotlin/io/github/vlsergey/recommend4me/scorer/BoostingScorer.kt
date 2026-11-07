package io.github.vlsergey.recommend4me.scorer

import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * GRADIENT BOOSTED TREES FOR RANKING (LambdaMART without the Δ of a measure: the gradients of the pairwise logistic
 * loss, as RankNet's): the score is a sum of small trees, each learnt on what the trees before it put in the wrong
 * order. A tree splits by one feature at a time, so it finds what counts only together — a tag only with a long text, a
 * picture only on a site — by itself.
 *
 * Learnt from the same pairs ([RankingPairs]) by the same loss as the line. Every tree is grown by Newton's step (as in
 * XGBoost): the gain of a split and the value of a leaf from the first and the second derivatives of the loss by the
 * scores of its rows, with an L2 penalty [L2] on the leaves. Trees of [DEPTH] levels, every leaf with [LEAST_ROWS] rows
 * at least, a [SHARE] of the features a tree, shrunk by [RATE]: the usual settings of boosting on few rows.
 *
 * The parameter is the number of trees: the fewer, the more cautious.
 */
class BoostingScorer : Scorer {
    override val id = "boosted-trees"
    override val candidates = listOf(25.0, 50.0, 100.0, 200.0, 400.0)
    override val defaultParameter = 100.0

    override fun fit(task: RankingTask, rows: IntArray, parameter: Double): FittedScorer =
        fit(task.x, rows.distinct().toIntArray(), RankingPairs.of(task, rows), parameter.toInt())

    /** The most trees grown once; a fit of fewer is its first trees — the same trees a fit of them alone grows. */
    override fun fitEach(task: RankingTask, rows: IntArray, parameters: List<Double>): List<FittedScorer> {
        if (parameters.isEmpty()) return emptyList()
        val forest =
            fit(task.x, rows.distinct().toIntArray(), RankingPairs.of(task, rows), parameters.maxOf { it.toInt() })
        return parameters.map { Forest(forest.features, forest.trees.subList(0, it.toInt())) }
    }

    override fun unpack(bytes: ByteArray): FittedScorer = Forest.unpack(bytes)

    companion object {
        const val DEPTH = 3
        const val LEAST_ROWS = 3
        const val RATE = 0.1
        const val L2 = 1.0
        const val SHARE = 0.3

        /** Grows [trees] trees on the rows [learnt] of [x], by the [pairs] of them. */
        fun fit(x: Matrix, learnt: IntArray, pairs: RankingPairs, trees: Int): Forest {
            val grower = Grower(x, learnt)
            val z = Floats(x.rows)
            val forest = ArrayList<Tree>()
            val random = Random(1)
            val g = DoubleArray(x.rows)
            val h = DoubleArray(x.rows)
            repeat(trees) {
                derivatives(pairs, z, g, h)
                val tree = grower.grow(g, h, random)
                forest += tree
                learnt.forEach { i -> z[i] = z[i] + tree.score(x, i) }
            }
            return Forest(x.cols, forest)
        }

        /** The first and the second derivative of the pairs' loss by every row's score. */
        internal fun derivatives(pairs: RankingPairs, z: Floats, g: DoubleArray, h: DoubleArray) {
            g.fill(0.0)
            h.fill(0.0)
            fun pair(a: Int, b: Int, weight: Double, target: Double) {
                val p = RankingPairs.sigmoid((z[a] - z[b]).toDouble())
                val first = weight * (p - target)
                val second = weight * p * (1 - p)
                g[a] += first
                g[b] -= first
                h[a] += second
                h[b] += second
            }
            for (k in pairs.better.indices) pair(pairs.better[k], pairs.worse[k], 1.0, 1.0)
            for (k in pairs.first.indices) pair(pairs.first[k], pairs.second[k], pairs.equalWeight, EVEN)
        }

        /** The chance of a pair graded alike that the first is the better: neither is. */
        private const val EVEN = 0.5
    }
}

/**
 * Grows the trees on the rows [learnt]: every feature's rows sorted by its value once, for the whole fit; a node finds
 * its best split by one pass over every feature's sorted rows.
 */
internal class Grower(private val x: Matrix, private val learnt: IntArray) {
    private val d = x.cols
    private val m = learnt.size

    private val node = IntArray(m)

    /** The rows' features, a row of [learnt] a row: read straight, not through the matrix's segment. */
    private val values: Array<FloatArray> =
        Array(m) { k -> FloatArray(d).also { x.held.take(it, 0, x.row(learnt[k]), d) } }

    /**
     * For every feature, the positions in [learnt] in the order of its value, of equal values the first first: every
     * position and its value one number sorted — the value's bits made to order as the floats do, above the position —
     * not a list of boxed numbers a feature.
     */
    internal val sorted: Array<IntArray> = run {
        val keyed = LongArray(m)
        Array(d) { f ->
            for (k in 0 until m) {
                val bits = java.lang.Float.floatToIntBits(values[k][f])
                keyed[k] = ((bits xor ((bits shr 31) and Int.MAX_VALUE)).toLong() shl 32) or k.toLong()
            }
            keyed.sort()
            IntArray(m) { keyed[it].toInt() }
        }
    }

    fun grow(g: DoubleArray, h: DoubleArray, random: Random): Tree {
        val features =
            (0 until d).filter { random.nextDouble() < BoostingScorer.SHARE }.ifEmpty { listOf(random.nextInt(d)) }
        val nodes = ArrayList<Node>()
        node.fill(0)
        nodes += Node()
        // Level by level: the rows of every node of the level are split at once, a pass a feature;
        // the sums of a node kept by its number — a tree has few
        val splits = Splits(1 shl (BoostingScorer.DEPTH + 1))
        var level = listOf(0)
        for (depth in 0 until BoostingScorer.DEPTH) {
            if (level.isEmpty()) break
            splits.start(level)
            sumLevel(splits, g, h)
            for (f in features) findSplits(splits, f, g, h)
            level = branch(level, splits, nodes)
            descend(splits, nodes)
        }
        return leaves(nodes, g, h)
    }

    /** The sums of the derivatives and the number of rows of every node of the level. */
    private fun sumLevel(splits: Splits, g: DoubleArray, h: DoubleArray) {
        val inLevel = splits.inLevel
        val sumG = splits.sumG
        val sumH = splits.sumH
        val rowsOf = splits.rowsOf
        for (k in 0 until m) {
            val n = node[k]
            if (!inLevel[n]) continue
            sumG[n] += g[learnt[k]]
            sumH[n] += h[learnt[k]]
            rowsOf[n]++
        }
    }

    /**
     * One pass over the rows of the feature [f] in the order of its value: every node of the level keeps the split by
     * it if it gains more than the best found before.
     */
    private fun findSplits(splits: Splits, f: Int, g: DoubleArray, h: DoubleArray) {
        splits.startFeature()
        val inLevel = splits.inLevel
        val leftG = splits.leftG
        val leftH = splits.leftH
        val leftRows = splits.leftRows
        val lastValue = splits.lastValue
        for (k in sorted[f]) {
            val n = node[k]
            if (!inLevel[n]) continue
            val value = values[k][f]
            val lr = leftRows[n]
            // A split between two different values, both sides big enough
            if (
                lr >= BoostingScorer.LEAST_ROWS &&
                    splits.rowsOf[n] - lr >= BoostingScorer.LEAST_ROWS &&
                    value > lastValue[n]
            ) {
                splits.offer(n, f, (lastValue[n] + value) / 2)
            }
            leftG[n] += g[learnt[k]]
            leftH[n] += h[learnt[k]]
            leftRows[n] = lr + 1
            lastValue[n] = value
        }
    }

    /** The nodes of the level that found a split get their two children; the children are the next level. */
    private fun branch(level: List<Int>, splits: Splits, nodes: ArrayList<Node>): List<Int> {
        val next = ArrayList<Int>()
        level.forEach { n ->
            if (splits.feature[n] < 0) return@forEach
            val left = nodes.size
            nodes += Node()
            nodes += Node()
            nodes[n].feature = splits.feature[n]
            nodes[n].threshold = splits.threshold[n]
            nodes[n].left = left
            next += left
            next += left + 1
        }
        return next
    }

    /** Every row of a node of the level that was split goes down to the child its value falls in. */
    private fun descend(splits: Splits, nodes: List<Node>) {
        for (k in 0 until m) {
            val n = node[k]
            if (!splits.inLevel[n] || nodes[n].left < 0) continue
            node[k] = if (values[k][nodes[n].feature] < nodes[n].threshold) nodes[n].left else nodes[n].left + 1
        }
    }

    /** The leaves: Newton's step, shrunk. */
    private fun leaves(nodes: List<Node>, g: DoubleArray, h: DoubleArray): Tree {
        val leafG = DoubleArray(nodes.size)
        val leafH = DoubleArray(nodes.size)
        for (k in 0 until m) {
            leafG[node[k]] += g[learnt[k]]
            leafH[node[k]] += h[learnt[k]]
        }
        nodes.forEachIndexed { n, leaf ->
            if (leaf.left < 0) {
                leaf.value = (-BoostingScorer.RATE * leafG[n] / (leafH[n] + BoostingScorer.L2)).toFloat()
            }
        }
        return Tree(
            IntArray(nodes.size) { nodes[it].feature },
            FloatArray(nodes.size) { nodes[it].threshold },
            IntArray(nodes.size) { nodes[it].left },
            FloatArray(nodes.size) { nodes[it].value },
        )
    }

    private class Node(var feature: Int = -1, var threshold: Float = 0f, var left: Int = -1, var value: Float = 0f)

    /**
     * The nodes of a level, kept by their numbers: the sums of their rows' derivatives, the left side of the pass over
     * a feature, and the best split found for each.
     */
    private class Splits(most: Int) {
        val inLevel = BooleanArray(most)
        val sumG = DoubleArray(most)
        val sumH = DoubleArray(most)
        val rowsOf = IntArray(most)
        val leftG = DoubleArray(most)
        val leftH = DoubleArray(most)
        val leftRows = IntArray(most)
        val lastValue = FloatArray(most)
        val gain = DoubleArray(most)
        val feature = IntArray(most)
        val threshold = FloatArray(most)

        /** A new level: its nodes marked, their sums and best splits forgotten. */
        fun start(level: List<Int>) {
            inLevel.fill(false)
            level.forEach { inLevel[it] = true }
            sumG.fill(0.0)
            sumH.fill(0.0)
            rowsOf.fill(0)
            gain.fill(0.0)
            feature.fill(-1)
        }

        /** A new pass, of another feature: the left sides empty. */
        fun startFeature() {
            leftG.fill(0.0)
            leftH.fill(0.0)
            leftRows.fill(0)
        }

        /**
         * The split of the node [n] by the feature [f] at [at], between its rows passed and the rest, kept if it gains
         * the most yet: Newton's gain, the sides' squared first derivatives over their second ones.
         */
        fun offer(n: Int, f: Int, at: Float) {
            val gl = leftG[n]
            val hl = leftH[n]
            val gr = sumG[n] - gl
            val hr = sumH[n] - hl
            val gained =
                gl * gl / (hl + BoostingScorer.L2) + gr * gr / (hr + BoostingScorer.L2) -
                    sumG[n] * sumG[n] / (sumH[n] + BoostingScorer.L2)
            if (gained > gain[n]) {
                gain[n] = gained
                feature[n] = f
                threshold[n] = at
            }
        }
    }
}

/** A tree: every node's feature and threshold, its left child (the right one after it; -1: a leaf), a leaf's value. */
class Tree(val feature: IntArray, val threshold: FloatArray, val left: IntArray, val value: FloatArray) {
    fun score(x: Matrix, row: Int): Float {
        var n = 0
        while (left[n] >= 0) n = if (x.held[x.row(row) + feature[n]] < threshold[n]) left[n] else left[n] + 1
        return value[n]
    }
}

/** The trees of a fit; a row's score is the sum of theirs. */
class Forest(val features: Int, val trees: List<Tree>) : FittedScorer {
    override fun scores(x: Matrix, rows: Int): FloatArray {
        check(x.cols == features) { "The trees split rows of $features features, not of ${x.cols}" }
        return FloatArray(rows) { i -> trees.fold(0f) { s, t -> s + t.score(x, i) } }
    }

    /** Little-endian: the features, the trees, and every tree's size and arrays. */
    override fun pack(): ByteArray {
        val size = 2 * Int.SIZE_BYTES + trees.sumOf { Int.SIZE_BYTES + it.feature.size * 4 * 4 }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(features).putInt(trees.size)
        trees.forEach { t ->
            buffer.putInt(t.feature.size)
            t.feature.forEach { buffer.putInt(it) }
            t.threshold.forEach { buffer.putFloat(it) }
            t.left.forEach { buffer.putInt(it) }
            t.value.forEach { buffer.putFloat(it) }
        }
        return buffer.array()
    }

    companion object {
        fun unpack(bytes: ByteArray): Forest {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val features = buffer.getInt()
            val trees =
                List(buffer.getInt()) {
                    val n = buffer.getInt()
                    Tree(
                        IntArray(n) { buffer.getInt() },
                        FloatArray(n) { buffer.getFloat() },
                        IntArray(n) { buffer.getInt() },
                        FloatArray(n) { buffer.getFloat() },
                    )
                }
            return Forest(features, trees)
        }
    }
}
