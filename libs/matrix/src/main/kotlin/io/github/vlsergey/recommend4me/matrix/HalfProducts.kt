package io.github.vlsergey.recommend4me.matrix

import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * THE PRODUCTS OF BRAIN FLOATING POINT ON THE CARD'S TENSOR CORES (bfloat16: single precision's range, a fraction of 8
 * bits), through the handle of [cublas] and under its lock: `cublasGemmEx`, the inputs two bytes a number, the result
 * summed and written in single precision — where the single precision's products are the card's cores' alone.
 */
class HalfProducts internal constructor(private val cublas: Cublas) {
    private val cublasGemmEx: MethodHandle by lazy {
        val int = ValueLayout.JAVA_INT
        val address = ValueLayout.ADDRESS
        Linker.nativeLinker()
            .downcallHandle(
                cublas.blas.find("cublasGemmEx").orElseThrow(),
                FunctionDescriptor.of(
                    int,
                    *arrayOf(address, int, int, int, int, int, address, address, int, int, address, int, int) +
                        arrayOf(address, address, int, int, int, int),
                ),
            )
    }

    /**
     * [Cublas.gemm] of matrices of brain floating point ([a] and [b]: two bytes a number, their offsets [aAt] and [bAt]
     * in numbers), [c] single precision, summed in single precision — by the card's tensor cores, where the single
     * precision's products are its cores' alone.
     */
    fun gemm(
        transA: Boolean,
        transB: Boolean,
        m: Int,
        n: Int,
        k: Int,
        a: Cublas.DeviceBytes,
        aAt: Long,
        lda: Int,
        b: Cublas.DeviceBytes,
        bAt: Long,
        ldb: Int,
        c: Cublas.DeviceFloats,
        cAt: Long,
        ldc: Int,
        beta: Float = 0f,
        alpha: Float = 1f,
    ) {
        if (m == 0 || n == 0) return
        cublas.scalars.setAtIndex(ValueLayout.JAVA_FLOAT, 0, alpha)
        cublas.scalars.setAtIndex(ValueLayout.JAVA_FLOAT, 1, beta)
        val status =
            cublasGemmEx.invokeWithArguments(
                cublas.handle,
                if (transB) Cublas.OP_T else Cublas.OP_N,
                if (transA) Cublas.OP_T else Cublas.OP_N,
                n,
                m,
                k,
                cublas.alphaAt,
                b.at(bAt * HALF_BYTES),
                BF16,
                ldb,
                a.at(aAt * HALF_BYTES),
                BF16,
                lda,
                cublas.betaAt,
                c.at(cAt),
                FLOAT,
                ldc,
                COMPUTE_FLOAT,
                GEMM_DEFAULT,
            ) as Int
        check(status == 0) { "a product of halves on the card failed: cuBLAS status $status" }
    }

    private companion object {
        // The types and the way of cublasGemmEx: brain floating point in, single precision out and summed, the
        // library's own choice of the algorithm (CUDA_R_16BF, CUDA_R_32F, CUBLAS_COMPUTE_32F, CUBLAS_GEMM_DEFAULT)
        const val BF16 = 14
        const val FLOAT = 0
        const val COMPUTE_FLOAT = 68
        const val GEMM_DEFAULT = -1

        /** The bytes of a number of brain floating point. */
        const val HALF_BYTES = 2L
    }
}
