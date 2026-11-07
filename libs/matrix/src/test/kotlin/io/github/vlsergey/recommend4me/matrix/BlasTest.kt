package io.github.vlsergey.recommend4me.matrix

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlasTest {

    @Test
    fun `the library is loaded with one thread, as its own C runtime reads the environment`() {
        assertTrue(Blas.on, "OpenBLAS must load")
        val windows = System.getProperty("os.name").startsWith("Windows")
        val read =
            java.lang.foreign.Arena.ofConfined().use { arena ->
                val linker = java.lang.foreign.Linker.nativeLinker()
                val getenv =
                    (if (windows) java.lang.foreign.SymbolLookup.libraryLookup("msvcrt", arena)
                        else linker.defaultLookup())
                        .find("getenv")
                        .orElseThrow()
                val at =
                    linker
                        .downcallHandle(
                            getenv,
                            java.lang.foreign.FunctionDescriptor.of(
                                java.lang.foreign.ValueLayout.ADDRESS,
                                java.lang.foreign.ValueLayout.ADDRESS,
                            ),
                        )
                        .invokeWithArguments(arena.allocateFrom("OPENBLAS_NUM_THREADS"))
                        as java.lang.foreign.MemorySegment
                if (at.address() == 0L) null else at.reinterpret(16).getString(0)
            }
        assertEquals(System.getenv("OPENBLAS_NUM_THREADS") ?: "1", read)
    }

    private val random = Random(1)

    private fun floats(n: Int) = Floats.of(FloatArray(n) { random.nextFloat() - 0.5f })

    @Test
    fun `products agree with plain loops`() {
        assertTrue(Simd.on, "the Vector API module must be loaded in tests")
        assertTrue(Blas.on, "OpenBLAS must load")
        val m = 150
        val k = 70
        val n = 13
        val a = floats(m * k)
        val b = floats(k * n)
        val out = Floats(m * n)
        Blas.product(a, k, m, k, b, n, out)
        for (i in 0 until m) for (j in 0 until n) {
            var s = 0.0
            for (q in 0 until k) s += a[i * k + q] * b[q * n + j]
            assertEquals(s, out[i * n + j].toDouble(), 1e-4)
        }

        val deltas = floats(m * n)
        val grad = Floats(k * n)
        Blas.transposedProduct(a, k, m, k, deltas, n, grad)
        for (q in 0 until k) for (j in 0 until n) {
            var s = 0.0
            for (i in 0 until m) s += a[i * k + q] * deltas[i * n + j]
            assertEquals(s, grad[q * n + j].toDouble(), 1e-4)
        }

        val x = floats(k)
        val y = Floats(m)
        Blas.times(a, k, m, k, x, y)
        for (i in 0 until m) assertEquals(Simd.dot(a, i * k, x, 0, k).toDouble(), y[i].toDouble(), 1e-4)

        val r = floats(m)
        val g = Floats(k)
        Blas.transposedTimes(a, k, m, k, r, g)
        for (q in 0 until k) {
            var s = 0.0
            for (i in 0 until m) s += a[i * k + q] * r[i]
            assertEquals(s, g[q].toDouble(), 1e-4)
        }
    }
}
