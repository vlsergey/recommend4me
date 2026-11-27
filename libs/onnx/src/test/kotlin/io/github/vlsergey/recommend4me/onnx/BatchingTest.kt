package io.github.vlsergey.recommend4me.onnx

import kotlin.test.Test
import kotlin.test.assertEquals

/** A batch as large as its rows and the memory of its run let, one row at least. */
class BatchingTest {
    private val memory = RunMemory(fixed = 100, perPiece = 10, perPair = 1)
    private val batching = Batching(cardRows = 4, cpuRows = 2, page = 10, memory = memory)

    @Test
    fun `a run's memory is what it takes once, of every piece and of every pair of pieces of a row`() {
        assertEquals(100L + 3 * 20 * (10 + 20), memory.of(3, 20))
        assertEquals(100L + 3 * 20 * (10 + 4 + 20), memory.withOutput(4).of(3, 20))
    }

    @Test
    fun `a batch takes as many rows as the room lets, padded to its longest`() {
        val lengths = intArrayOf(5, 5, 10, 10, 10, 40)
        val order = lengths.indices.toList()
        val next = { from: Int, rows: Int, room: Long -> batching.next(order, from, { lengths[it] }, rows, room) }
        // Every row fits: the most rows a batch may have
        assertEquals(4, next(0, 4, Long.MAX_VALUE))
        // Two rows of 5 take 100 + 2·5·15 = 250; a third of 10 pads them all to 10: 100 + 3·10·20 = 700
        assertEquals(2, next(0, 4, 699))
        assertEquals(3, next(0, 4, 700))
        // The last row is alone however little the room: it runs, the caller has seen it fits
        assertEquals(1, next(5, 4, 0))
        assertEquals(1, next(4, 4, memory.of(2, 40) - 1))
        // The processor's room: its rows of its page
        assertEquals(memory.of(2, 10), batching.cpuRoom)
    }
}
