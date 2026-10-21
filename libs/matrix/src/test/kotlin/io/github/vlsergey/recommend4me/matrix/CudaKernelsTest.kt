package io.github.vlsergey.recommend4me.matrix

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Kernels of one's own compiled for the card and run there; skipped without the card's libraries. */
class CudaKernelsTest {

    @Test
    fun `a kernel of one's own is compiled, launched and reads and writes the card's memory`() {
        val dir = Path.of(System.getenv("LOCALAPPDATA") ?: "", "recommend4me", "cuda")
        assumeTrue(Files.exists(dir.resolve("nvrtc64_120_0.dll")), "no CUDA libraries with NVRTC")
        // Loaded before cuBLAS, which needs them, as the application's ONNX Runtime loads them: by full path
        listOf("cudart64_12.dll", "cublasLt64_12.dll").forEach { System.load(dir.resolve(it).toAbsolutePath().toString()) }
        val cublas = Cublas.open(dir)
        assumeTrue(cublas != null, "no card")
        val kernels = cublas!!.kernels(SOURCE, listOf("scaled_sum"))!!
        val n = 1000
        val x = Floats(n).also { f -> for (i in 0 until n) f[i] = i.toFloat() }
        cublas.allocate(n.toLong())!!.use { dx ->
            cublas.allocateBytes(Double.SIZE_BYTES.toLong())!!.use { sum ->
                cublas.allocateBytes(Int.SIZE_BYTES.toLong() * n)!!.use { index ->
                    dx.upload(x, 0, 0, n)
                    sum.zero()
                    index.upload(IntArray(n) { n - 1 - it })
                    kernels.launch("scaled_sum", n.toLong(), dx, index, n, 2f, sum)
                    val back = Floats(n)
                    dx.download(back, 0, 0, n)
                    // Every number doubled in place, read through the reversed index; their sum in double
                    assertEquals((0 until n).map { 2f * it }, (0 until n).map { back[it] })
                    assertEquals(2.0 * n * (n - 1) / 2, sum.doubles(1)[0])
                }
            }
        }
    }

    private companion object {
        val SOURCE = """
            extern "C" __global__ void scaled_sum(float* x, const int* index, int n, float c, double* sum) {
                int k = blockIdx.x * blockDim.x + threadIdx.x;
                if (k >= n) return;
                int at = index[n - 1 - k];
                x[at] = c * x[at];
                atomicAdd(sum, (double) x[at]);
            }
        """.trimIndent()
    }
}
