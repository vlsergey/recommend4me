package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas

/**
 * THE ROOM A FITTING TAKES ON THE CARD: every array it puts there, freed together with it ([close]). An array the card
 * has no room for throws [NoRoom], the caller giving back what was taken.
 */
internal class CardRoom(private val cublas: Cublas) : AutoCloseable {
    private val made = ArrayList<AutoCloseable>()

    /** The card had no room for an array: what the fitting would put there stays in the main memory. */
    class NoRoom(what: String) : RuntimeException(what)

    /** [count] floats, at least one. */
    fun floats(count: Long): Cublas.DeviceFloats =
        (cublas.allocate(maxOf(1L, count)) ?: throw NoRoom("no room for $count floats")).also { made += it }

    /** [count] bytes, at least one. */
    fun bytes(count: Long): Cublas.DeviceBytes =
        (cublas.allocateBytes(maxOf(1L, count)) ?: throw NoRoom("no room for $count bytes")).also { made += it }

    /** The numbers [of] put there. */
    fun ints(of: IntArray): Cublas.DeviceBytes = bytes(of.size.toLong() * Int.SIZE_BYTES).also { it.upload(of) }

    /** The numbers [of] put there. */
    fun longs(of: LongArray): Cublas.DeviceBytes = bytes(of.size.toLong() * Long.SIZE_BYTES).also { it.upload(of) }

    /** The numbers [of] put there, as bytes a kernel reads. */
    fun floatsOf(of: FloatArray): Cublas.DeviceBytes = bytes(of.size.toLong() * Float.SIZE_BYTES).also { it.upload(of) }

    override fun close() = made.forEach { it.close() }
}
