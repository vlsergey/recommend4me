package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import kotlin.math.pow
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(FandomLikenessOnCard::class.java)

/**
 * THE LEARNING OF THE LIKENESS OF THE FANDOMS' NAMES ON THE CARD (user, 2026-11-14), the same descent as the
 * processor's ([FandomLikenessDescent]) by kernels of its own (`likeness.cu`): the names' rows sent as they are kept —
 * half precision, straight from the block off the heap they were read into ([HalfMatrix]) — and the pairs laid out flat
 * ([NamePairs]), once; every step there, the weights, their slopes and Adam's moments never leaving the card; the
 * weights alone brought back at the end. Held by its caller under the card's lock; its room freed by [close].
 */
internal class FandomLikenessOnCard(card: FandomLikeness.OnCard, private val pairs: NamePairs, rows: HalfMatrix) :
    AutoCloseable {
    private val cublas = card.cublas
    private val kernels = card.kernels
    private val held = ArrayList<AutoCloseable>()
    private val dim = rows.cols
    private val names = rows.rows
    private val anchorCount = pairs.anchors.size
    private val slots = pairs.candidates.size

    init {
        require(dim % 2 == 0 && dim <= MAX_DIM) { "a name of $dim numbers is not read two at a time on the card" }
    }

    private fun bytes(count: Long): Cublas.DeviceBytes =
        (cublas.allocateBytes(count.coerceAtLeast(Int.SIZE_BYTES.toLong()))
                ?: error("the card has no room for $count bytes"))
            .also { held += it }

    private fun ints(of: IntArray) = bytes(of.size.toLong() * Int.SIZE_BYTES).also { it.upload(of) }

    private fun floats(count: Int): Cublas.DeviceFloats =
        (cublas.allocate(count.toLong().coerceAtLeast(1)) ?: error("the card has no room for $count floats")).also {
            held += it
        }

    private val sent = System.nanoTime()
    private val y = bytes(names.toLong() * dim * 2).also { it.upload(rows, 0, names) }
    private val c = floats(names)
    private val anchors = ints(pairs.anchors)
    private val candidateStart = ints(pairs.candidateStart)
    private val candidates = ints(pairs.candidates)
    private val pairStart = ints(pairs.pairStart)
    private val near = ints(pairs.near)
    private val far = ints(pairs.far)
    private val weight = bytes(pairs.size.toLong() * Float.SIZE_BYTES).also { it.upload(pairs.weight) }
    private val rowOfAnchor = pairs.anchorOfCandidate().let { a -> ints(IntArray(slots) { pairs.anchors[a[it]] }) }
    private val prod = floats(slots)
    private val root = floats(slots)
    private val coef = floats(slots)
    private val ofName = floats(names)
    private val w = floats(dim)
    private val m = floats(dim)
    private val v = floats(dim)
    private val grad = floats(dim)
    private val sending = System.nanoTime() - sent

    /** The weights learnt. */
    fun run(): FloatArray {
        val started = System.nanoTime()
        w.upload(Floats.of(FloatArray(dim) { 1f }), 0, 0, dim)
        m.zero(0, dim.toLong())
        v.zero(0, dim.toLong())
        grad.zero(0, dim.toLong())
        for (t in 1..FandomLikenessDescent.STEPS) {
            weighAndMultiply(w)
            ofName.zero(0, names.toLong())
            kernels.launch(
                "pairs",
                blocks(anchorCount),
                pairStart,
                near,
                far,
                weight,
                anchors,
                candidates,
                prod,
                root,
                c,
                coef,
                ofName,
                anchorCount,
                FandomLikenessDescent.TEMPERATURE.toFloat(),
            )
            kernels.launch("along", runs(slots) * (dim / 2), y, candidates, rowOfAnchor, coef, grad, slots, dim)
            kernels.launch("squares", runs(names) * (dim / 2), y, ofName, grad, names, dim)
            adam(t)
        }
        val out = Floats(dim)
        w.download(out, 0, 0, dim)
        log.info(
            "The likeness of the fandoms' names on the card: {} steps over {} pairs of {} anchors, {} products an " +
                "anchor and a name, {} names of {} numbers, sent in {} ms, learnt in {} ms",
            FandomLikenessDescent.STEPS,
            pairs.size,
            anchorCount,
            slots,
            names,
            dim,
            sending / NANOS,
            (System.nanoTime() - started) / NANOS,
        )
        return out.toArray()
    }

    /** A step of Adam of every weight, the [t]-th. */
    private fun adam(t: Int) {
        kernels.launch(
            "adam",
            dim.toLong(),
            w,
            m,
            v,
            grad,
            dim,
            FandomLikenessDescent.L2.toFloat(),
            FandomLikenessDescent.RATE.toFloat(),
            FandomLikenessDescent.BETA1.toFloat(),
            FandomLikenessDescent.BETA2.toFloat(),
            FandomLikenessDescent.EPSILON.toFloat(),
            (1 - FandomLikenessDescent.BETA1.pow(t)).toFloat(),
            (1 - FandomLikenessDescent.BETA2.pow(t)).toFloat(),
        )
    }

    /** The share of the pairs the weights [weights] put in the user's order: of each, the near the closer. */
    fun ordered(weights: FloatArray): Double {
        w.upload(Floats.of(weights), 0, 0, dim)
        weighAndMultiply(w)
        val sum = bytes(Double.SIZE_BYTES.toLong()).also { it.zero() }
        kernels.launch("right", blocks(anchorCount), pairStart, near, far, weight, prod, root, sum, anchorCount)
        return sum.doubles(1)[0]
    }

    /** Of every name its weighed square length, and of every anchor and candidate their product and √(B C). */
    private fun weighAndMultiply(weights: Cublas.DeviceFloats) {
        kernels.launch("weigh", blocks(names), y, weights, c, names, dim)
        kernels.launch(
            "products",
            blocks(anchorCount),
            y,
            weights,
            c,
            anchors,
            candidateStart,
            candidates,
            prod,
            root,
            coef,
            anchorCount,
            dim,
        )
    }

    override fun close() {
        held.asReversed().forEach { it.close() }
        held.clear()
    }

    companion object {
        /** The longest name a block holds weighed in its memory: the kernels' own. */
        const val MAX_DIM = 4096

        /** The rows a thread sums two dimensions of: the kernels' own. */
        private const val RUN = 256

        private const val NANOS = 1_000_000L

        /** The threads of a kernel of a block a thing — a name, an anchor —, [count] things. */
        private fun blocks(count: Int): Long = count.toLong() * CudaKernels.BLOCK

        /** The runs of [RUN] rows of [count]. */
        private fun runs(count: Int): Long = (count + RUN - 1L) / RUN

        /** The kernels of the likeness, compiled for the [card]; null when they are not. */
        fun kernels(card: Cublas): CudaKernels? =
            card.kernels(
                FandomLikenessOnCard::class.java.getResource("likeness.cu")!!.readText(),
                listOf("weigh", "products", "pairs", "along", "squares", "adam", "right", "cosines"),
            )
    }
}
