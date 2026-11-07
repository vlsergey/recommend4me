package io.github.vlsergey.recommend4me.tagtext

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.part.Paragraph

/**
 * OF EVERY TAG ITS [TOP] PARAGRAPHS OF A WORK CLOSEST TO IT, by its closest name, a paragraph answered on for the tag
 * left out: every name of every tag held against a block of paragraphs at a time — a product of the two matrices.
 */
internal class ClosestParagraphs
private constructor(
    /** The tags' ids, in the order of their names' [vectors]. */
    private val tagIds: LongArray,
    /** Every tag's names' vectors, a list a tag. */
    vectors: Collection<List<FloatArray>>,
    /** The length of a vector. */
    private val dim: Int,
    /** The paragraphs answered on for a tag, of any cutting, by the hash of their text: tag to hash. */
    private val answered: Set<Pair<Long, String>>,
) {
    /** Of every tag the closenesses of its closest paragraphs, the closest first; -∞ where there is none. */
    val best = Array(tagIds.size) { DoubleArray(TOP) { Double.NEGATIVE_INFINITY } }

    /** Of every tag the indices of its closest paragraphs, in the order of [best]; -1 where there is none. */
    val at = Array(tagIds.size) { IntArray(TOP) { -1 } }

    /** The tag of every name, a row of [names] each. */
    private val owners: IntArray = vectors.withIndex().flatMap { (t, names) -> List(names.size) { t } }.toIntArray()

    /** Every name of every tag a row of [dim]. */
    private val names = Floats(owners.size * dim)

    /** Of the tags their closeness to the paragraph held: by its closest name. */
    private val ofTag = DoubleArray(tagIds.size)

    init {
        var row = 0
        vectors.forEach { list ->
            list.forEach { v ->
                for (q in 0 until dim) names[row * dim + q] = v[q]
                row++
            }
        }
    }

    /** The [paragraphs] held against every name, a block of them at a time. */
    private fun hold(paragraphs: List<Paragraph>) {
        val block = minOf(paragraphs.size, BLOCK)
        val p = Floats(block * dim)
        val s = Floats(block * owners.size)
        for (from in paragraphs.indices step block) {
            val rows = minOf(block, paragraphs.size - from)
            for (k in 0 until rows) {
                val v = paragraphs[from + k].vector
                for (q in 0 until dim) p[k * dim + q] = v[q]
            }
            Blas.gemm(false, true, rows, owners.size, dim, p, 0, dim, names, 0, dim, s, 0, owners.size)
            for (k in 0 until rows) offer(paragraphs[from + k].hash, from + k, s, k * owners.size)
        }
    }

    /**
     * The paragraph [index] (its text's [hash]) offered to every tag by its closest name — its closenesses to the names
     * in [s] from [offset] on: kept among a tag's closest when it is one, and not answered on for the tag.
     */
    private fun offer(hash: String, index: Int, s: Floats, offset: Int) {
        ofTag.fill(Double.NEGATIVE_INFINITY)
        for (n in owners.indices) {
            val t = owners[n]
            val c = s[offset + n].toDouble()
            if (c > ofTag[t]) ofTag[t] = c
        }
        for (t in tagIds.indices) {
            val c = ofTag[t]
            if (c > best[t][TOP - 1] && (tagIds[t] to hash) !in answered) keep(t, c, index)
        }
    }

    /** The paragraph [index] of the closeness [c] put among the closest of the tag [t], in order; the last one out. */
    private fun keep(t: Int, c: Double, index: Int) {
        var place = TOP - 1
        while (place > 0 && best[t][place - 1] < c) {
            best[t][place] = best[t][place - 1]
            at[t][place] = at[t][place - 1]
            place--
        }
        best[t][place] = c
        at[t][place] = index
    }

    companion object {
        /** The paragraphs a tag's closeness is the mean of. */
        const val TOP = 3

        /** Paragraphs held against the tags at once: memory, not meaning. */
        private const val BLOCK = 256

        /**
         * Of every tag of [tagIds] — its names' [vectors] in the same order — its closest of the [paragraphs] (not
         * empty), a paragraph [answered] on for it left out.
         */
        fun of(
            paragraphs: List<Paragraph>,
            tagIds: LongArray,
            vectors: Collection<List<FloatArray>>,
            answered: Set<Pair<Long, String>>,
        ): ClosestParagraphs =
            ClosestParagraphs(tagIds, vectors, paragraphs.first().vector.size, answered).also { it.hold(paragraphs) }
    }
}
