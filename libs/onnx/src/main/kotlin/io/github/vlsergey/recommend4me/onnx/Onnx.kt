package io.github.vlsergey.recommend4me.onnx

import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.OrtCUDAProviderOptions
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.FreeCardMemory
import io.github.vlsergey.recommend4me.matrix.NoCardRoom
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileChannel.MapMode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Onnx::class.java)

/**
 * Neural nets as ONNX graphs: one runtime, one session per graph, opened on the first call and kept — opening costs
 * seconds and a graph is asked thousands of times. The files lie in the models folder of the data folder; a missing
 * file is a missing net, reported when it is asked for and not before. Shared by every encoder plugin: one runtime in
 * the process.
 *
 * ON THE CPU, OR ON AN NVIDIA CARD when the launch asks for it ([gpu], `recommend4me.gpu`): the sessions then run on
 * CUDA, and a graph CUDA cannot take — no card, no libraries — runs on the CPU, with a warning, rather than not at all.
 * The CUDA libraries (tools/fetch-cuda.ps1) are loaded from [cudaDir] by full path before the first session: a library
 * already loaded is the one Windows hands to whoever asks for it by name, so neither PATH nor an older CUDA toolkit
 * installed on the machine decides which one the runtime gets.
 */
class Onnx(
    private val dir: Path,
    private val gpu: Boolean = false,
    private val cuda: Path = dir.resolveSibling("cuda"),
) {

    val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessions = HashMap<String, OrtSession>()
    private val made = HashMap<String, Pair<String, (ByteBuffer) -> ByteBuffer>>()
    private val onGpu = HashSet<String>()
    private val libraries = CudaLibraries(cuda)

    fun path(name: String): Path = dir.resolve(name)

    fun has(name: String): Boolean = Files.isRegularFile(path(synchronized(this) { made[name]?.first } ?: name))

    /**
     * The graph [name] made of the model file [of] by [make] ([OnnxSplice]): the file mapped and given to it as its
     * session is opened — a model of a gigabyte read out of the heap, and what it makes too —, and the session of what
     * it makes kept as any other.
     */
    @Synchronized
    fun madeOf(name: String, of: String, make: (ByteBuffer) -> ByteBuffer) {
        made[name] = of to make
    }

    /**
     * THE ROWS OF [order] RUN THROUGH THE GRAPH [name] IN BATCHES ([Batching]) — sorted by length, each of [lengthOf]
     * pieces —, every batch handed to [run] with the run of the graph it goes through. On the card a batch is made as
     * large as the memory the card has free as it begins lets ([FreeCardMemory.withRoom]) — a run of another graph, the
     * products, another process on the card leave it fewer rows —, and that memory is the runtime's for the run alone,
     * given back to the card as it ends; a row with no room on the card runs on the processor (owner, 2026-11-27: every
     * plugin takes the memory it needs by what there is and gives it back, its batches smaller when there is too
     * little). [threads] caps the threads of a run on the processor (0 — every core).
     */
    fun inBatches(
        name: String,
        order: List<Int>,
        lengthOf: (Int) -> Int,
        batching: Batching,
        threads: Int = 0,
        run: (List<Int>, GraphRun) -> Unit,
    ) {
        val session = session(name, threads)
        val free = if (synchronized(this) { name in onGpu }) freeOnCard() else null
        if (free == null) {
            onProcessor(order, 0, lengthOf, batching, GraphRun(session, null), run, all = true)
            return
        }
        var processor: OrtSession? = null
        try {
            var from = 0
            while (from < order.size) {
                val longest = lengthOf(order[minOf(from + batching.cardRows, order.size) - 1])
                val most = batching.memory.of(minOf(batching.cardRows, order.size - from), longest)
                from +=
                    try {
                        free.withRoom(batching.memory.of(1, lengthOf(order[from])), most) { room ->
                            val n = batching.next(order, from, lengthOf, batching.cardRows, room)
                            run(order.subList(from, from + n), GraphRun(session, shrinking))
                            n
                        }
                    } catch (e: NoCardRoom) {
                        val twin = processor ?: open(name, threads, cuda = false).also { processor = it }
                        log.warn("{}: no room on the card ({}), a batch runs on the processor", name, e.message)
                        onProcessor(order, from, lengthOf, batching, GraphRun(twin, null), run, all = false)
                    }
            }
        } finally {
            processor?.close()
        }
    }

    /**
     * The rows of [order] from [from] on run on the processor by [graph] in [batching]'s batches: every one of them
     * when [all], else the next batch alone; how many were run.
     */
    private fun onProcessor(
        order: List<Int>,
        from: Int,
        lengthOf: (Int) -> Int,
        batching: Batching,
        graph: GraphRun,
        run: (List<Int>, GraphRun) -> Unit,
        all: Boolean,
    ): Int {
        var at = from
        do {
            if (at == order.size) break
            val n = batching.next(order, at, lengthOf, batching.cpuRows, batching.cpuRoom)
            run(order.subList(at, at + n), graph)
            at += n
        } while (all)
        return at - from
    }

    /**
     * The session of a graph, opened on the first call and kept: on the card when it can be. [threads] caps the threads
     * of one run on the CPU (0 — the runtime's default, every core); it counts only when the session is opened.
     */
    @Synchronized
    private fun session(name: String, threads: Int): OrtSession {
        sessions[name]?.let {
            return it
        }
        val session = (if (gpu && libraries.ready()) openOnGpu(name) else null) ?: open(name, threads, cuda = false)
        sessions[name] = session
        log.info(
            "{} is up on {}: inputs {}, outputs {}",
            name,
            if (name in onGpu) "CUDA" else "the CPU",
            session.inputNames,
            session.outputNames,
        )
        return session
    }

    /** A session of the graph [name] — of its file, or of the bytes made of it ([madeOf]) — on the card or not. */
    private fun open(name: String, threads: Int, cuda: Boolean): OrtSession {
        val (file, make) = synchronized(this) { made[name] }?.let { it.first to it.second } ?: (name to null)
        val p = path(file)
        check(Files.isRegularFile(p)) { "No such model file: ${p.toAbsolutePath()}" }
        val options = sessionOptions(threads, cuda)
        if (make == null) return env.createSession(p.toString(), options)
        val graph = FileChannel.open(p, StandardOpenOption.READ).use { make(it.map(MapMode.READ_ONLY, 0, it.size())) }
        return env.createSession(graph, options)
    }

    /** The session of the graph [name] on the card; null, and CUDA not tried again, when it fails. */
    private fun openOnGpu(name: String): OrtSession? =
        try {
            open(name, 0, cuda = true).also { onGpu += name }
        } catch (e: OrtException) {
            log.warn("{} cannot run on CUDA, it runs on the CPU: {}", name, e.message)
            libraries.failed()
            null
        }

    /**
     * A session of a graph made in memory for one piece of work — its data among the graph's constants — on the card,
     * in single precision without TF32; null without a card. Not kept: the caller closes it when the work is done, and
     * the card's memory is given back — opened and run within a room its caller holds ([freeOnCard]). [supplied]: the
     * tensors the graph does not carry ([OnnxGraph.supplied]), by name, over the memory they lie in — kept open by the
     * caller as long as the session.
     */
    fun onCard(graph: ByteArray, supplied: Map<String, OnnxTensorLike> = emptyMap()): OrtSession? {
        if (!gpu || !synchronized(this) { libraries.ready() }) return null
        return try {
            env.createSession(
                graph,
                sessionOptions(0, cuda = true, tf32 = false).apply {
                    if (supplied.isNotEmpty()) addExternalInitializers(supplied)
                },
            )
        } catch (e: OrtException) {
            log.warn("A graph of {} bytes cannot run on CUDA: {}", graph.size, e.message)
            null
        }
    }

    /**
     * The card's memory free for a new piece of work, of the process ([FreeCardMemory]): what a batch on the card and a
     * graph of one's own ([onCard]) hold their room in. Null on the CPU, and when cuBLAS cannot be opened — the card
     * then works as it did before its memory was counted.
     */
    fun freeOnCard(): FreeCardMemory? = cudaLibraries()?.let(Cublas::open)?.free

    /**
     * The folder of the CUDA libraries, loaded ([CudaLibraries.ready]), when the launch asks for the card and they are
     * there: for whoever calls them besides the runtime — the matrix products of a fitting on the card (cuBLAS) — the
     * very libraries the runtime has, not those of a toolkit on PATH. Null on the CPU.
     */
    @Synchronized fun cudaLibraries(): Path? = cuda.takeIf { gpu && libraries.ready() }

    /** The options of a run on the card: the arena's memory the run took given back to the card as it ends. */
    private val shrinking: OrtSession.RunOptions by lazy {
        OrtSession.RunOptions().apply { addRunConfigEntry("memory.enable_memory_arena_shrinkage", "gpu:0") }
    }

    companion object {
        /**
         * The environment variable naming the file prefix ONNX Runtime writes its profile of every session to; unset,
         * none is written.
         */
        const val PROFILE = "RECOMMEND4ME_ORT_PROFILE"
    }
}

