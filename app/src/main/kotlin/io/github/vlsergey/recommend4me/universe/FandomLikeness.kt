package io.github.vlsergey.recommend4me.universe

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Two names of the sites' fandoms the user's word says are alike or not, for a third: [anchor] is likelier to [near]
 * than to [far].
 */
class NamePair(val anchor: FloatArray, val near: FloatArray, val far: FloatArray, val weight: Double)

/**
 * THE LIKENESS OF THE NAMES OF THE SITES' FANDOMS, LEARNT OF THE USER'S WORD ON THE UNIVERSES (user, 2026-11-14): the
 * cosine of two names' vectors with every dimension weighed ([weights], none below nought) — the encoder's own of
 * weights all 1. A name's vector made ready for it ([of]): every number times the root of its weight, of unit length
 * again — the cosine of two such is the weighed one.
 *
 * Learnt ([fit]) of the universes as the user made them: two names of one universe — the sites' fandoms the user bound
 * together — and the names of a universe and of one under or above it likelier than a name of another; the names of two
 * universes the user said different the least alike of all. The weights pulled toward 1, the encoder's likeness, as far
 * as the word says nothing.
 */
class FandomLikeness(val weights: FloatArray) {
    private val roots = FloatArray(weights.size) { sqrt(weights[it]) }

    /** The vector [v] of a name made ready: weighed, of unit length; a vector of another length as it is. */
    fun of(v: FloatArray): FloatArray {
        if (v.size != weights.size) return v
        val out = FloatArray(v.size) { v[it] * roots[it] }
        var n = 0.0
        for (x in out) n += x.toDouble() * x
        n = sqrt(n)
        if (n > 0) for (k in out.indices) out[k] = (out[k] / n).toFloat()
        return out
    }

    fun pack(): ByteArray {
        val buffer = ByteBuffer.allocate(weights.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(weights)
        return buffer.array()
    }

    companion object {
        fun unpack(bytes: ByteArray): FandomLikeness {
            val out = FloatArray(bytes.size / Float.SIZE_BYTES)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return FandomLikeness(out)
        }

        /** The share of the [pairs] the [likeness] — the encoder's own when null — puts in the user's order. */
        fun ordered(pairs: List<NamePair>, likeness: FandomLikeness?): Double {
            if (pairs.isEmpty()) return Double.NaN
            val w = likeness?.weights ?: FloatArray(pairs[0].anchor.size) { 1f }
            val right = pairs.sumOf { p ->
                if (cosine(w, p.anchor, p.near) > cosine(w, p.anchor, p.far)) p.weight else 0.0
            }
            return right / pairs.sumOf { it.weight }
        }

        /** The weights learnt of the [pairs], of vectors of one length ([FandomLikenessDescent]); null of none. */
        fun fit(pairs: List<NamePair>): FandomLikeness? =
            if (pairs.isEmpty()) null else FandomLikeness(FandomLikenessDescent(pairs).run())

        private fun cosine(w: FloatArray, a: FloatArray, y: FloatArray): Double {
            var ay = 0.0
            var b = 0.0
            var c = 0.0
            for (k in w.indices) {
                ay += w[k] * a[k] * y[k]
                b += w[k] * a[k] * a[k]
                c += w[k] * y[k] * y[k]
            }
            return ay / sqrt(b * c).coerceAtLeast(LEAST)
        }

        /**
         * The pairs of the user's word on the universes [anchors]: of every one — its sites' fandoms' names' vectors —
         * every name and another of it or of a universe [related] to it (under or above it) the nearer, a name of a
         * universe said [different] the farther — every one — and of [random] other universes one name each, drawn of
         * [seed]; the names of every universe, [names]. Every universe weighs alike, however many names it has.
         */
        fun pairs(
            anchors: Map<Long, List<FloatArray>>,
            related: Map<Long, Set<Long>>,
            different: Map<Long, Set<Long>>,
            names: Map<Long, List<FloatArray>>,
            random: Int = RANDOM_FAR,
            seed: Int = 0,
        ): List<NamePair> {
            val draw = Random(seed)
            val universes = names.keys.sorted()
            val out = ArrayList<NamePair>()
            anchors.keys.sorted().forEach { u ->
                val own = anchors.getValue(u)
                val kin = related[u].orEmpty()
                val apart = different[u].orEmpty()
                val made = ArrayList<Triple<FloatArray, FloatArray, FloatArray>>()
                own.forEachIndexed { i, anchor ->
                    val near = own.filterIndexed { j, _ -> j != i } + kin.flatMap { names[it].orEmpty() }
                    if (near.isEmpty()) return@forEachIndexed
                    val far =
                        apart.flatMap { names[it].orEmpty() } +
                            List(random) { universes[draw.nextInt(universes.size)] }
                                .filter { it != u && it !in kin && it !in apart }
                                .mapNotNull { names[it]?.let { n -> n[draw.nextInt(n.size)] } }
                    near.forEach { n -> far.forEach { f -> made += Triple(anchor, n, f) } }
                }
                made.forEach { (a, n, f) -> out += NamePair(a, n, f, 1.0 / made.size) }
            }
            return out
        }

        /** How many other universes a name of one is set below a name of its own: drawn at random. */
        const val RANDOM_FAR = 4

        /** The least length of a product of two weighed lengths divided by. */
        private const val LEAST = 1e-12
    }
}
