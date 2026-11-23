package io.github.vlsergey.recommend4me.matrix

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(CudaKernels::class.java)

/**
 * KERNELS OF ONE'S OWN ON THE CARD, beside the products of [Cublas]: CUDA C compiled at the start by NVRTC
 * (`nvrtc64_120_0`, beside the runtime's libraries) for the card's own architecture, loaded by the runtime
 * (`cudaLibraryLoadData`) and launched on its default stream — after the products asked for before them, before those
 * asked for after. Called through the foreign function interface of the JDK, as cuBLAS is. Made by [Cublas.kernels];
 * one thread at a time, as the products. The source is given `BLOCKS_A_MULTIPROCESSOR`, the [blocks] of the card: a
 * kernel bounded so (`__launch_bounds__(BLOCK, BLOCKS_A_MULTIPROCESSOR)`) is compiled to fill a multiprocessor, of
 * whatever card.
 */
class CudaKernels
private constructor(
    private val runtime: Nvrtc,
    private val functions: Map<String, MemorySegment>,
    /**
     * The blocks of [BLOCK] a multiprocessor of the card holds at most: of its threads, no more than its blocks
     * (2026-11-23: the descent of the graph of the duplicates ran on 2 of the 6 of an RTX 5080, of 118 registers a
     * thread the compiler chose itself, 21 s; bounded to fill it, 10.6 s).
     */
    val blocks: Int,
) {
    /**
     * The kernel [name] over [threads] threads, [BLOCK] a block, of [args] in the order of its parameters: a
     * [Cublas.DeviceFloats] or [Cublas.DeviceBytes] as the address of its first number, a [MemorySegment] as the
     * address it is, an [Int], a [Long], a [Float] or a [Double] as itself.
     */
    fun launch(name: String, threads: Long, vararg args: Any) {
        if (threads <= 0) return
        val function = functions[name] ?: error("no kernel $name")
        val blocks = (threads + BLOCK - 1) / BLOCK
        require(blocks <= Int.MAX_VALUE) { "$threads threads are too many for one launch" }
        Arena.ofConfined().use { a ->
            val pointers = a.allocate(ValueLayout.ADDRESS, args.size.toLong().coerceAtLeast(1))
            args.forEachIndexed { k, arg ->
                val value =
                    when (arg) {
                        is Cublas.DeviceFloats -> a.allocateFrom(ValueLayout.ADDRESS, arg.address)
                        is Cublas.DeviceBytes -> a.allocateFrom(ValueLayout.ADDRESS, arg.address)
                        is MemorySegment -> a.allocateFrom(ValueLayout.ADDRESS, arg)
                        is Int -> a.allocateFrom(ValueLayout.JAVA_INT, arg)
                        is Long -> a.allocateFrom(ValueLayout.JAVA_LONG, arg)
                        is Float -> a.allocateFrom(ValueLayout.JAVA_FLOAT, arg)
                        is Double -> a.allocateFrom(ValueLayout.JAVA_DOUBLE, arg)
                        else -> error("an argument of a kernel cannot be ${arg.javaClass.simpleName}")
                    }
                pointers.setAtIndex(ValueLayout.ADDRESS, k.toLong(), value)
            }
            val grid =
                a.allocate(DIM3).also {
                    it.set(ValueLayout.JAVA_INT, 0, blocks.toInt())
                    it.set(ValueLayout.JAVA_INT, 4, 1)
                    it.set(ValueLayout.JAVA_INT, 8, 1)
                }
            val block =
                a.allocate(DIM3).also {
                    it.set(ValueLayout.JAVA_INT, 0, BLOCK)
                    it.set(ValueLayout.JAVA_INT, 4, 1)
                    it.set(ValueLayout.JAVA_INT, 8, 1)
                }
            val status = runtime.launch.invokeExact(function, grid, block, pointers, 0L, MemorySegment.NULL) as Int
            check(status == 0) { "the kernel $name was not launched: CUDA error $status" }
        }
    }

    /**
     * What the card made of the kernel [name] as it was compiled: the registers of a thread, the bytes of a thread kept
     * beside them in the card's memory (arrays a thread indexes, and the registers spilled), the blocks of [BLOCK] a
     * multiprocessor runs at once — what a kernel's bounds (`__launch_bounds__`) are tested by.
     */
    fun fit(name: String): Fit {
        val function = functions[name] ?: error("no kernel $name")
        return Arena.ofConfined().use { a ->
            val attributes = a.allocate(FUNCTION_ATTRIBUTES)
            val read = runtime.attributes.invokeExact(attributes, function) as Int
            check(read == 0) { "no attributes of the kernel $name: CUDA error $read" }
            val blocks = a.allocate(ValueLayout.JAVA_INT)
            val counted = runtime.occupancy.invokeExact(blocks, function, BLOCK, 0L) as Int
            check(counted == 0) { "no occupancy of the kernel $name: CUDA error $counted" }
            Fit(
                attributes.get(ValueLayout.JAVA_INT, REGISTERS_AT),
                attributes.get(ValueLayout.JAVA_LONG, LOCAL_BYTES_AT),
                blocks.get(ValueLayout.JAVA_INT, 0),
            )
        }
    }

    /** What the card made of a kernel ([fit]). */
    class Fit(val registers: Int, val localBytes: Long, val blocks: Int) {
        override fun toString() = "$registers registers, $localBytes local bytes, $blocks blocks a multiprocessor"
    }

    companion object {
        /** cudaFuncAttributes: room for all of it; its local bytes (a size_t) and registers (an int) where they are. */
        private const val FUNCTION_ATTRIBUTES = 256L
        private const val LOCAL_BYTES_AT = 16L
        private const val REGISTERS_AT = 28L

        /** Threads of a block: a kernel's shared sums are made for as many. */
        const val BLOCK = 256

        /** CUDA's dim3: three unsigned ints, handed over by value. */
        private val DIM3 = MemoryLayout.structLayout(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)

        /** The device attributes of the compute capability, and of the threads and the blocks of a multiprocessor. */
        private const val MAJOR = 75
        private const val MINOR = 76
        private const val THREADS_A_MULTIPROCESSOR = 39
        private const val BLOCKS_A_MULTIPROCESSOR = 106

        /** The compute capability as NVRTC numbers its architectures: the major version in tens, the minor in ones. */
        private const val TENS = 10

        /** The kernels [names] of [source], compiled for the card the runtime of [runtime] (in [dir]) works with. */
        internal fun compile(dir: Path, runtime: SymbolLookup, source: String, names: List<String>): CudaKernels {
            loadBuiltins(dir)
            val nvrtc = Nvrtc(SymbolLookup.libraryLookup(dir.resolve("nvrtc64_120_0.dll"), Arena.global()), runtime)
            return Arena.ofConfined().use { a ->
                val (major, minor) = nvrtc.capability(a)
                val target = nvrtc.target(a, major * TENS + minor)
                val blocks = nvrtc.blocks(a)
                val functions = nvrtc.load(a, nvrtc.ptx(a, source, target, blocks), names)
                log.info(
                    "{} kernels of their own compiled for the card, compute_{} (the card {}.{}, {} blocks a " +
                        "multiprocessor)",
                    names.size,
                    target,
                    major,
                    minor,
                    blocks,
                )
                CudaKernels(nvrtc, functions, blocks)
            }
        }

        /**
         * NVRTC opens its built-in headers' library by name alone, where Windows looks for it — not beside NVRTC:
         * loaded first by its full path, it is the one found. Loaded already by another class loader — ONNX Runtime's,
         * which loads the whole folder — it is in the process, which is all NVRTC needs: the JDK refuses it to this one
         * alone.
         */
        private fun loadBuiltins(dir: Path) {
            Files.list(dir).use { files ->
                files
                    .filter {
                        it.fileName.toString().startsWith("nvrtc-builtins") && it.fileName.toString().endsWith(".dll")
                    }
                    .forEach { runCatching { System.load(it.toAbsolutePath().toString()) } }
            }
        }
    }

    /** The functions of NVRTC and of the CUDA runtime that compile the kernels for the card and load them there. */
    private class Nvrtc(nvrtc: SymbolLookup, runtime: SymbolLookup) {
        private val linker = Linker.nativeLinker()
        private val int = ValueLayout.JAVA_INT
        private val address = ValueLayout.ADDRESS
        private val long = ValueLayout.JAVA_LONG
        private val getDevice = handleOf(runtime, "cudaGetDevice", FunctionDescriptor.of(int, address))
        private val attribute =
            handleOf(runtime, "cudaDeviceGetAttribute", FunctionDescriptor.of(int, address, int, int))
        private val create =
            handleOf(
                nvrtc,
                "nvrtcCreateProgram",
                FunctionDescriptor.of(int, address, address, address, int, address, address),
            )
        private val compileProgram =
            handleOf(nvrtc, "nvrtcCompileProgram", FunctionDescriptor.of(int, address, int, address))
        private val logSize = handleOf(nvrtc, "nvrtcGetProgramLogSize", FunctionDescriptor.of(int, address, address))
        private val logOf = handleOf(nvrtc, "nvrtcGetProgramLog", FunctionDescriptor.of(int, address, address))
        private val ptxSize = handleOf(nvrtc, "nvrtcGetPTXSize", FunctionDescriptor.of(int, address, address))
        private val ptxOf = handleOf(nvrtc, "nvrtcGetPTX", FunctionDescriptor.of(int, address, address))
        private val destroy = handleOf(nvrtc, "nvrtcDestroyProgram", FunctionDescriptor.of(int, address))
        private val archCount = handleOf(nvrtc, "nvrtcGetNumSupportedArchs", FunctionDescriptor.of(int, address))
        private val archs = handleOf(nvrtc, "nvrtcGetSupportedArchs", FunctionDescriptor.of(int, address))
        private val loadData =
            handleOf(
                runtime,
                "cudaLibraryLoadData",
                FunctionDescriptor.of(int, address, address, address, address, int, address, address, int),
            )
        private val getKernel =
            handleOf(runtime, "cudaLibraryGetKernel", FunctionDescriptor.of(int, address, address, address))
        val launch: MethodHandle =
            handleOf(
                runtime,
                "cudaLaunchKernel",
                FunctionDescriptor.of(int, address, DIM3, DIM3, address, long, address),
            )
        val attributes: MethodHandle =
            handleOf(runtime, "cudaFuncGetAttributes", FunctionDescriptor.of(int, address, address))
        val occupancy: MethodHandle =
            handleOf(
                runtime,
                "cudaOccupancyMaxActiveBlocksPerMultiprocessor",
                FunctionDescriptor.of(int, address, address, int, long),
            )

        private fun handleOf(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("no $name") }, descriptor)

        private fun ok(status: Int, what: String) = check(status == 0) { "$what: error $status" }

        /** The compute capability of the card the runtime works with: its major version and its minor one. */
        fun capability(a: Arena): Pair<Int, Int> {
            val value = a.allocate(int)
            ok(getDevice.invokeExact(value) as Int, "no device")
            val device = value.get(int, 0)
            ok(attribute.invokeExact(value, MAJOR, device) as Int, "no compute capability")
            val major = value.get(int, 0)
            ok(attribute.invokeExact(value, MINOR, device) as Int, "no compute capability")
            return major to value.get(int, 0)
        }

        /**
         * The blocks of [BLOCK] a multiprocessor of the card holds at most: of its threads, no more than its blocks.
         */
        fun blocks(a: Arena): Int {
            val value = a.allocate(int)
            ok(getDevice.invokeExact(value) as Int, "no device")
            val device = value.get(int, 0)
            ok(attribute.invokeExact(value, THREADS_A_MULTIPROCESSOR, device) as Int, "no threads of a multiprocessor")
            val threads = value.get(int, 0)
            ok(attribute.invokeExact(value, BLOCKS_A_MULTIPROCESSOR, device) as Int, "no blocks of a multiprocessor")
            return minOf(threads / BLOCK, value.get(int, 0)).coerceAtLeast(1)
        }

        /**
         * The card's own architecture [arch] if the compiler knows it; else the newest it knows — the driver compiles
         * that code for the card as it loads it.
         */
        fun target(a: Arena, arch: Int): Int {
            val value = a.allocate(int)
            ok(archCount.invokeExact(value) as Int, "no architectures of NVRTC")
            val known = a.allocate(int, value.get(int, 0).toLong())
            ok(archs.invokeExact(known) as Int, "no architectures of NVRTC")
            val supported = known.toArray(int)
            return if (arch in supported) arch
            else supported.filter { it < arch }.maxOrNull() ?: error("NVRTC knows no architecture up to $arch")
        }

        /**
         * The PTX of [source] compiled for the architecture [target], given the [blocks] of a multiprocessor, in memory
         * kept for the process's life.
         */
        fun ptx(a: Arena, source: String, target: Int, blocks: Int): MemorySegment {
            val program = a.allocate(address)
            ok(
                create.invokeExact(
                    program,
                    a.allocateFrom(source),
                    a.allocateFrom("kernels.cu"),
                    0,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                ) as Int,
                "no program",
            )
            val prog = program.get(address, 0)
            try {
                val options =
                    listOf("--gpu-architecture=compute_$target", "--std=c++17", "-DBLOCKS_A_MULTIPROCESSOR=$blocks")
                val optionPointers = a.allocate(address, options.size.toLong())
                options.forEachIndexed { k, o -> optionPointers.setAtIndex(address, k.toLong(), a.allocateFrom(o)) }
                val compiled = compileProgram.invokeExact(prog, options.size, optionPointers) as Int
                if (compiled != 0) {
                    val size = a.allocate(long)
                    logSize.invokeExact(prog, size) as Int
                    val text = a.allocate(size.get(long, 0).coerceAtLeast(1))
                    logOf.invokeExact(prog, text) as Int
                    error("the kernels were not compiled (error $compiled): ${text.getString(0)}")
                }
                val size = a.allocate(long)
                ok(ptxSize.invokeExact(prog, size) as Int, "no PTX")
                val ptx = Arena.global().allocate(size.get(long, 0))
                ok(ptxOf.invokeExact(prog, ptx) as Int, "no PTX")
                return ptx
            } finally {
                destroy.invokeExact(program) as Int
            }
        }

        /** The library of [ptx] loaded by the runtime, and the kernels [names] of it. */
        fun load(a: Arena, ptx: MemorySegment, names: List<String>): Map<String, MemorySegment> {
            val library = a.allocate(address)
            ok(
                loadData.invokeExact(
                    library,
                    ptx,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    0,
                    MemorySegment.NULL,
                    MemorySegment.NULL,
                    0,
                ) as Int,
                "the kernels were not loaded",
            )
            val lib = library.get(address, 0)
            return names.associateWith { name ->
                val kernel = a.allocate(address)
                ok(getKernel.invokeExact(kernel, lib, a.allocateFrom(name)) as Int, "no kernel $name")
                kernel.get(address, 0)
            }
        }
    }
}
