package io.github.vlsergey.recommend4me.matrix

import org.slf4j.LoggerFactory
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

private val log = LoggerFactory.getLogger(CudaKernels::class.java)

/**
 * KERNELS OF ONE'S OWN ON THE CARD, beside the products of [Cublas]: CUDA C compiled at the start by
 * NVRTC (`nvrtc64_120_0`, beside the runtime's libraries) for the card's own architecture, loaded by
 * the runtime (`cudaLibraryLoadData`) and launched on its default stream — after the products asked
 * for before them, before those asked for after. Called through the foreign function interface of
 * the JDK, as cuBLAS is. Made by [Cublas.kernels]; one thread at a time, as the products.
 */
class CudaKernels private constructor(
    private val cudaLaunchKernel: MethodHandle,
    private val functions: Map<String, MemorySegment>,
) {
    /**
     * The kernel [name] over [threads] threads, [BLOCK] a block, of [args] in the order of its
     * parameters: a [Cublas.DeviceFloats] or [Cublas.DeviceBytes] as the address of its first number,
     * a [MemorySegment] as the address it is, an [Int], a [Long], a [Float] or a [Double] as itself.
     */
    fun launch(name: String, threads: Long, vararg args: Any) {
        if (threads <= 0) return
        val function = functions[name] ?: error("no kernel $name")
        val blocks = (threads + BLOCK - 1) / BLOCK
        require(blocks <= Int.MAX_VALUE) { "$threads threads are too many for one launch" }
        Arena.ofConfined().use { a ->
            val pointers = a.allocate(ValueLayout.ADDRESS, args.size.toLong().coerceAtLeast(1))
            args.forEachIndexed { k, arg ->
                val value = when (arg) {
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
            val grid = a.allocate(DIM3).also { it.set(ValueLayout.JAVA_INT, 0, blocks.toInt()); it.set(ValueLayout.JAVA_INT, 4, 1); it.set(ValueLayout.JAVA_INT, 8, 1) }
            val block = a.allocate(DIM3).also { it.set(ValueLayout.JAVA_INT, 0, BLOCK); it.set(ValueLayout.JAVA_INT, 4, 1); it.set(ValueLayout.JAVA_INT, 8, 1) }
            val status = cudaLaunchKernel.invokeExact(function, grid, block, pointers, 0L, MemorySegment.NULL) as Int
            check(status == 0) { "the kernel $name was not launched: CUDA error $status" }
        }
    }

    companion object {
        /** Threads of a block: a kernel's shared sums are made for as many. */
        const val BLOCK = 256

        /** CUDA's dim3: three unsigned ints, handed over by value. */
        private val DIM3 = MemoryLayout.structLayout(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)

        /** The device attributes of the compute capability. */
        private const val MAJOR = 75
        private const val MINOR = 76

        /** The kernels [names] of [source], compiled for the card the runtime of [runtime] (in [dir]) works with. */
        internal fun compile(dir: Path, runtime: SymbolLookup, source: String, names: List<String>): CudaKernels {
            val linker = Linker.nativeLinker()
            // NVRTC opens its built-in headers' library by name alone, where Windows looks for it —
            // not beside NVRTC: loaded first by its full path, it is the one found. Loaded already by
            // another class loader — ONNX Runtime's, which loads the whole folder — it is in the
            // process, which is all NVRTC needs: the JDK refuses it to this one alone
            Files.list(dir).use { files ->
                files.filter { it.fileName.toString().startsWith("nvrtc-builtins") && it.fileName.toString().endsWith(".dll") }
                    .forEach { runCatching { System.load(it.toAbsolutePath().toString()) } }
            }
            val nvrtc = SymbolLookup.libraryLookup(dir.resolve("nvrtc64_120_0.dll"), Arena.global())
            fun handleOf(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor) =
                linker.downcallHandle(lookup.find(name).orElseThrow { IllegalStateException("no $name") }, descriptor)
            val int = ValueLayout.JAVA_INT
            val address = ValueLayout.ADDRESS
            val long = ValueLayout.JAVA_LONG
            val getDevice = handleOf(runtime, "cudaGetDevice", FunctionDescriptor.of(int, address))
            val attribute = handleOf(runtime, "cudaDeviceGetAttribute", FunctionDescriptor.of(int, address, int, int))
            val create = handleOf(nvrtc, "nvrtcCreateProgram", FunctionDescriptor.of(int, address, address, address, int, address, address))
            val compileProgram = handleOf(nvrtc, "nvrtcCompileProgram", FunctionDescriptor.of(int, address, int, address))
            val logSize = handleOf(nvrtc, "nvrtcGetProgramLogSize", FunctionDescriptor.of(int, address, address))
            val logOf = handleOf(nvrtc, "nvrtcGetProgramLog", FunctionDescriptor.of(int, address, address))
            val ptxSize = handleOf(nvrtc, "nvrtcGetPTXSize", FunctionDescriptor.of(int, address, address))
            val ptxOf = handleOf(nvrtc, "nvrtcGetPTX", FunctionDescriptor.of(int, address, address))
            val destroy = handleOf(nvrtc, "nvrtcDestroyProgram", FunctionDescriptor.of(int, address))
            val archCount = handleOf(nvrtc, "nvrtcGetNumSupportedArchs", FunctionDescriptor.of(int, address))
            val archs = handleOf(nvrtc, "nvrtcGetSupportedArchs", FunctionDescriptor.of(int, address))
            val loadData = handleOf(
                runtime, "cudaLibraryLoadData",
                FunctionDescriptor.of(int, address, address, address, address, int, address, address, int),
            )
            val getKernel = handleOf(runtime, "cudaLibraryGetKernel", FunctionDescriptor.of(int, address, address, address))
            val launch = handleOf(runtime, "cudaLaunchKernel", FunctionDescriptor.of(int, address, DIM3, DIM3, address, long, address))

            return Arena.ofConfined().use { a ->
                fun ok(status: Int, what: String) = check(status == 0) { "$what: error $status" }
                val value = a.allocate(int)
                ok(getDevice.invokeExact(value) as Int, "no device")
                val device = value.get(int, 0)
                ok(attribute.invokeExact(value, MAJOR, device) as Int, "no compute capability")
                val major = value.get(int, 0)
                ok(attribute.invokeExact(value, MINOR, device) as Int, "no compute capability")
                val arch = major * 10 + value.get(int, 0)
                // The card's own architecture if the compiler knows it; else the newest it knows —
                // the driver compiles that code for the card as it loads it
                ok(archCount.invokeExact(value) as Int, "no architectures of NVRTC")
                val known = a.allocate(int, value.get(int, 0).toLong())
                ok(archs.invokeExact(known) as Int, "no architectures of NVRTC")
                val supported = known.toArray(int)
                val target = if (arch in supported) arch else supported.filter { it < arch }.maxOrNull() ?: error("NVRTC knows no architecture up to $arch")

                val program = a.allocate(address)
                ok(create.invokeExact(program, a.allocateFrom(source), a.allocateFrom("kernels.cu"), 0, MemorySegment.NULL, MemorySegment.NULL) as Int, "no program")
                val prog = program.get(address, 0)
                try {
                    val options = listOf("--gpu-architecture=compute_$target", "--std=c++17")
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
                    val library = a.allocate(address)
                    ok(
                        loadData.invokeExact(library, ptx, MemorySegment.NULL, MemorySegment.NULL, 0, MemorySegment.NULL, MemorySegment.NULL, 0) as Int,
                        "the kernels were not loaded",
                    )
                    val lib = library.get(address, 0)
                    val functions = names.associateWith { name ->
                        val kernel = a.allocate(address)
                        ok(getKernel.invokeExact(kernel, lib, a.allocateFrom(name)) as Int, "no kernel $name")
                        kernel.get(address, 0)
                    }
                    log.info("{} kernels of their own compiled for the card, compute_{} (the card {}.{})", names.size, target, major, arch % 10)
                    CudaKernels(launch, functions)
                } finally {
                    destroy.invokeExact(program) as Int
                }
            }
        }
    }
}
