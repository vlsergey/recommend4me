package io.github.vlsergey.recommend4me.onnx

/**
 * WHAT A RUN OF A GRAPH TAKES OF THE CARD BEYOND ITS WEIGHTS — what it computes through and what it gives out —, of a
 * batch of rows padded to one length: [fixed] bytes once, [perPiece] for every piece of every row, [perPair] for every
 * pair of pieces of a row (the attention of a transformer, its length squared). Told by the plugin of the graph, as
 * measured of it: the least memory a run of each of several shapes goes through, the runtime given no more
 * (`gpu_mem_limit`, the arena growing by what is asked); the bytes told are no fewer than any measured.
 */
class RunMemory(private val fixed: Long, private val perPiece: Long, private val perPair: Long) {
    /** The bytes a run of [rows] rows of [length] pieces each takes. */
    fun of(rows: Int, length: Int): Long = fixed + rows.toLong() * length * (perPiece + perPair * length)

    /** A run that gives out [bytes] more for every piece: another output of the same graph. */
    fun withOutput(bytes: Long): RunMemory = RunMemory(fixed, perPiece + bytes, perPair)
}

/**
 * HOW THE ROWS OF A GRAPH ARE RUN IN BATCHES — sorted by length, a batch padded to its longest —: on the card at most
 * [cardRows] a run and as many as the memory it has free when the batch begins lets ([RunMemory]); on the processor at
 * most [cpuRows], of no more memory than so many rows of [page] pieces. A row too long for either runs alone.
 */
class Batching(val cardRows: Int, val cpuRows: Int, val page: Int, val memory: RunMemory) {
    /** The memory a run on the processor may take. */
    internal val cpuRoom: Long
        get() = memory.of(cpuRows, page)

    /**
     * How many rows of [order], from [from] on — sorted by length, each of [lengthOf] pieces —, the next batch takes:
     * at most [rows], their run within [room] bytes, one at least.
     */
    internal fun next(order: List<Int>, from: Int, lengthOf: (Int) -> Int, rows: Int, room: Long): Int {
        var n = 1
        // Sorted by length: the last one of a batch is its longest, what every one of it is padded to
        while (from + n < order.size && n < rows && memory.of(n + 1, lengthOf(order[from + n])) <= room) n++
        return n
    }
}
