package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.matrix.Matrix
import org.slf4j.LoggerFactory
import kotlin.concurrent.withLock

private val log = LoggerFactory.getLogger(Gathered::class.java)

/**
 * THE VECTORS OF ONE INPUT BLOCK AS THEY ARE READ, of the items that have it alone: each written
 * where it stays till the block is laid out — a chunk of [CHUNK] rows after another, none of the
 * size of every item — with the item of every row. An item's second vector of the block is written
 * over its first; a vector of another length is of another encoder, not of this block.
 */
internal class Gathered(items: Int, private val dim: Int) {
    private val slot = IntArray(items) { -1 }
    private val chunks = ArrayList<Floats>()
    private var rows = IntArray(CHUNK)
    private var count = 0

    fun put(item: Int, v: FloatArray) {
        if (v.size != dim) return
        var k = slot[item]
        if (k < 0) {
            k = count++
            slot[item] = k
            if (k / CHUNK == chunks.size) chunks += Floats(CHUNK * dim)
            if (k == rows.size) rows = rows.copyOf(rows.size * 2)
            rows[k] = item
        }
        chunks[k / CHUNK].put(v, 0, (k % CHUNK) * dim, dim)
    }

    /** The block laid out whole, its rows one after another, and — given the [card] — put there; the chunks let go. */
    fun block(name: String, card: Cublas?): InputBlock {
        val vectors = Matrix(count, dim)
        chunks.forEachIndexed { c, chunk -> vectors.held.copy(chunk, 0, c * CHUNK * dim, minOf(CHUNK, count - c * CHUNK) * dim) }
        chunks.clear()
        val items = rows.copyOf(count)
        return InputBlock(name, vectors, items, card?.let { onCard(it, name, vectors, items) })
    }

    companion object {
        /** The rows of a chunk: memory, not meaning. */
        const val CHUNK = 1024

        /** The rows of a block put on the card; null when it has no room — multiplied in the main memory then, said in the log. */
        fun onCard(card: Cublas, name: String, vectors: Matrix, items: IntArray): CardRows? =
            card.lock.withLock { CardRows.put(card, vectors, items) }
                ?: null.also { log.warn("no room on the card for {} vectors of {} numbers of {}: they are multiplied in the main memory", vectors.rows, vectors.cols, name) }
    }
}
