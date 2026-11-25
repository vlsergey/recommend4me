package io.github.vlsergey.recommend4me.fulltext

import java.nio.ByteBuffer

/** A window of a text: its characters [from] until [to] in the text, and its vector. */
class TextWindow(val from: Int, val to: Int, val vector: FloatArray)

/**
 * THE FULL-TEXT VECTORS OF THE WINDOWS, what a search by meaning reads of every window of every chapter and of every
 * text searched by meaning ([io.github.vlsergey.recommend4me.part.FullTextVectorRepository]), whole
 * ([FullTextNearest]). Made as RaBitQ makes its codes (Gao and Long, SIGMOD 2024; Lucene's and Elasticsearch's BBQ): a
 * window's vector less the type's centre, turned by the type's random rotation ([FullTextCentre.turned]), of every
 * number whether it is above nought — a bit a number, 128 bytes a window of bge-m3. A line's vector turned alike, in 4
 * bits a number — 16 levels evenly from its least to its most —, held against them by counting bits (`popcount`): of
 * every bit of the line's levels, the bits it shares with the window's. Measured on 85 retellings among 129 250 windows
 * (owner, 2026-11-25), the chapter of the answer among the first 100 70 times, among the first 300 81 — of the vectors
 * themselves 67 and 79; not turned 66 and 78, turned by a Hadamard transform of random signs 67 and 80; not less the
 * centre 63 and 77; of the 256 leading directions 59 and 73; of a product quantization of 64 bytes 51 and 72.
 *
 * Kept of a text as one value: the words of 64 bits of a window's bits, how many windows, then of every window its
 * characters and its bits.
 */
object FullTextVectors {
    /** The full-text vectors of the [windows] of a text by the [centre], as kept. */
    fun pack(windows: List<TextWindow>, centre: FullTextCentre): ByteArray {
        val words = wordsOf(centre.centre.size)
        val out = ByteBuffer.allocate(HEAD + windows.size * (WINDOW_HEAD + words * Long.SIZE_BYTES))
        out.putInt(words).putInt(windows.size)
        windows.forEach { w ->
            out.putInt(w.from).putInt(w.to)
            val turned = centre.turned(w.vector)
            val bits = LongArray(words)
            for (i in turned.indices) if (turned[i] > 0)
                bits[i / Long.SIZE_BITS] = bits[i / Long.SIZE_BITS] or (1L shl (i % Long.SIZE_BITS))
            bits.forEach(out::putLong)
        }
        return out.array()
    }

    /**
     * Whether [packed] is of the format the [query] reads: of as many words of bits a window, as long as its windows
     * take. A value of a format before, or of another encoder's centre, is not — its text waits for its stage to make
     * it anew ([io.github.vlsergey.recommend4me.part.ChapterEncoding]); read as one, it reads beyond its end.
     */
    fun readable(packed: ByteArray, query: Query): Boolean {
        if (packed.size < HEAD) return false
        val buffer = ByteBuffer.wrap(packed)
        val words = buffer.getInt(0)
        val count = buffer.getInt(Int.SIZE_BYTES)
        return words == query.words &&
            count >= 0 &&
            packed.size == HEAD + count * (WINDOW_HEAD + words * Long.SIZE_BYTES)
    }

    /** Every window of the text kept as [packed]: its characters, and where its bits begin in [packed]. */
    inline fun forEachWindow(packed: ByteArray, action: (from: Int, to: Int, at: Int) -> Unit) {
        val buffer = ByteBuffer.wrap(packed)
        val words = buffer.getInt(0)
        val count = buffer.getInt(Int.SIZE_BYTES)
        var at = HEAD
        repeat(count) {
            action(buffer.getInt(at), buffer.getInt(at + Int.SIZE_BYTES), at + WINDOW_HEAD)
            at += WINDOW_HEAD + words * Long.SIZE_BYTES
        }
    }

    /**
     * A line's vector [q] as it is held against the full-text vectors of the [centre]: turned, in 4 bits a number — of
     * every bit of the levels, the numbers whose level has it ([Query.planes]).
     */
    fun query(q: FloatArray, centre: FullTextCentre): Query {
        val turned = centre.turned(q)
        val least = turned.min()
        val step = ((turned.max() - least) / (LEVELS - 1)).takeIf { it > 0f } ?: 1f
        val words = wordsOf(turned.size)
        val planes = Array(BITS) { LongArray(words) }
        for (i in turned.indices) {
            val level = Math.round((turned[i] - least) / step)
            for (b in 0 until BITS) if ((level shr b) and 1 == 1)
                planes[b][i / Long.SIZE_BITS] = planes[b][i / Long.SIZE_BITS] or (1L shl (i % Long.SIZE_BITS))
        }
        return Query(planes, least, step)
    }

    /**
     * A line as it is held against the full-text vectors ([query]): of a window, the sum of the line's numbers at the
     * window's bits — every number its level times the [step] above the [least] —, what the dot product of the line and
     * the window's signs grows with.
     */
    class Query(private val planes: Array<LongArray>, private val least: Float, private val step: Float) {
        /** The words of 64 bits of a window's bits it reads. */
        val words
            get() = planes[0].size

        /** The score of the window whose bits begin at [at] of [packed]: the higher, the nearer. */
        fun score(packed: ByteArray, at: Int): Float {
            val buffer = ByteBuffer.wrap(packed)
            var count = 0
            var levels = 0L
            for (k in planes[0].indices) {
                val bits = buffer.getLong(at + k * Long.SIZE_BYTES)
                count += java.lang.Long.bitCount(bits)
                for (b in planes.indices) levels += java.lang.Long.bitCount(bits and planes[b][k]).toLong() shl b
            }
            return count * least + levels * step
        }
    }

    private fun wordsOf(numbers: Int) = (numbers + Long.SIZE_BITS - 1) / Long.SIZE_BITS

    /** The bytes before the windows: a window's words of bits, how many windows. */
    const val HEAD = 2 * Int.SIZE_BYTES

    /** The bytes of a window before its bits: its characters from and until. */
    const val WINDOW_HEAD = 2 * Int.SIZE_BYTES

    /** The bits of a number of the line, and its levels. */
    private const val BITS = 4
    private const val LEVELS = 1 shl BITS
}
