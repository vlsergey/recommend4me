package io.github.vlsergey.recommend4me.matrix

import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorOperators
import org.slf4j.LoggerFactory
import java.nio.ByteOrder

private val log = LoggerFactory.getLogger(Simd::class.java)

/**
 * Inner loops written for the wide registers (Vector API), ported from local-ai-chroma-enf-lora's
 * `student/Simd.kt`. Multiplication and addition are fused: the results differ from the plain
 * loops in the last digits, which is accepted for speed.
 *
 * The module is incubating, so the JVM must load it (`--add-modules jdk.incubator.vector`, which
 * the launcher and the tests pass). Started without it, the plain loops run instead.
 */
object Simd {

    val on: Boolean = try {
        val width = Probe.width()
        log.info("wide registers are on: {} floats at a time", width)
        width > 1
    } catch (e: Throwable) {
        log.warn("no wide registers ({}), plain loops are used; start the JVM with --add-modules jdk.incubator.vector", e.javaClass.simpleName)
        false
    }

    private val ORDER = ByteOrder.nativeOrder()
    private const val SUMS = 4

    /**
     * `Σ a[i + k] · b[j + k]` for k under [n]. Four sums at once, added up at the end: a single
     * sum waits for its own last fused multiply-add on every step.
     */
    fun dot(a: Floats, i: Int, b: Floats, j: Int, n: Int): Float {
        var k = 0
        var s = 0f
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val width = sp.length()
            val bytes = Float.SIZE_BYTES.toLong()
            val fourBound = n - n % (SUMS * width)
            var acc0 = FloatVector.zero(sp)
            var acc1 = FloatVector.zero(sp)
            var acc2 = FloatVector.zero(sp)
            var acc3 = FloatVector.zero(sp)
            fun lane(f: Floats, at: Int): FloatVector = FloatVector.fromMemorySegment(sp, f.seg, at * bytes, ORDER)
            while (k < fourBound) {
                acc0 = lane(a, i + k).fma(lane(b, j + k), acc0)
                acc1 = lane(a, i + k + width).fma(lane(b, j + k + width), acc1)
                acc2 = lane(a, i + k + 2 * width).fma(lane(b, j + k + 2 * width), acc2)
                acc3 = lane(a, i + k + 3 * width).fma(lane(b, j + k + 3 * width), acc3)
                k += SUMS * width
            }
            val bound = sp.loopBound(n)
            while (k < bound) {
                acc0 = lane(a, i + k).fma(lane(b, j + k), acc0)
                k += width
            }
            s = acc0.add(acc1).add(acc2.add(acc3)).reduceLanes(VectorOperators.ADD)
        }
        while (k < n) {
            s += a[i + k] * b[j + k]
            k++
        }
        return s
    }
}

/**
 * The question «are the registers there» asked in a class of its own: without the module the
 * answer is a linkage error, which must be thrown inside a call to be caught.
 */
private object Probe {
    fun width(): Int = FloatVector.SPECIES_PREFERRED.length()
}
