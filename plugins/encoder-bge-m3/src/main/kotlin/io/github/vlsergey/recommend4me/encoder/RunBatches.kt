package io.github.vlsergey.recommend4me.encoder

/**
 * THE BATCHES THE WINDOWS ARE RUN THROUGH THE GRAPH IN: of [GPU_BATCH] windows at most on the card, [BATCH] on the
 * processor, and of no more room than so many windows of [PAGE] pieces each, the room of a window's attention counted
 * as its length squared — a text read whole runs among few, one of the longest alone. Memory, not meaning.
 */
internal object RunBatches {
    /** Windows per run, on the processor and on the card. */
    const val BATCH = 8
    const val GPU_BATCH = 64

    /** The window the room of a batch is counted in: [GPU_BATCH] windows of so many pieces ran on the card, known. */
    private const val PAGE = 512

    /** The batches of the windows of [order] — sorted by length, each of [lengthOf] pieces — on the card or not. */
    fun of(order: List<Int>, lengthOf: (Int) -> Int, onGpu: Boolean): List<List<Int>> {
        val rows = if (onGpu) GPU_BATCH else BATCH
        val room = rows.toLong() * PAGE * PAGE
        val out = ArrayList<List<Int>>()
        var batch = ArrayList<Int>()
        order.forEach { w ->
            // Sorted by length: the last one of a batch is its longest, what every one of it is padded to
            val len = lengthOf(w).toLong()
            if (batch.isNotEmpty() && (batch.size == rows || (batch.size + 1) * len * len > room)) {
                out += batch
                batch = ArrayList()
            }
            batch += w
        }
        if (batch.isNotEmpty()) out += batch
        return out
    }
}
