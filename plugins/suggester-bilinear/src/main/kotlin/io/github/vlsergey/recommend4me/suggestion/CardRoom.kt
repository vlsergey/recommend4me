package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.CardMemory
import io.github.vlsergey.recommend4me.matrix.Cublas

/**
 * THE ROOM A FITTING TAKES ON THE CARD, a block of the card's memory ([CardMemory], owner, 2026-11-20: every operation
 * with the card's memory inside a block of a least and a most, freed at its exit): every array it puts there, freed
 * together with it ([close]). An array the card has no room for throws
 * [NoCardRoom][io.github.vlsergey.recommend4me.matrix.NoCardRoom], the caller giving back what was taken.
 */
internal class CardRoom(cublas: Cublas) : AutoCloseable {
    private val memory = cublas.memory(0, Long.MAX_VALUE)

    /** [count] floats, at least one. */
    fun floats(count: Long): Cublas.DeviceFloats = memory.floats(count)

    /** [count] bytes, at least one. */
    fun bytes(count: Long): Cublas.DeviceBytes = memory.bytes(count)

    /** The numbers [of] put there. */
    fun ints(of: IntArray): Cublas.DeviceBytes = bytes(of.size.toLong() * Int.SIZE_BYTES).also { it.upload(of) }

    /** The numbers [of] put there. */
    fun longs(of: LongArray): Cublas.DeviceBytes = bytes(of.size.toLong() * Long.SIZE_BYTES).also { it.upload(of) }

    /** The numbers [of] put there, as bytes a kernel reads. */
    fun floatsOf(of: FloatArray): Cublas.DeviceBytes = bytes(of.size.toLong() * Float.SIZE_BYTES).also { it.upload(of) }

    override fun close() = memory.close()
}
