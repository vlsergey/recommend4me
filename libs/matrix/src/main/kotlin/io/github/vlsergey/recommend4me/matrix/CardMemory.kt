package io.github.vlsergey.recommend4me.matrix

/**
 * THE CARD'S MEMORY OF ONE BLOCK OF WORK (owner, 2026-11-20: "every operation with the card's memory inside a block
 * with(minRequiredMemory, maxUsableMemory) { actualMemory -> } that frees the memory or the counter at its exit and
 * hands in how much memory there is now"): [size] bytes the block may take, what the card had free as it began — no
 * more than it asked for ([Cublas.withMemory], [Cublas.memory]). Every byte on the card is made here ([floats],
 * [bytes]): none beyond the block's size, none beyond what the card has free at the time ([NoCardRoom]) — Windows
 * refuses the card no allocation (WDDM), it moves the card's memory to the main one, and every kernel reads it through
 * the bus: the graph of the duplicates ran 70 times slower so (2026-11-20). Everything made here is freed as the block
 * ends ([close]).
 */
class CardMemory internal constructor(private val card: Cublas, val size: Long) : AutoCloseable {
    private val made = LinkedHashMap<AutoCloseable, Long>()

    /** The bytes of the card made, and not freed yet. */
    var used = 0L
        private set

    /** The bytes of the block not taken yet. */
    val left: Long
        get() = size - used

    /** Room for [count] floats. */
    fun floats(count: Long): Cublas.DeviceFloats =
        taken(maxOf(1L, count) * Float.SIZE_BYTES) { card.DeviceFloats(maxOf(1L, count)) }

    /** Room for [count] bytes. */
    fun bytes(count: Long): Cublas.DeviceBytes = taken(maxOf(1L, count)) { card.DeviceBytes(maxOf(1L, count)) }

    /**
     * Page-locked main memory of [bytes], what is brought back from the card copied into: not the card's, freed with
     * the block all the same.
     */
    fun pinned(bytes: Long): Cublas.Pinned =
        (runCatching { card.Pinned(maxOf(1L, bytes)) }.getOrNull() ?: throw NoCardRoom("no page-locked memory")).also {
            made[it] = 0L
        }

    /** [what], made here, freed now: its bytes given back to the block. */
    fun free(what: AutoCloseable) {
        made.remove(what)?.let { used -= it }
        what.close()
    }

    private fun <T : AutoCloseable> taken(bytes: Long, make: () -> T): T {
        if (bytes > left) throw NoCardRoom("$bytes bytes on the card, of $left left of the block's $size")
        card.refuseBeyondFree(bytes)
        val made = runCatching(make).getOrNull() ?: throw NoCardRoom("the card refused $bytes bytes")
        this.made[made] = bytes
        used += bytes
        return made
    }

    /** Everything made here freed. */
    override fun close() {
        made.keys.reversed().forEach { it.close() }
        made.clear()
        used = 0L
    }
}

/** No room on the card for what a block of work asked for, or would make beyond its size ([CardMemory]). */
class NoCardRoom(message: String) : RuntimeException(message)
