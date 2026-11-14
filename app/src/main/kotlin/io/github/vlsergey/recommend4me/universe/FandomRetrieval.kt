package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import java.util.stream.IntStream
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * How a way of making the vectors of the sites' fandoms found the universes: the share found first, the mean 1/rank.
 */
class FandomFound(val first: Double, val reciprocal: Double)

/** The ways a site's fandom is read for its likeness: its name alone, its description a synonym, or added to it. */
enum class FandomWay {
    NAMES,
    SYNONYMS,
    ADDED,
}

/**
 * HOW WELL A WAY OF READING THE SITES' FANDOMS FINDS THEIR UNIVERSES, as the user made them: of every universe of two
 * sites' fandoms and more, its first fandom asked for, every other fandom of every universe put in the order of its
 * likeness to it, and where the first of its own universe stands. The ways ([FandomWay]): the name alone; the
 * description a synonym of the name — the best cosine of a vector of each —; the description added to the name — the
 * cosine of the sums of their unit vectors. Every likeness made of the cosines of the rows of the fandoms asked for and
 * of every row ([cosines]): on the card when there is one, a kernel of the likeness's, else on the processor.
 */
internal object FandomRetrieval {

    /** The finding of the [universes] — each its sites' fandoms — of every way; none of none asked. */
    fun of(
        universes: Map<Long, List<SiteFandomRef>>,
        fandoms: FandomRows,
        card: FandomLikeness.OnCard?,
    ): Map<FandomWay, FandomFound?> {
        val owner = HashMap<SiteFandomRef, Long>()
        universes.forEach { (u, own) -> own.forEach { owner[it] = u } }
        val asked = universes.mapNotNull { (u, own) ->
            own.filter { it in fandoms.ofFandom }
                .sortedBy { it.toString() }
                .takeIf { it.size >= 2 }
                ?.let { u to it.first() }
        }
        if (asked.isEmpty()) return FandomWay.entries.associateWith { null }
        val askedRows = asked.flatMap { (_, ref) -> rowsOf(fandoms.ofFandom.getValue(ref)) }.distinct().toIntArray()
        val place = askedRows.withIndex().associate { (i, row) -> row to i }
        val n = fandoms.rows.rows
        val cos = cosines(fandoms.rows, askedRows, card)
        // Of every fandom the cosine of its name and its description, for the length of their sum
        val norm1 = norms(fandoms)
        val refs = fandoms.ofFandom.keys.sortedBy { it.toString() }
        return FandomWay.entries.associateWith { way ->
            var first = 0.0
            var reciprocal = 0.0
            asked.forEach { (u, query) ->
                val q = fandoms.ofFandom.getValue(query)
                val qRows = rowsOf(q).map { place.getValue(it) }
                val scores =
                    refs
                        .filter { it != query }
                        .map { ref ->
                            val f = fandoms.ofFandom.getValue(ref)
                            ref to score(way, qRows, rowsOf(f), cos, n, norm1[query]!!, norm1[ref]!!)
                        }
                val rank = scores.sortedByDescending { it.second }.indexOfFirst { owner[it.first] == u } + 1
                if (rank == 1) first++
                if (rank > 0) reciprocal += 1.0 / rank
            }
            FandomFound(first / asked.size, reciprocal / asked.size)
        }
    }

    /** A fandom's rows: its name's, and its description's when it has one. */
    private fun rowsOf(f: FandomRowsOf): List<Int> = listOfNotNull(f.name, f.description.takeIf { it >= 0 })

    /**
     * The likeness by the [way] of a fandom of the asked rows [q] — places among the rows asked for — and one of the
     * [rows], of the cosines [cos] of every row asked for with every one of [n]; [nq] and [nf] the lengths of the sums
     * of their unit vectors.
     */
    private fun score(
        way: FandomWay,
        q: List<Int>,
        rows: List<Int>,
        cos: Floats,
        n: Int,
        nq: Double,
        nf: Double,
    ): Double =
        when (way) {
            FandomWay.NAMES -> cos[q[0] * n + rows[0]].toDouble()
            FandomWay.SYNONYMS -> q.maxOf { i -> rows.maxOf { j -> cos[i * n + j].toDouble() } }
            FandomWay.ADDED -> q.sumOf { i -> rows.sumOf { j -> cos[i * n + j].toDouble() } } / (nq * nf)
        }

    /** Of every fandom, the length of the sum of the unit vectors of its name and its description: √(2 + 2 cos). */
    private fun norms(fandoms: FandomRows): Map<SiteFandomRef, Double> {
        val rows = fandoms.rows
        val a = FloatArray(rows.cols)
        val b = FloatArray(rows.cols)
        return fandoms.ofFandom.mapValues { (_, f) ->
            if (f.description < 0) 1.0
            else {
                rows.take(f.name, a)
                rows.take(f.description, b)
                sqrt(2 + 2 * cosine(a, b))
            }
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (k in a.indices) {
            ab += a[k] * b[k]
            aa += a[k] * a[k]
            bb += b[k] * b[k]
        }
        return ab / sqrt((aa * bb).coerceAtLeast(LEAST))
    }

    /** The cosine of every row [asked] with every row of [rows]: asked × rows, row after row. */
    private fun cosines(rows: HalfMatrix, asked: IntArray, card: FandomLikeness.OnCard?): Floats {
        val n = rows.rows
        val out = Floats(asked.size * n)
        if (card != null) {
            card.cublas.lock.withLock {
                val held = ArrayList<AutoCloseable>()
                try {
                    val y = card.cublas.allocateBytes(n.toLong() * rows.cols * 2)!!.also { held += it }
                    y.upload(rows, 0, n)
                    val ones = card.cublas.allocate(rows.cols.toLong())!!.also { held += it }
                    ones.upload(Floats.of(FloatArray(rows.cols) { 1f }), 0, 0, rows.cols)
                    val c = card.cublas.allocate(n.toLong())!!.also { held += it }
                    val at = card.cublas.allocateBytes(asked.size.toLong() * Int.SIZE_BYTES)!!.also { held += it }
                    at.upload(asked)
                    val result = card.cublas.allocate(out.count.toLong())!!.also { held += it }
                    card.kernels.launch("weigh", n.toLong() * CudaKernels.BLOCK, y, ones, c, n, rows.cols)
                    val runs = (n + RUN - 1L) / RUN
                    card.kernels.launch(
                        "cosines",
                        asked.size * runs * CudaKernels.BLOCK,
                        y,
                        c,
                        at,
                        asked.size,
                        n,
                        rows.cols,
                        result,
                    )
                    result.download(out, 0, 0, out.count)
                } finally {
                    held.asReversed().forEach { it.close() }
                }
            }
            return out
        }
        val all = List(n) { i -> FloatArray(rows.cols).also { rows.take(i, it) } }
        val length = DoubleArray(n) { i -> sqrt(all[i].sumOf { (it * it).toDouble() }.coerceAtLeast(LEAST)) }
        IntStream.range(0, asked.size).parallel().forEach { i ->
            val a = all[asked[i]]
            for (j in 0 until n) {
                val b = all[j]
                var s = 0.0
                for (k in a.indices) s += a[k] * b[k]
                out[i * n + j] = (s / (length[asked[i]] * length[j])).toFloat()
            }
        }
        return out
    }

    /** The rows a block of the kernel of the cosines takes: the kernel's own. */
    private const val RUN = 256

    private const val LEAST = 1e-24
}