/**
 * The options of a session: on the card when [cuda], else on [threads] threads of the CPU (0 — every core). [tf32]: the
 * card's matrix products may round to ten bits of mantissa — as a neural net's do, at twice the speed.
 */
private fun sessionOptions(threads: Int, cuda: Boolean, tf32: Boolean = true): OrtSession.SessionOptions {
    val options = OrtSession.SessionOptions()
    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    // The time of every node of every run written out, when asked for: where a graph's time goes
    System.getenv(Onnx.PROFILE)?.let { options.enableProfiling(it) }
    if (cuda) {
        options.addCUDA(
            OrtCUDAProviderOptions(0).apply {
                if (!tf32) add("use_tf32", "0")
                // The arena grows by what a run asks, no more: what it took is given back as the run ends (shrinking)
                add("arena_extend_strategy", "kSameAsRequested")
            }
        )
        // Not the warning of every session that the shape operations stay on the CPU: the runtime puts them there
        // itself
        options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    } else if (threads > 0) options.setIntraOpNumThreads(threads)
    return options
}

/**
 * The CUDA libraries of [dir] (tools/fetch-cuda.ps1), loaded by full path before the first session on the card, and
 * whether CUDA can still be tried. Used under the lock of its [Onnx].
 */
private class CudaLibraries(private val dir: Path) {
    /** Whether CUDA can still be tried: false after it failed once, so the next graph does not wait for it again. */
    private var usable: Boolean? = null

