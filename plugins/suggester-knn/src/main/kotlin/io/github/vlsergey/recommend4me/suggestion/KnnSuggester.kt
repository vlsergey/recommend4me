package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.onnx.Onnx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CancellationException
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(KnnSuggester::class.java)

/**
 * THE NEAREST WORKS: the chance a work has a value is the share of its k most alike works that have it — each by how
 * alike — drawn towards the value's share among all the works by one work's worth. Alike by the cosine of what the
 * work's texts and pictures were encoded as ([InputBlock]): every block of unit length, the blocks side by side, put
 * down to [Neighbours.DIMENSIONS] dimensions by a random projection (fixed by its seed, the same every time) and of
 * unit length again. Reads neither the names of the values nor the work's other facets: a measure of what the texts and
 * pictures tell by likeness alone.
 *
 * The likeness of every work to every other, the [Neighbours.MAX_K] nearest of each kept ([Neighbours]); k chosen by
 * the held-out loss of the five folds ([SuggestionPairs]), the neighbours of a held-out work taken from the other
 * folds; the one-standard-error rule preferring the more. THE MODEL KEPT IS THE WORKS FITTED ON ([KnnModel]): where
 * each lies and what it says of the values — a work asked later is scored by its nearest among them, of its own inputs
 * alone.
 */
class KnnSuggester internal constructor(private val onnx: Onnx?) : FacetSuggester {
    constructor() : this(null)

    override val id = "knn-1"

    override fun fit(task: SuggestionTask, previous: FittedSuggester?, stop: () -> Boolean): FittedSuggester? {
        if (task.valueCount == 0 || task.assigned.all { it.isEmpty() }) return null
        val started = System.currentTimeMillis()
        val pairs = SuggestionPairs(task)
        val labels = Labels.of(pairs)
        val layout = task.inputs.map { it.name to it.dim }
        val y = Neighbours.project(task.inputs, task.itemCount, layout)!!
        val near = Neighbours.among(y, task.itemCount, y, task.itemCount, IntArray(task.itemCount) { it }, onnx)
        val searched = System.currentTimeMillis() - started
        val tried = ArrayList<Tried>()
        for (k in Neighbours.KS) {
            if (stop()) throw CancellationException("the nearest works given up for newer changes")
            val at = System.currentTimeMillis()
            val held = pairs.heldOut { fold, items ->
                val share = labels.share { pairs.foldOf[it] != fold }
                items.map { i -> labels.chances(near.rows[i], near.likeness[i], share, k) { pairs.foldOf[it] != fold } }
            }
            tried += Tried(k, held, System.currentTimeMillis() - at)
        }
        val chosen = oneStandardError(tried, { it.held }, compareByDescending { it.k })
        log.info(
            "{} items × {} values, {} blocks of input, neighbours {} in {} ms: {}; chosen k={} in {} ms",
            task.itemCount,
            task.valueCount,
            task.inputs.size,
            if (near.onCard) "on the card" else "on the processor",
            searched,
            tried.joinToString("; ") { "k=${it.k}: %.4f ± %.4f".format(it.held.mean, it.held.error) },
            chosen.k,
            System.currentTimeMillis() - started,
        )
        val report =
            FitReport(
                mapOf(K to chosen.k.toString()),
                chosen.held.mean,
                chosen.held.error,
                tried.map { FitTry(mapOf(K to it.k.toString()), it.held.mean, it.held.error, it.millis) },
                fromScratch = true,
            )
        return Fitted(chosen.k, report)
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != FORMAT) return null
        return Fitted(input.readInt())
    }

    override fun scoring(model: ByteArray, task: SuggestionTask): SuggestionScores? =
        KnnModel.read(model)?.on(task, onnx)

    /** Every value's share among the works the model was fitted on. */
    override fun weights(model: ByteArray): DoubleArray? = KnnModel.read(model)?.share

    private class Tried(val k: Int, val held: HeldLoss, val millis: Long)

    /** The [k] chosen: the state a refit knows; the works themselves are the model ([model]). */
    private class Fitted(val k: Int, override val report: FitReport? = null) : FittedSuggester {
        override fun model(task: SuggestionTask): ByteArray = KnnModel.of(task, k).pack()

        override fun pack(): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(FORMAT)
                out.writeInt(k)
            }
            return bytes.toByteArray()
        }
    }

    companion object {
        private const val FORMAT = 1
        private const val K = "k"
    }
}
