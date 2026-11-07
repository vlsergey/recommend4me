package io.github.vlsergey.recommend4me.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.OrtCUDAProviderOptions
import java.nio.file.Files
import java.nio.file.Path
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
    private val onGpu = HashSet<String>()
    private val libraries = CudaLibraries(cuda)

    fun path(name: String): Path = dir.resolve(name)

    fun has(name: String): Boolean = Files.isRegularFile(path(name))

    /** Whether the graph runs on the card. Opens its session. */
    @Synchronized
    fun onGpu(name: String): Boolean {
        session(name)
        return name in onGpu
    }

    /**
     * The session of a graph, opened on the first call. [threads] caps the threads of one run on the CPU (0 — the
     * runtime's default, every core); it counts only when the session is opened.
     */
    @Synchronized
    fun session(name: String, threads: Int = 0): OrtSession {
        sessions[name]?.let {
            return it
        }
        val p = path(name)
        check(Files.isRegularFile(p)) { "No such model file: ${p.toAbsolutePath()}" }
        val session =
            (if (gpu && libraries.ready()) openOnGpu(name, p) else null)
                ?: env.createSession(p.toString(), sessionOptions(threads, cuda = false))
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

    /**
     * A session of a graph made in memory for one piece of work — its data among the graph's constants — on the card,
     * in single precision without TF32; null without a card. Not kept: the caller closes it when the work is done, and
     * the card's memory is given back. [supplied]: the tensors the graph does not carry ([OnnxGraph.supplied]), by
     * name, over the memory they lie in — kept open by the caller as long as the session.
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

    /** The session of the graph [name] of the file [p] on the card; null, and CUDA not tried again, when it fails. */
    private fun openOnGpu(name: String, p: Path): OrtSession? =
        try {
            env.createSession(p.toString(), sessionOptions(0, cuda = true)).also { onGpu += name }
        } catch (e: OrtException) {
            log.warn("{} cannot run on CUDA, it runs on the CPU: {}", name, e.message)
            libraries.failed()
            null
        }

    /**
     * The folder of the CUDA libraries, loaded ([CudaLibraries.ready]), when the launch asks for the card and they are
     * there: for whoever calls them besides the runtime — the matrix products of a fitting on the card (cuBLAS) — the
     * very libraries the runtime has, not those of a toolkit on PATH. Null on the CPU.
     */
    @Synchronized fun cudaLibraries(): Path? = cuda.takeIf { gpu && libraries.ready() }

    /** The input names the graph asks for. */
    fun inputNames(name: String): Set<String> = session(name).inputNames

    /**
     * Runs a graph with named inputs and returns the first output as a [batch, tokens, width] array — the hidden states
     * of a text encoder. The tensors are closed here.
     */
    fun runSequence(name: String, inputs: Map<String, OnnxTensor>): Array<Array<FloatArray>> {
        val session = session(name)
        try {
            session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                return result[0].value as Array<Array<FloatArray>>
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** Runs a graph with named inputs and returns the first output as rows of floats. The tensors are closed here. */
    fun run(name: String, inputs: Map<String, OnnxTensor>): Array<FloatArray> {
        val session = session(name)
        try {
            session.run(inputs).use { result ->
                @Suppress("UNCHECKED_CAST")
                return when (val v = result[0].value) {
                    is Array<*> -> v as Array<FloatArray>
                    is FloatArray -> arrayOf(v)
                    else -> error("Unexpected output ${v.javaClass}")
                }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
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
        if (tf32) options.addCUDA(0) else options.addCUDA(OrtCUDAProviderOptions(0).apply { add("use_tf32", "0") })
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
 * The models folder of a run without Spring — the tests: `recommend4me.models-dir`, or the one in the default data
 * folder.
 */
fun defaultModelsDir(): java.nio.file.Path =
    System.getProperty("recommend4me.models-dir")?.let { java.nio.file.Path.of(it) }
        ?: java.nio.file.Path.of(
            System.getenv("LOCALAPPDATA") ?: (System.getProperty("user.home") + "/.local/share"),
            "recommend4me",
            "models",
        )
