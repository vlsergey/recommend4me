package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.ByteMatrix
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Rows
import io.github.vlsergey.recommend4me.matrix.SparseRows

/**
 * SPARSE ROWS OF EVERY WORK OF A SEARCH ON THE CARD, as the kernels of the graph read them (`graph.cu`): of the work u
 * its entries [at] (u) to [at] (u + 1) of [entries], each its column in the lower two bytes and its half in the higher,
 * by their columns ascending — the sites' rows one after another.
 */
internal class SparseOnCard(val at: Cublas.DeviceBytes, val entries: Cublas.DeviceBytes) {
    companion object {
        /** The rows of the sites [rows], one site after another, of any kind made sparse ([sparse]). */
        fun of(rows: List<Rows>, room: CardRoom): SparseOnCard {
            val own = rows.map { r -> sparse(r).entries(0, r.rows) }
            val at = IntArray(rows.sumOf { it.rows } + 1)
            var row = 0
            var entry = 0
            own.forEach { e ->
                for (k in 1 until e.at.size) at[row + k] = entry + e.at[k]
                row += e.at.size - 1
                entry += e.packed.size
            }
            val entries = IntArray(entry)
            var from = 0
            own.forEach { e ->
                e.packed.copyInto(entries, from)
                from += e.packed.size
            }
            return SparseOnCard(room.ints(at), room.ints(entries))
        }

        /** The [rows] sparse: as they are, or of their numbers not nought. */
        fun sparse(rows: Rows): SparseRows =
            rows as? SparseRows
                ?: SparseRows.of(rows.rows, rows.cols) { i ->
                    FloatArray(rows.cols).also { rows.take(i, it) }.takeIf { r -> r.any { it != 0f } }
                }
    }
}

/**
 * ROWS OF A BYTE A NUMBER OF EVERY WORK OF A SEARCH ON THE CARD ([ByteMatrix]), as the kernels of the graph read them:
 * [cols] bytes a work ([bytes], the sites' one after another — noughts of a site of another number of columns) and its
 * scale ([scales]).
 */
internal class BytesOnCard(val bytes: Cublas.DeviceBytes, val scales: Cublas.DeviceBytes) {
    companion object {
        /** The rows [of] every site of [all], of [cols] numbers: those of the sites of another number none. */
        fun of(all: AllWorks, of: (SiteWorks) -> Rows, cols: Int, room: CardRoom): BytesOnCard {
            val width = maxOf(1, cols)
            val bytes = room.bytes(all.count.toLong() * width).also { it.zero() }
            val scales = FloatArray(all.count)
            all.sites.forEachIndexed { a, s ->
                val rows = of(s)
                if (cols == 0 || rows.cols != cols) return@forEachIndexed
                val own = rows as? ByteMatrix ?: quantized(rows)
                bytes.upload(own, 0, s.count, all.start[a].toLong() * width)
                own.scales(0, s.count).copyInto(scales, all.start[a])
            }
            return BytesOnCard(bytes, room.floatsOf(scales))
        }

        /** The [rows] a byte a number. */
        private fun quantized(rows: Rows): ByteMatrix =
            ByteMatrix(rows.rows, rows.cols).also { m ->
                val row = FloatArray(rows.cols)
                for (i in 0 until rows.rows) {
                    rows.take(i, row)
                    m.put(i, row)
                }
            }
    }
}