    /** Whether CUDA can be tried: the libraries loaded on the first call. */
    fun ready(): Boolean = usable ?: load().also { usable = it }

    /** CUDA failed a graph: it is not tried again. */
    fun failed() {
        usable = false
    }

    /**
     * Loads every library of [dir]. A library is loaded only after those it needs, which are found among the loaded
     * ones by name: the folder is gone through again while that loads more. What never loads (a part of cuDNN that
     * needs a library not fetched) is left out — the runtime asks only for what it uses, and says so if it was needed.
     */
    private fun load(): Boolean {
        val libraries =
            if (Files.isDirectory(dir)) dir.listDirectoryEntries().filter { it.extension.equals("dll", true) }
            else emptyList()
        if (libraries.isEmpty()) {
            log.warn("No CUDA libraries in {} (tools/fetch-cuda.ps1): the nets run on the CPU", dir.toAbsolutePath())
            return false
        }
        var left = libraries.sortedBy { it.name }
        while (true) {
            val failed = left.filter { runCatching { System.load(it.toAbsolutePath().toString()) }.isFailure }
            if (failed.size == left.size) break
            left = failed
        }
        if (left.isNotEmpty())
            log.info("CUDA libraries not loaded (unless needed, no matter): {}", left.joinToString { it.name })
        return true
    }
}

/**
 * The models folder of a run without Spring — the tests: `recommend4me.models-dir`, or the one in the checkout's data
 * folder (`recommend4me.local-data-dir`, given by the build), or in the default data folder.
 */
fun defaultModelsDir(): java.nio.file.Path =
    System.getProperty("recommend4me.models-dir")?.let { java.nio.file.Path.of(it) }
        ?: System.getProperty("recommend4me.local-data-dir")?.let { java.nio.file.Path.of(it, "models") }
        ?: java.nio.file.Path.of(
            System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + "/.local/share"),
            "recommend4me",
            "models",
        )
