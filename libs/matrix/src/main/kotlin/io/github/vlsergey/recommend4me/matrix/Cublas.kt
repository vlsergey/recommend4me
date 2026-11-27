package io.github.vlsergey.recommend4me.matrix

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Cublas::class.java)

/**
 * THE MATRIX PRODUCTS ON THE NVIDIA CARD: cuBLAS, called straight through the foreign function interface of the JDK —
 * the CUDA runtime's own libraries ([open] is handed their folder, the very ones ONNX Runtime has loaded), no binding
 * of a library besides. What OpenBLAS is to the processor: `sgemm` of row-major matrices, here kept in the card's
 * memory ([DeviceFloats]) — a fitting puts its gigabyte and a half of the items' features there once and multiplies
 * them there every step.
 *
 * One handle for the process, used by one thread at a time: the fittings run one at a time (the queue of the
 * computations). Opened once; a card the libraries cannot use — no card, the libraries missing, a product that comes
 * out wrong — leaves it closed, and the products are the processor's.
 */
class Cublas
private constructor(
    private val cudaMalloc: MethodHandle,
    private val cudaFree: MethodHandle,
    private val cudaMemcpy: MethodHandle,
    private val cublasSgemm: MethodHandle,
    private val cublasSdot: MethodHandle,
    private val cublasSaxpy: MethodHandle,
    private val cublasSscal: MethodHandle,
    private val cudaMemset: MethodHandle,
    internal val handle: MemorySegment,
    /** The folder of the libraries, and the runtime's own: what the kernels of [kernels] are compiled and loaded by. */
    private val dir: Path,
    private val runtime: SymbolLookup,
    internal val blas: SymbolLookup,
) {
    /**
     * HELD BY WHOEVER WORKS ON THE CARD, for as long as the work: one thread at a time on its one handle and its
     * [scalars]. The computations run one at a time (the queue), and a fitting holds it through; a scoring of every
     * work holds it while it multiplies.
     */
    val lock = ReentrantLock()

    /**
     * The scalars a call hands over and takes back — α, β, a dot's result: one place for them, made once; one thread at
     * a time.
     */
    internal val scalars: MemorySegment = Arena.global().allocate(ValueLayout.JAVA_FLOAT, 4)

    /** Where a call reads α of a product, or the c of an axpy or of a scaling: the first of [scalars]. */
    internal val alphaAt: MemorySegment = scalars.asSlice(0, Float.SIZE_BYTES.toLong())

    /** Where a product reads β: the second of [scalars]. */
    internal val betaAt: MemorySegment = scalars.asSlice(Float.SIZE_BYTES.toLong(), Float.SIZE_BYTES.toLong())

    /** Where a dot writes its result: the third of [scalars]. */
    private val resultAt: MemorySegment = scalars.asSlice(2L * Float.SIZE_BYTES, Float.SIZE_BYTES.toLong())

    private val cudaMallocHost: MethodHandle by lazy {
        Linker.nativeLinker()
            .downcallHandle(
                runtime.find("cudaMallocHost").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
            )
    }
    private val cudaDeviceSynchronize: MethodHandle by lazy {
        Linker.nativeLinker()
            .downcallHandle(
                runtime.find("cudaDeviceSynchronize").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT),
            )
    }
    /** The card's memory free for a new piece of work, of the process: of these products and of every other run. */
    val free = FreeCardMemory(runtime)

    private val cudaFreeHost: MethodHandle by lazy {
        Linker.nativeLinker()
            .downcallHandle(
                runtime.find("cudaFreeHost").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
            )
    }

    /**
     * BYTES OF THE MAIN MEMORY THE CARD COPIES FROM AND INTO AT ITS OWN SPEED: page-locked (`cudaMallocHost`) — a copy
     * of the ordinary memory goes through a buffer of the driver first. Of what a kernel is handed and gives back, laid
     * out here and sent at once. Freed by [close].
     */
    inner class Pinned internal constructor(val bytes: Long) : AutoCloseable {
        val segment: MemorySegment =
            Arena.ofConfined().use { a ->
                val out = a.allocate(ValueLayout.ADDRESS)
                check((cudaMallocHost.invokeExact(out, bytes.coerceAtLeast(1)) as Int) == 0) {
                    "no page-locked memory of $bytes bytes"
                }
                out.get(ValueLayout.ADDRESS, 0).reinterpret(bytes.coerceAtLeast(1))
            }
        private var closed = false

        /** [from] written [at] bytes on. */
        fun put(at: Long, from: IntArray) = MemorySegment.copy(from, 0, segment, ValueLayout.JAVA_INT, at, from.size)

        /** [n] ints [at] bytes on. */
        fun ints(at: Long, n: Int): IntArray =
            IntArray(n).also { if (n > 0) MemorySegment.copy(segment, ValueLayout.JAVA_INT, at, it, 0, n) }

        /** [n] floats [at] bytes on. */
        fun floats(at: Long, n: Int): FloatArray =
            FloatArray(n).also { if (n > 0) MemorySegment.copy(segment, ValueLayout.JAVA_FLOAT, at, it, 0, n) }

        override fun close() {
            if (closed) return
            closed = true
            cudaFreeHost.invokeExact(segment) as Int
        }
    }

    /** Floats in the card's memory: [count] of them, made in a block of work ([withMemory]). Freed by [close]. */
    inner class DeviceFloats internal constructor(val count: Long) : AutoCloseable {
        val address: MemorySegment =
            Arena.ofConfined().use { a ->
                val out = a.allocate(ValueLayout.ADDRESS)
                check((cudaMalloc.invokeExact(out, count * Float.SIZE_BYTES) as Int) == 0) {
                    "the card has no room for $count floats"
                }
                out.get(ValueLayout.ADDRESS, 0)
            }
        private var closed = false

        /** [n] numbers of [from] at [fromAt] written at [at]: from the main memory to the card. */
        fun upload(from: Floats, fromAt: Int, at: Long, n: Int) =
            copy(at(at), from.seg.asSlice(fromAt.toLong() * Float.SIZE_BYTES), n.toLong(), HOST_TO_DEVICE)

        /** [n] numbers at [at] written into [into] at [intoAt]: from the card to the main memory. */
        fun download(into: Floats, intoAt: Int, at: Long, n: Int) =
            copy(into.seg.asSlice(intoAt.toLong() * Float.SIZE_BYTES), at(at), n.toLong(), DEVICE_TO_HOST)

        /** [n] numbers of [from] at [fromAt] written at [at]: on the card, nothing through the main memory. */
        fun copyFrom(from: DeviceFloats, fromAt: Long, at: Long, n: Int) =
            copy(at(at), from.at(fromAt), n.toLong(), DEVICE_TO_DEVICE)

        fun at(k: Long): MemorySegment = MemorySegment.ofAddress(address.address() + k * Float.SIZE_BYTES)

        /** [n] numbers at [at] made nought, on the card. */
        fun zero(at: Long, n: Long) = memset(at(at), n * Float.SIZE_BYTES)

        override fun close() {
            if (closed) return
            closed = true
            cudaFree.invokeExact(address) as Int
        }

        /** [n] floats copied. */
        private fun copy(dst: MemorySegment, src: MemorySegment, n: Long, kind: Int) =
            copyBytes(dst, src, n * Float.SIZE_BYTES, kind)
    }

    /**
     * Bytes in the card's memory, of numbers other than the floats of [DeviceFloats]: the ints a kernel indexes by, the
     * doubles it sums into. Freed by [close].
     */
    inner class DeviceBytes internal constructor(val bytes: Long) : AutoCloseable {
        val address: MemorySegment =
            Arena.ofConfined().use { a ->
                val out = a.allocate(ValueLayout.ADDRESS)
                check((cudaMalloc.invokeExact(out, bytes.coerceAtLeast(1)) as Int) == 0) {
                    "the card has no room for $bytes bytes"
                }
                out.get(ValueLayout.ADDRESS, 0)
            }
        private var closed = false

        /** The ints of [from] written from the start. */
        fun upload(from: IntArray) =
            Arena.ofConfined().use { a ->
                if (from.isNotEmpty())
                    copyBytes(
                        address,
                        a.allocateFrom(ValueLayout.JAVA_INT, *from),
                        from.size.toLong() * Int.SIZE_BYTES,
                        HOST_TO_DEVICE,
                    )
            }

        /** The floats of [from] written from the start. */
        fun upload(from: FloatArray) =
            Arena.ofConfined().use { a ->
                if (from.isNotEmpty())
                    copyBytes(
                        address,
                        a.allocateFrom(ValueLayout.JAVA_FLOAT, *from),
                        from.size.toLong() * Float.SIZE_BYTES,
                        HOST_TO_DEVICE,
                    )
            }

        /** The longs of [from] written from the start. */
        fun upload(from: LongArray) =
            Arena.ofConfined().use { a ->
                if (from.isNotEmpty())
                    copyBytes(
                        address,
                        a.allocateFrom(ValueLayout.JAVA_LONG, *from),
                        from.size.toLong() * Long.SIZE_BYTES,
                        HOST_TO_DEVICE,
                    )
            }

        /** The first [n] doubles. */
        fun doubles(n: Int): DoubleArray =
            Arena.ofConfined().use { a ->
                val into = a.allocate(ValueLayout.JAVA_DOUBLE, n.toLong())
                copyBytes(into, address, n.toLong() * Double.SIZE_BYTES, DEVICE_TO_HOST)
                into.toArray(ValueLayout.JAVA_DOUBLE)
            }

        /**
         * The rows [fromRow] to [fromRow] + [count] of [from] as they are kept, written [at] bytes on: halves two bytes
         * a number, of a [ByteMatrix] its bytes alone (its scales sent apart). Sparse rows are sent as their entries.
         */
        fun upload(from: Rows, fromRow: Int, count: Int, at: Long = 0) {
            if (count <= 0) return
            when (from) {
                is HalfMatrix ->
                    copyBytes(at(at), from.slice(fromRow, count), count.toLong() * from.cols * 2, HOST_TO_DEVICE)
                is ByteMatrix ->
                    copyBytes(at(at), from.slice(fromRow, count), count.toLong() * from.cols, HOST_TO_DEVICE)
                is SparseRows -> error("sparse rows are sent as their entries")
            }
        }

        /** The first [bytes] of [from] written [at] bytes on. */
        fun upload(from: Pinned, bytes: Long, at: Long = 0) {
            if (bytes > 0) copyBytes(at(at), from.segment, bytes, HOST_TO_DEVICE)
        }

        /** [bytes] from [at] bytes on written into [into] from its start. */
        fun download(into: Pinned, bytes: Long, at: Long = 0) {
            if (bytes > 0) copyBytes(into.segment, at(at), bytes, DEVICE_TO_HOST)
        }

        /** Every byte made nought. */
        fun zero() = memset(address, bytes)

        /** The [count] bytes from [at] on made nought. */
        fun zero(at: Long, count: Long) = memset(at(at), count)

        /** The address [offset] bytes on. */
        fun at(offset: Long): MemorySegment = MemorySegment.ofAddress(address.address() + offset)

        override fun close() {
            if (closed) return
            closed = true
            cudaFree.invokeExact(address) as Int
        }
    }

    /**
     * A BLOCK OF WORK ON THE CARD, the one way its memory is had (owner, 2026-11-20): [body] handed the card's memory
     * of its own ([CardMemory]) — as much as the card has free now ([FreeCardMemory]), and no more than [most] —,
     * everything it made there freed at its end. Of less than [least] free, [NoCardRoom]: the caller fits itself — a
     * smaller block, another way, the processor.
     */
    fun <T> withMemory(least: Long, most: Long, body: (CardMemory) -> T): T = memory(least, most).use(body)

    /**
     * The card's memory of a block of work whose end is an object's ([withMemory]): one that holds its rooms while it
     * lives — a model scoring the works one portion after another — and frees them as it is closed.
     */
    fun memory(least: Long, most: Long): CardMemory {
        val now = free.bytes()
        val size = minOf(most, now)
        if (size < least) throw NoCardRoom("$least bytes asked of the card, $now free")
        return CardMemory(this, maxOf(0L, size))
    }

    /** No [bytes] beyond what the card has free now ([FreeCardMemory]): [NoCardRoom], as the card would refuse them. */
    internal fun refuseBeyondFree(bytes: Long) {
        if (bytes <= SMALL) return
        val now = free.bytes()
        if (bytes > now) throw NoCardRoom("$bytes bytes asked of the card, $now free")
    }

    /**
     * Every work handed to the card done: a kernel and a product return before they end, and the time of what follows
     * would be theirs — a measure of where the time goes waits for them here.
     */
    fun synchronize() {
        val status = cudaDeviceSynchronize.invokeExact() as Int
        check(status == 0) { "the card's work failed: CUDA error $status" }
    }

    private fun memset(at: MemorySegment, bytes: Long) {
        val status = cudaMemset.invokeExact(at, 0, bytes) as Int
        check(status == 0) { "a clearing on the card failed: CUDA error $status" }
    }

    /**
     * Kernels of one's own, the CUDA C of [source] compiled for this card ([CudaKernels]): those named [names]. Null
     * when they cannot be — no NVRTC beside the libraries, a source the compiler refuses — said in the log; the
     * caller's work stays on the processor.
     */
    fun kernels(source: String, names: List<String>): CudaKernels? =
        try {
            CudaKernels.compile(dir, runtime, source, names)
        } catch (e: IllegalArgumentException) {
            // NVRTC's library not found
            kernelsNotMade(e)
        } catch (e: IllegalStateException) {
            // A function missing, a call that failed, a source the compiler refused
            kernelsNotMade(e)
        } catch (e: IllegalCallerException) {
            // Native access denied to the module
            kernelsNotMade(e)
        } catch (e: IOException) {
            // The folder of the libraries not read
            kernelsNotMade(e)
        }

    private fun copyBytes(dst: MemorySegment, src: MemorySegment, bytes: Long, kind: Int) {
        val status = cudaMemcpy.invokeExact(dst, src, bytes, kind) as Int
        check(status == 0) { "a copy to or from the card failed: CUDA error $status" }
    }

    /**
     * `c = alpha · op(a) · op(b) + beta · c` of row-major matrices on the card, as [Blas.gemm]: op(a) m × k, op(b) k ×
     * n, c m × n. cuBLAS reads column-major, where the row-major c is cᵀ = op(b)ᵀ op(a)ᵀ: the two sides handed over
     * swapped, each with its own leading dimension.
     */
    fun gemm(
        transA: Boolean,
        transB: Boolean,
        m: Int,
        n: Int,
        k: Int,
        a: DeviceFloats,
        aAt: Long,
        lda: Int,
        b: DeviceFloats,
        bAt: Long,
        ldb: Int,
        c: DeviceFloats,
        cAt: Long,
        ldc: Int,
        beta: Float = 0f,
        alpha: Float = 1f,
    ) {
        if (m == 0 || n == 0) return
        scalars.setAtIndex(ValueLayout.JAVA_FLOAT, 0, alpha)
        scalars.setAtIndex(ValueLayout.JAVA_FLOAT, 1, beta)
        val status =
            cublasSgemm.invokeExact(
                handle,
                if (transB) OP_T else OP_N,
                if (transA) OP_T else OP_N,
                n,
                m,
                k,
                alphaAt,
                b.at(bAt),
                ldb,
                a.at(aAt),
                lda,
                betaAt,
                c.at(cAt),
                ldc,
            ) as Int
        check(status == 0) { "a product on the card failed: cuBLAS status $status" }
    }

    /** The products of brain floating point on the tensor cores ([HalfProducts]), through this handle. */
    val halves: HalfProducts by lazy { HalfProducts(this) }

    /** `Σ a[aAt + q] · b[bAt + q]` for q under [n], on the card; the sum brought back. */
    fun dot(a: DeviceFloats, aAt: Long, b: DeviceFloats, bAt: Long, n: Int): Float {
        val status = cublasSdot.invokeExact(handle, n, a.at(aAt), 1, b.at(bAt), 1, resultAt) as Int
        check(status == 0) { "a dot on the card failed: cuBLAS status $status" }
        return scalars.getAtIndex(ValueLayout.JAVA_FLOAT, 2)
    }

    /** `y[yAt + q] += c · x[xAt + q]` for q under [n], on the card. */
    fun axpy(c: Float, x: DeviceFloats, xAt: Long, y: DeviceFloats, yAt: Long, n: Int) {
        scalars.setAtIndex(ValueLayout.JAVA_FLOAT, 0, c)
        val status = cublasSaxpy.invokeExact(handle, n, alphaAt, x.at(xAt), 1, y.at(yAt), 1) as Int
        check(status == 0) { "an axpy on the card failed: cuBLAS status $status" }
    }

    /** `x[at + q] *= c` for q under [n], on the card. */
    fun scale(c: Float, x: DeviceFloats, at: Long, n: Int) {
        scalars.setAtIndex(ValueLayout.JAVA_FLOAT, 0, c)
        val status = cublasSscal.invokeExact(handle, n, alphaAt, x.at(at), 1) as Int
        check(status == 0) { "a scaling on the card failed: cuBLAS status $status" }
    }

    companion object {
        internal const val OP_N = 0
        internal const val OP_T = 1
        private const val HOST_TO_DEVICE = 1
        private const val DEVICE_TO_HOST = 2
        private const val DEVICE_TO_DEVICE = 3

        private var opened: Cublas? = null
        private var tried = false

        /**
         * cuBLAS of the libraries in [dir] — cudart64_12 and cublas64_12 — opened once for the process and checked by a
         * product of its own against the processor's; null when it cannot be, said once in the log.
         */
        @Synchronized
        fun open(dir: Path): Cublas? {
            if (tried) return opened
            tried = true
            opened =
                try {
                    bind(dir)
                        .takeIf { checked(it) }
                        .also {
                            if (it == null)
                                log.warn(
                                    "cuBLAS in {} computes a product wrong: the products stay on the processor",
                                    dir,
                                )
                        }
                } catch (e: IllegalArgumentException) {
                    // A library not found
                    notOpened(dir, e)
                } catch (e: IllegalStateException) {
                    // A function missing, a call that failed: no card, no room on it
                    notOpened(dir, e)
                } catch (e: IllegalCallerException) {
                    // Native access denied to the module
                    notOpened(dir, e)
                }
            if (opened != null) log.info("matrix products of the fittings go through cuBLAS of {}", dir)
            return opened
        }

        /** The libraries in [dir] loaded, the functions called bound, and cuBLAS's handle made. */
        private fun bind(dir: Path): Cublas {
            val runtime = SymbolLookup.libraryLookup(dir.resolve("cudart64_12.dll"), Arena.global())
            val blas = SymbolLookup.libraryLookup(dir.resolve("cublas64_12.dll"), Arena.global())
            val int = ValueLayout.JAVA_INT
            val address = ValueLayout.ADDRESS
            val handle = createHandle(blas)
            return Cublas(
                cudaMalloc =
                    handleOf(runtime, "cudaMalloc", FunctionDescriptor.of(int, address, ValueLayout.JAVA_LONG)),
                cudaFree = handleOf(runtime, "cudaFree", FunctionDescriptor.of(int, address)),
                cudaMemcpy =
                    handleOf(
                        runtime,
                        "cudaMemcpy",
                        FunctionDescriptor.of(int, address, address, ValueLayout.JAVA_LONG, int),
                    ),
                cublasSgemm =
                    handleOf(
                        blas,
                        "cublasSgemm_v2",
                        FunctionDescriptor.of(
                            int,
                            address,
                            int,
                            int,
                            int,
                            int,
                            int,
                            address,
                            address,
                            int,
                            address,
                            int,
                            address,
                            address,
                            int,
                        ),
                    ),
                cublasSdot =
                    handleOf(
                        blas,
                        "cublasSdot_v2",
                        FunctionDescriptor.of(int, address, int, address, int, address, int, address),
                    ),
                cublasSaxpy =
                    handleOf(
                        blas,
                        "cublasSaxpy_v2",
                        FunctionDescriptor.of(int, address, int, address, address, int, address, int),
                    ),
                cublasSscal =
                    handleOf(blas, "cublasSscal_v2", FunctionDescriptor.of(int, address, int, address, address, int)),
                cudaMemset =
                    handleOf(runtime, "cudaMemset", FunctionDescriptor.of(int, address, int, ValueLayout.JAVA_LONG)),
                handle = handle,
                dir = dir,
                runtime = runtime,
                blas = blas,
            )
        }

        private fun handleOf(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle =
            Linker.nativeLinker()
                .downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("no $name") }, descriptor)

        /** cuBLAS's handle, made by the library [blas]. */
        private fun createHandle(blas: SymbolLookup): MemorySegment {
            val address = ValueLayout.ADDRESS
            val cublasCreate = handleOf(blas, "cublasCreate_v2", FunctionDescriptor.of(ValueLayout.JAVA_INT, address))
            return Arena.ofConfined().use { a ->
                val out = a.allocate(address)
                check((cublasCreate.invokeExact(out) as Int) == 0) { "cuBLAS was not opened" }
                out.get(address, 0)
            }
        }

        /**
         * A product of its own, on [cublas] and by the processor's plain loops: the same to the float's last digits.
         */
        private fun checked(cublas: Cublas): Boolean {
            val m = 3
            val n = 4
            val k = 5
            val a = Floats(m * k).also { f -> for (i in 0 until f.count) f[i] = (i % 7 - 3) / 2f }
            val b = Floats(n * k).also { f -> for (i in 0 until f.count) f[i] = (i % 5 - 2) / 3f }
            val expected = Floats(m * n)
            val got = Floats(m * n)
            // a · bᵀ: one side transposed, as the fittings' products are
            for (i in 0 until m) for (j in 0 until n) {
                var s = 0f
                for (q in 0 until k) s += a[i * k + q] * b[j * k + q]
                expected[i * n + j] = s
            }
            cublas.withMemory(0, CHECK_ROOM) { memory ->
                val da = memory.floats(a.count.toLong())
                val db = memory.floats(b.count.toLong())
                val dc = memory.floats(got.count.toLong())
                da.upload(a, 0, 0, a.count)
                db.upload(b, 0, 0, b.count)
                cublas.gemm(false, true, m, n, k, da, 0, k, db, 0, k, dc, 0, n)
                dc.download(got, 0, 0, got.count)
            }
            return (0 until m * n).all { abs(got[it] - expected[it]) < AGREEMENT }
        }

        /** How near the card's product and the processor's must be: the last digits of a float. */
        private const val AGREEMENT = 1e-4f

        /** The card's memory of the check: three small products. */
        private const val CHECK_ROOM = 1L shl 16
    }
}

/** The warning that the card's own kernels were not made, and why: the caller's work stays on the processor. */
/** An allocation of no more than so many bytes made without asking the card what it has free: within its spare. */
private const val SMALL = 256L shl 20

private fun kernelsNotMade(e: Exception): CudaKernels? {
    log.warn(
        "kernels of their own not made for the card ({}): that work stays on the processor",
        e.message ?: e.javaClass.simpleName,
    )
    return null
}

/** The warning that cuBLAS of [dir] was not opened, and why: the products stay on the processor. */
private fun notOpened(dir: Path, e: RuntimeException): Cublas? {
    log.warn("no cuBLAS in {} ({}): the products stay on the processor", dir, e.message ?: e.javaClass.simpleName)
    return null
}
