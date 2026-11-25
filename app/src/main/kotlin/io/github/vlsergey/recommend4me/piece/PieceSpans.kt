package io.github.vlsergey.recommend4me.piece

/**
 * The best span of a text for a line searched for: of every span of [SPAN] times the line's pieces, the mean over the
 * line's pieces of the best cosine of a piece of the span (MaxSim, as ColBERT reads a passage) — a meaning is said in a
 * place of a text, and the best of every piece of a chapter of thousands is no place (owner, 2026-11-25: as good of 4,
 * 8 and 16 times the line). [from] until [to] the text's pieces, [marked] the piece every piece of the line matched
 * best.
 */
class PieceSpan(val score: Double, val from: Int, val to: Int, val marked: List<Int>)

object PieceSpans {
    /** The length of a span, in the line's pieces. */
    const val SPAN = 8

    /** The best span of the text of [codes] for the line's pieces [line], read by the [codebook]; null of no piece. */
    fun best(line: List<FloatArray>, codes: ByteArray, codebook: PieceCodebook): PieceSpan? {
        val n = codes.size / codebook.bytes
        if (n == 0 || line.isEmpty()) return null
        val m = line.size
        // The cosine of every piece of the text with every piece of the line
        val sim = Array(n) { FloatArray(m) }
        val v = FloatArray(codebook.width)
        for (t in 0 until n) {
            codebook.decode(codes, t * codebook.bytes, v)
            for (i in 0 until m) {
                var s = 0f
                val q = line[i]
                for (j in v.indices) s += q[j] * v[j]
                sim[t][i] = s
            }
        }
        val len = minOf(SPAN * m, n)
        val starts = n - len + 1
        val sum = DoubleArray(starts)
        val window = IntArray(n)
        // Of every piece of the line, the maximum of every span by a sliding maximum
        for (i in 0 until m) {
            var head = 0
            var tail = 0
            for (t in 0 until n) {
                while (tail > head && sim[window[tail - 1]][i] <= sim[t][i]) tail--
                window[tail++] = t
                if (window[head] <= t - len) head++
                val start = t - len + 1
                if (start >= 0) sum[start] += sim[window[head]][i].toDouble()
            }
        }
        var best = 0
        for (s in 1 until starts) if (sum[s] > sum[best]) best = s
        val marked = (0 until m).map { i -> (best until best + len).maxBy { sim[it][i] } }.distinct().sorted()
        return PieceSpan(sum[best] / m, best, best + len, marked)
    }
}
