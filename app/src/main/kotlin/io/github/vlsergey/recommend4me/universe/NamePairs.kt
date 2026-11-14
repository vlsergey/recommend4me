package io.github.vlsergey.recommend4me.universe

import kotlin.random.Random

/**
 * THE PAIRS OF THE USER'S WORD ON THE UNIVERSES, of rows of the names' vectors ([FandomRows.rows]), gathered by their
 * anchor and laid out flat — as the card reads them, and the processor alike: of the anchor i, the name of the row
 * [anchors]`[i]`, its candidates — every name it is set nearer or farther than, each once — [candidates] from
 * [candidateStart]`[i]` to [candidateStart]`[i + 1]`, and its pairs from [pairStart]`[i]` to [pairStart]`[i + 1]`: the
 * near and the far one as places in [candidates], and the weight — the pairs' summing to 1.
 */
class NamePairs(
    val anchors: IntArray,
    val candidateStart: IntArray,
    val candidates: IntArray,
    val pairStart: IntArray,
    val near: IntArray,
    val far: IntArray,
    val weight: FloatArray,
) {
    val size: Int
        get() = near.size

    /** The anchor of every place of [candidates]: its index among [anchors]. */
    fun anchorOfCandidate(): IntArray {
        val out = IntArray(candidates.size)
        for (i in anchors.indices) for (s in candidateStart[i] until candidateStart[i + 1]) out[s] = i
        return out
    }

    /** The anchor of every pair: its index among [anchors]. */
    fun anchorOfPair(): IntArray {
        val out = IntArray(near.size)
        for (i in anchors.indices) for (p in pairStart[i] until pairStart[i + 1]) out[p] = i
        return out
    }

    companion object {
        /**
         * The pairs of the universes [anchors] — each its names' rows —: every name and another of it or of a universe
         * [related] to it (under or above it) the nearer, a name of a universe said [different] the farther — every one
         * — and of [random] other universes one name each, drawn of [seed]; the names of every universe, [names]. Every
         * universe weighs alike, however many names and pairs it has.
         */
        fun of(
            anchors: Map<Long, IntArray>,
            related: Map<Long, Set<Long>>,
            different: Map<Long, Set<Long>>,
            names: Map<Long, IntArray>,
            random: Int = RANDOM_FAR,
            seed: Int = 0,
        ): NamePairs {
            val draw = Random(seed)
            val universes = names.keys.sorted()
            val byAnchor = LinkedHashMap<Int, Anchor>()
            val of = Ints()
            val nearOf = Ints()
            val farOf = Ints()
            anchors.keys.sorted().forEach { u ->
                val own = anchors.getValue(u)
                val kin = related[u].orEmpty()
                val apart = different[u].orEmpty()
                of.clear()
                nearOf.clear()
                farOf.clear()
                own.forEachIndexed { i, anchor ->
                    val near = own.filterIndexed { j, _ -> j != i } + kin.flatMap { names[it]?.toList().orEmpty() }
                    if (near.isEmpty()) return@forEachIndexed
                    val far =
                        apart.flatMap { names[it]?.toList().orEmpty() } +
                            List(random) { universes[draw.nextInt(universes.size)] }
                                .filter { it != u && it !in kin && it !in apart }
                                .mapNotNull { names[it]?.let { n -> n[draw.nextInt(n.size)] } }
                    near.forEach { n ->
                        far.forEach { f ->
                            of += anchor
                            nearOf += n
                            farOf += f
                        }
                    }
                }
                val weight = 1f / of.size.coerceAtLeast(1)
                for (k in 0 until of.size) byAnchor.getOrPut(of[k]) { Anchor() }.add(nearOf[k], farOf[k], weight)
            }
            return laid(byAnchor)
        }

        /** The pairs of every anchor laid out flat, their weights summing to 1. */
        private fun laid(byAnchor: Map<Int, Anchor>): NamePairs {
            val anchors = byAnchor.keys.toIntArray()
            val candidateStart = IntArray(anchors.size + 1)
            val pairStart = IntArray(anchors.size + 1)
            val candidates = Ints()
            val near = Ints()
            val far = Ints()
            val weight = Weights()
            var total = 0.0
            anchors.forEachIndexed { i, a ->
                val own = byAnchor.getValue(a)
                candidateStart[i] = candidates.size
                pairStart[i] = near.size
                val base = candidates.size
                own.rows.forEach { candidates += it }
                for (k in 0 until own.near.size) {
                    near += base + own.near[k]
                    far += base + own.far[k]
                }
                for (k in 0 until own.weight.size) {
                    weight += own.weight[k]
                    total += own.weight[k]
                }
            }
            candidateStart[anchors.size] = candidates.size
            pairStart[anchors.size] = near.size
            return NamePairs(
                anchors,
                candidateStart,
                candidates.toArray(),
                pairStart,
                near.toArray(),
                far.toArray(),
                FloatArray(weight.size) { (weight[it] / total).toFloat() },
            )
        }

        /** How many other universes a name of one is set below a name of its own: drawn at random. */
        const val RANDOM_FAR = 4

        /** The room a growing array starts with. */
        private const val INITIAL = 16
    }

    /** An anchor's candidates, each once, and its pairs as places among them. */
    private class Anchor {
        val rows = ArrayList<Int>()
        private val place = HashMap<Int, Int>()
        val near = Ints()
        val far = Ints()
        val weight = Weights()

        fun add(n: Int, f: Int, w: Float) {
            near += place.getOrPut(n) { rows.size.also { rows += n } }
            far += place.getOrPut(f) { rows.size.also { rows += f } }
            weight += w
        }
    }

    /** A growing array of floats, none boxed: the pairs are millions. */
    private class Weights {
        private var items = FloatArray(INITIAL)
        var size = 0
            private set

        operator fun plusAssign(x: Float) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = x
        }

        operator fun get(i: Int): Float = items[i]
    }

    /** A growing array of ints, none boxed: the pairs are millions. */
    private class Ints {
        private var items = IntArray(INITIAL)
        var size = 0
            private set

        operator fun plusAssign(x: Int) {
            if (size == items.size) items = items.copyOf(size * 2)
            items[size++] = x
        }

        operator fun get(i: Int): Int = items[i]

        fun clear() {
            size = 0
        }

        fun toArray(): IntArray = items.copyOf(size)
    }
}
