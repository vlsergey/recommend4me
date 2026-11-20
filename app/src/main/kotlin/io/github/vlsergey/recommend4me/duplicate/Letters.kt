package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import io.github.vlsergey.recommend4me.matrix.SparseRows
import java.util.stream.IntStream
import kotlin.math.sqrt

/**
 * THE LETTERS OF A TITLE OR OF THE NAMES OF THE AUTHORS AS THE SEARCH OF DUPLICATES COMPARES THEM (user, 2026-11-18:
 * the titles and the authors compared as strings, not as vectors of meaning — approximately and fast): the string
 * normalized ([normalized]), then the counts of its trigrams, each in one of [WIDTH] buckets by its hash ([rows]) — the
 * cosine of two such rows how alike the two strings are letter by letter, the product over the lengths how much of
 * either the other has ([io.github.vlsergey.recommend4me.duplicate.Lexical]).
 */
internal object Letters {
    /** The buckets of the trigrams: a row of a work, of every title and every name of authors. */
    const val WIDTH = 512

    /** The bits of a bucket's number: [WIDTH] is two to their power. */
    private const val BITS = 9

    /** Fibonacci's multiplier: the hash of a trigram spread over the bits before the highest taken. */
    private const val SPREAD = -1640531535

    private const val PRIME = 31

    /** The letters of a trigram. */
    private const val TRIGRAM = 3

    /**
     * The [text] as it is compared: lower case, every run of what is no letter nor digit one space, trimmed (user,
     * 2026-11-18).
     */
    fun normalized(text: String): String {
        val out = StringBuilder(text.length)
        var space = false
        text.lowercase().forEach { c ->
            if (c.isLetterOrDigit()) {
                if (space && out.isNotEmpty()) out.append(' ')
                space = false
                out.append(c)
            } else space = true
        }
        return out.toString()
    }

    /**
     * Of every string of [texts] — none of one of none —, its trigrams' counts as a unit row, in half precision, only
     * those not nought kept (a short text's twenty of [WIDTH], owner, 2026-11-20: the search ran out of memory), and
     * the counts' length (nought of a string of no trigram): a row a string, side by side on every core.
     */
    fun rows(texts: List<String?>): Pair<SparseRows, FloatArray> {
        val lengths = FloatArray(texts.size)
        val rows =
            SparseRows.of(texts.size, WIDTH) { i ->
                counted(texts[i])?.let { (row, length) ->
                    lengths[i] = length
                    row
                }
            }
        return rows to lengths
    }

    /** The same of long texts — a description, of hundreds of trigrams —, every row whole. */
    fun wholeRows(texts: List<String?>): Pair<HalfMatrix, FloatArray> {
        val rows = HalfMatrix(texts.size, WIDTH)
        val lengths = FloatArray(texts.size)
        IntStream.range(0, texts.size).parallel().forEach { i ->
            counted(texts[i])?.let { (row, length) ->
                lengths[i] = length
                rows.put(i, row)
            }
        }
        return rows to lengths
    }

    /** The counts of the trigrams of [text] as a unit row, and their length; none of a string of none. */
    private fun counted(text: String?): Pair<FloatArray, Float>? {
        val normal = text?.let(::normalized)?.takeIf { it.isNotEmpty() } ?: return null
        val counts = FloatArray(WIDTH)
        // Padded by a space either side: a word's first and last letters a trigram of their own
        val padded = " $normal "
        for (k in 0..padded.length - TRIGRAM) {
            val hash = (padded[k].code * PRIME + padded[k + 1].code) * PRIME + padded[k + 2].code
            counts[(hash * SPREAD) ushr (Int.SIZE_BITS - BITS)]++
        }
        val length = sqrt(counts.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(WIDTH) { counts[it] / length } to length
    }
}
