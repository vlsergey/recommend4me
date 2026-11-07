package io.github.vlsergey.recommend4me.matrix

import java.nio.ByteOrder
import jdk.incubator.vector.DoubleVector
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorOperators
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Simd::class.java)

/**
 * Inner loops written for the wide registers (Vector API), ported from local-ai-chroma-enf-lora's `student/Simd.kt`.
 * Multiplication and addition are fused: the results differ from the plain loops in the last digits, which is accepted
 * for speed.
 *
 * The module is incubating, so the JVM must load it (`--add-modules jdk.incubator.vector`, which the launcher and the
 * tests pass). Started without it, the plain loops run instead.
 */
object Simd {

    val on: Boolean =
        try {
            val width = Probe.width()
            log.info("wide registers are on: {} floats at a time", width)
            width > 1
        } catch (e: LinkageError) {
            log.warn(
                "no wide registers ({}), plain loops are used; start the JVM with --add-modules jdk.incubator.vector",
                e.javaClass.simpleName,
            )
            false
        }

    private val ORDER = ByteOrder.nativeOrder()
    private const val SUMS = 4

    /**
     * `Σ a[i + k] · b[j + k]` for k under [n]. Four sums at once, added up at the end: a single sum waits for its own
     * last fused multiply-add on every step.
     */
    fun dot(a: Floats, i: Int, b: Floats, j: Int, n: Int): Float {
        var k = 0
        var s = 0f
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val width = sp.length()
            // Where the second, the third and the fourth sum read, past the first
            val w2 = 2 * width
            val w3 = w2 + width
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
                acc2 = lane(a, i + k + w2).fma(lane(b, j + k + w2), acc2)
                acc3 = lane(a, i + k + w3).fma(lane(b, j + k + w3), acc3)
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

    /** `Σ a[k] · b[k]` in single precision, four sums at once in the wide registers. */
    fun dot(a: FloatArray, b: FloatArray): Float {
        var k = 0
        var s = 0f
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val width = sp.length()
            // Where the second, the third and the fourth sum read, past the first
            val w2 = 2 * width
            val w3 = w2 + width
            val fourBound = a.size - a.size % (SUMS * width)
            var acc0 = FloatVector.zero(sp)
            var acc1 = FloatVector.zero(sp)
            var acc2 = FloatVector.zero(sp)
            var acc3 = FloatVector.zero(sp)
            while (k < fourBound) {
                acc0 = FloatVector.fromArray(sp, a, k).fma(FloatVector.fromArray(sp, b, k), acc0)
                acc1 = FloatVector.fromArray(sp, a, k + width).fma(FloatVector.fromArray(sp, b, k + width), acc1)
                acc2 = FloatVector.fromArray(sp, a, k + w2).fma(FloatVector.fromArray(sp, b, k + w2), acc2)
                acc3 = FloatVector.fromArray(sp, a, k + w3).fma(FloatVector.fromArray(sp, b, k + w3), acc3)
                k += SUMS * width
            }
            s = acc0.add(acc1).add(acc2.add(acc3)).reduceLanes(VectorOperators.ADD)
        }
        while (k < a.size) {
            s += a[k] * b[k]
            k++
        }
        return s
    }

