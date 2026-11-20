package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.ByteMatrix
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import io.github.vlsergey.recommend4me.matrix.Rows
import io.github.vlsergey.recommend4me.matrix.SparseRows

/**
 * ROWS OF ANY KIND ([Rows]) MADE WHOLE HALVES ON THE CARD, as the products and the kernels of the searches read them:
 * half precision copied as it is; of sparse rows their entries sent and spread there, of rows of a byte a number their
 * bytes and scales sent and multiplied there (`rows.cu`) — the main memory keeps them small, the card whole (owner,
 * 2026-11-20: the search of the duplicates ran out of the main memory).
 */
internal object RowsOnCard {
    /** The kernels of `rows.cu`. */
    val NAMES = listOf("sparse_halves", "byte_halves")

    /** The source of a search's kernels, [name] beside this class, with those of `rows.cu` after it. */
    fun source(name: String): String =
        RowsOnCard::class.java.getResource(name)!!.readText() +
            RowsOnCard::class.java.getResource("rows.cu")!!.readText()

    /**
     * Every row of [rows] written into [into] as whole halves, [at] bytes on: what is sent to the card in the meantime
     * freed once written.
     */
    fun write(card: Cublas, kernels: CudaKernels, rows: Rows, into: Cublas.DeviceBytes, at: Long) {
        val n = rows.rows
        if (n == 0 || rows.cols == 0) return
        when (rows) {
            is HalfMatrix -> into.upload(rows, 0, n, at)
            is SparseRows ->
                CardRoom(card).use { sent ->
                    val e = rows.entries(0, n)
                    into.zero(at, n.toLong() * rows.cols * HALF_BYTES)
                    kernels.launch(
                        "sparse_halves",
                        n.toLong(),
                        n,
                        rows.cols,
                        sent.ints(e.at),
                        sent.ints(e.packed),
                        into.at(at),
                    )
                    card.synchronize()
                }
            is ByteMatrix ->
                CardRoom(card).use { sent ->
                    val count = n.toLong() * rows.cols
                    kernels.launch(
                        "byte_halves",
                        count,
                        count,
                        rows.cols,
                        sent.bytes(count).also { it.upload(rows, 0, n) },
                        sent.floatsOf(rows.scales(0, n)),
                        into.at(at),
                    )
                    card.synchronize()
                }
        }
    }
}
