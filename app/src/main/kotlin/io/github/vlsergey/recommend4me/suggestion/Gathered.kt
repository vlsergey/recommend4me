package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import org.slf4j.LoggerFactory
import kotlin.concurrent.withLock

private val log = LoggerFactory.getLogger(Gathered::class.java)

/**
 * THE VECTORS OF ONE INPUT BLOCK AS THEY ARE READ, of the items that have it alone, in half
 * precision: each written where it stays till the block is laid out — a chunk of [CHUNK] rows after
 * another, none of the size of every item — with the item of every row; the database's half-precision
 * bytes copied as they are ([putHalves]). An item's second vector of the block is written over its
 * first; a vector of another length is of another encoder, not of this block.
 */
internal class Gathered(items: Int, private val dim: Int) {
    private val slot = IntArray(items) { -1 }
    private val chunks = ArrayList<HalfMatrix>()
    private var rows = IntArray(CHUNK)
    private var count = 0

    fun put(item: Int, v: FloatArray) {
        if (v.size != dim) return
        val k = slotOf(item)
        chunks[k / CHUNK].put(k % CHUNK, v)
    }

    fun putHalves(item: Int, bytes: ByteArray) {
        if (bytes.size != dim * 2) return
        val k = slotOf(item)
        chunks[k / CHUNK].putHalves(k % CHUNK, bytes)
    }

    /** The row of [item], made at its first vector. */
    private fun slotOf(item: Int): Int {
        var k = slot[item]
        if (k < 0) {
            k = count++
            slot[item] = k
            if (k / CHUNK == chunks.size) chunks += HalfMatrix(CHUNK, dim)
            if (k == rows.size) rows = rows.copyOf(rows.size * 2)
            rows[k] = item
        }
        return k
    }

    /** The block laid out whole, its rows one after another, with the item of each; the chunks let go. */
    fun laidOut(): Pair<HalfMatrix, IntArray> {
        val vectors = HalfMatrix(count, dim)
        chunks.forEachIndexed { c, chunk -> vectors.copyRows(chunk, 0, c * CHUNK, minOf(CHUNK, count - c * CHUNK)) }
        chunks.clear()
        return vectors to rows.copyOf(count)
    }

    companion object {
        /** The rows of a chunk: memory, not meaning. */
        const val CHUNK = 1024

        /** The rows of a block put on the card; null when it has no room — multiplied in the main memory then, said in the log. */
        fun onCard(card: Cublas, name: String, vectors: HalfMatrix, items: IntArray): CardRows? =
            card.lock.withLock { CardRows.put(card, vectors, items) }
                ?: null.also { log.warn("no room on the card for {} vectors of {} numbers of {}: they are multiplied in the main memory", vectors.rows, vectors.cols, name) }
    }
}
