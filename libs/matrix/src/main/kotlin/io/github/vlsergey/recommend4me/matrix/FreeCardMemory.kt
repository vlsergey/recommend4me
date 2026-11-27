package io.github.vlsergey.recommend4me.matrix

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.util.concurrent.atomic.AtomicLong

/**
 * THE CARD'S MEMORY FREE FOR A NEW PIECE OF WORK, of the process: what the card has free now (`cudaMemGetInfo`), less
 * [SPARE], less the rooms held by the runs of another library that allocates on the card itself ([withRoom]) — ONNX
 * Runtime, whose run takes its memory as it goes: two runs at once, or a run and a block of the products
 * ([CardMemory]), would else count the same free bytes twice, and Windows refuses the card no allocation (WDDM) — it
 * moves the card's memory to the main one, and the work goes through the bus.
 *
 * A room is held whole for as long as its run, though the run's own allocations are taken from the free bytes as it
 * makes them: counted twice while it runs, the free memory is told less than it is, never more.
 */
class FreeCardMemory internal constructor(runtime: SymbolLookup) {
    private val memGetInfo: MethodHandle =
        Linker.nativeLinker()
            .downcallHandle(
                runtime.find("cudaMemGetInfo").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
            )

    /** The bytes of the rooms held now ([withRoom]). */
    private val held = AtomicLong()

    /** The bytes free now for a new piece of work: the card's, less [SPARE] and the rooms held. */
    fun bytes(): Long = onCard() - SPARE - held.get()

    /**
     * A ROOM ON THE CARD FOR A PIECE OF WORK THAT ALLOCATES IT ITSELF — a run of a graph of ONNX Runtime: [body] handed
     * the bytes it may take, as many as are free now ([bytes]) and no more than [most]; held while it lasts. Of less
     * than [least] free, [NoCardRoom]: the caller fits itself — a smaller batch, the processor.
     */
    fun <T> withRoom(least: Long, most: Long, body: (Long) -> T): T {
        val size =
            synchronized(this) {
                val free = bytes()
                val size = minOf(most, free)
                if (size < least) throw NoCardRoom("$least bytes asked of the card, $free free")
                held.addAndGet(size)
                size
            }
        try {
            return body(size)
        } finally {
            held.addAndGet(-size)
        }
    }

    /** The bytes of the card's own memory free now. */
    private fun onCard(): Long =
        Arena.ofConfined().use { a ->
            val free = a.allocate(ValueLayout.JAVA_LONG)
            val total = a.allocate(ValueLayout.JAVA_LONG)
            check((memGetInfo.invokeExact(free, total) as Int) == 0) { "the card's memory not told" }
            free.get(ValueLayout.JAVA_LONG, 0)
        }

    private companion object {
        /** The card's memory left free: cuBLAS's own, the kernels', the screen's. */
        const val SPARE = 512L shl 20
    }
}