    /** `y[k] += c · x[k]` in single precision, a lane's worth at once. */
    fun axpy(c: Float, x: FloatArray, y: FloatArray) {
        var k = 0
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val bound = sp.loopBound(x.size)
            val cv = FloatVector.broadcast(sp, c)
            while (k < bound) {
                FloatVector.fromArray(sp, x, k).fma(cv, FloatVector.fromArray(sp, y, k)).intoArray(y, k)
                k += sp.length()
            }
        }
        while (k < x.size) {
            y[k] += c * x[k]
            k++
        }
    }

    /**
     * `out[k] = base[k] + Σ_q c[cAt + q] · x[q · stride + k]` for q under [count], k under `out.size`: a row of a
     * product of [count] coordinates by a side of them laid out row after row ([stride] apart), made in one pass —
     * every lane's worth of [out] summed in the wide registers over all the rows of [x] and written once, not [count]
     * passes of [axpy] over it, nor a product written whole and copied after.
     */
    fun combine(c: FloatArray, cAt: Int, count: Int, x: FloatArray, stride: Int, base: FloatArray, out: FloatArray) {
        val n = out.size
        var k = 0
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val bound = sp.loopBound(n)
            while (k < bound) {
                var acc = FloatVector.fromArray(sp, base, k)
                for (q in 0 until count) acc =
                    FloatVector.fromArray(sp, x, q * stride + k).fma(FloatVector.broadcast(sp, c[cAt + q]), acc)
                acc.intoArray(out, k)
                k += sp.length()
            }
        }
        while (k < n) {
            var s = base[k]
            for (q in 0 until count) s += c[cAt + q] * x[q * stride + k]
            out[k] = s
            k++
        }
    }

    /** `y[yAt + k] += c · x[xAt + k]` for k under [n], where the numbers lie: a lane's worth at once. */
    fun axpy(c: Float, x: Floats, xAt: Int, y: Floats, yAt: Int, n: Int) {
        var k = 0
        val bytes = Float.SIZE_BYTES.toLong()
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val bound = sp.loopBound(n)
            val cv = FloatVector.broadcast(sp, c)
            while (k < bound) {
                FloatVector.fromMemorySegment(sp, x.seg, (xAt + k) * bytes, ORDER)
                    .fma(cv, FloatVector.fromMemorySegment(sp, y.seg, (yAt + k) * bytes, ORDER))
                    .intoMemorySegment(y.seg, (yAt + k) * bytes, ORDER)
                k += sp.length()
            }
        }
        while (k < n) {
            y[yAt + k] = y[yAt + k] + c * x[xAt + k]
            k++
        }
    }

    /** `Σ f[at + k]` for k under [n], where the numbers lie. */
    fun sum(f: Floats, at: Int, n: Int): Float {
        var k = 0
        var s = 0f
        val bytes = Float.SIZE_BYTES.toLong()
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val bound = sp.loopBound(n)
            var acc = FloatVector.zero(sp)
            while (k < bound) {
                acc = acc.add(FloatVector.fromMemorySegment(sp, f.seg, (at + k) * bytes, ORDER))
                k += sp.length()
            }
            s = acc.reduceLanes(VectorOperators.ADD)
        }
        while (k < n) {
            s += f[at + k]
            k++
        }
        return s
    }

    /** `f[at + k] += c` for k under [n], where the numbers lie. */
    fun add(c: Float, f: Floats, at: Int, n: Int) {
        var k = 0
        val bytes = Float.SIZE_BYTES.toLong()
        if (on) {
            val sp = FloatVector.SPECIES_PREFERRED
            val bound = sp.loopBound(n)
            val cv = FloatVector.broadcast(sp, c)
            while (k < bound) {
                FloatVector.fromMemorySegment(sp, f.seg, (at + k) * bytes, ORDER)
                    .add(cv)
                    .intoMemorySegment(f.seg, (at + k) * bytes, ORDER)
                k += sp.length()
            }
        }
        while (k < n) {
            f[at + k] = f[at + k] + c
            k++
        }
    }

    /** `Σ a[k] · b[k]` in double precision, four sums at once in the wide registers. */
    fun dot(a: DoubleArray, b: DoubleArray): Double {
        var k = 0
        var s = 0.0
        if (on) {
            val sp = DoubleVector.SPECIES_PREFERRED
            val width = sp.length()
            // Where the second, the third and the fourth sum read, past the first
            val w2 = 2 * width
            val w3 = w2 + width
            val fourBound = a.size - a.size % (SUMS * width)
            var acc0 = DoubleVector.zero(sp)
            var acc1 = DoubleVector.zero(sp)
            var acc2 = DoubleVector.zero(sp)
            var acc3 = DoubleVector.zero(sp)
            while (k < fourBound) {
                acc0 = DoubleVector.fromArray(sp, a, k).fma(DoubleVector.fromArray(sp, b, k), acc0)
                acc1 = DoubleVector.fromArray(sp, a, k + width).fma(DoubleVector.fromArray(sp, b, k + width), acc1)
                acc2 = DoubleVector.fromArray(sp, a, k + w2).fma(DoubleVector.fromArray(sp, b, k + w2), acc2)
                acc3 = DoubleVector.fromArray(sp, a, k + w3).fma(DoubleVector.fromArray(sp, b, k + w3), acc3)
                k += SUMS * width
            }
            s = acc0.add(acc1).add(acc2.add(acc3)).reduceLanes(VectorOperators.ADD)
        }
        while (k < a.size) {
            s += a[k] * b[k]
            k++
        }
        return s
    }

    /** `y[k] += c · x[k]` in double precision, a lane's worth at once. */
    fun axpy(c: Double, x: DoubleArray, y: DoubleArray) {
        var k = 0
        if (on) {
            val sp = DoubleVector.SPECIES_PREFERRED
            val bound = sp.loopBound(x.size)
            val cv = DoubleVector.broadcast(sp, c)
            while (k < bound) {
                DoubleVector.fromArray(sp, x, k).fma(cv, DoubleVector.fromArray(sp, y, k)).intoArray(y, k)
                k += sp.length()
            }
        }
        while (k < x.size) {
            y[k] += c * x[k]
            k++
        }
    }
}

/**
 * The question «are the registers there» asked in a class of its own: without the module the answer is a linkage error,
 * which must be thrown inside a call to be caught.
 */
private object Probe {
    fun width(): Int = FloatVector.SPECIES_PREFERRED.length()
}
