package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.onnx.Onnx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
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
 * THE NEIGHBOURS ARE A BOUNDED SET OF THE WORKS ([ReferenceSet]): [room] at most ([TaskPortions.MOST]) of the works
 * fitted on, those the user answered on first, the rest a sample by their ids — gathered in one walk of the portions,
 * the share of every value summed over every work in the same walk ([FoldSums]). k is chosen by the held-out loss of
 * the five folds ([SuggestionPairs]) among the works gathered, the neighbours of a held-out work taken from the other
 * folds; the one-standard-error rule preferring the more. THE MODEL KEPT IS THE WORKS GATHERED ([KnnModel]): where each
 * lies and what it says of the values — a work asked later is scored by its nearest among them, of its own inputs
 * alone. What the user answered on is handed over held out one work at a time ([FittedSuggester.heldOut]): by the k
 * chosen, of the other works gathered alone; of a source of more works answered on than the set holds, those left out
 * in a second walk.
 */
class KnnSuggester internal constructor(private val onnx: Onnx?, private val room: Int = TaskPortions.MOST) :
    FacetSuggester {
    constructor() : this(null)

    override val id = "knn-1"

    override fun fit(portions: TaskPortions, previous: FittedSuggester?, stop: () -> Boolean): FittedSuggester? {
        if (portions.values.valueCount == 0) return null
        val started = System.currentTimeMillis()
        return gather(portions, stop).takeIf { it.valued }?.let { chosen(portions, it, started, stop) }
    }

    /**
     * One walk of the [portions]: the works gathered as neighbours and the sums of the pairs of every value over every
     * work, by fold.
     */
    internal fun gather(portions: TaskPortions, stop: () -> Boolean): Gathered {
        val side = portions.values
        val reference = ReferenceSet(room, side.valueCount, side.inputs.map { it.name to it.dim })
        val sums = FoldSums(side.valueCount)
        var valued = false
        portions.forEach { portion ->
            stopIf(stop)
            val pairs = SuggestionPairs(portion)
            sums.add(pairs)
            reference.offer(portion, pairs)
            valued = valued || portion.assigned.any { it.isNotEmpty() }
        }
        return Gathered(reference.gathered(), sums.folds(), valued)
    }

    /** Every k tried on the works [gathered], the one chosen, fitted. */
    private fun chosen(portions: TaskPortions, gathered: Gathered, started: Long, stop: () -> Boolean): Fitted {
        val side = portions.values
        val ref = gathered.reference
        val near = Neighbours.among(ref.vectors, ref.size, ref.vectors, ref.size, IntArray(ref.size) { it }, onnx)
        val searched = System.currentTimeMillis() - started
        val tried =
            Neighbours.KS.map { k ->
                stopIf(stop)
                val at = System.currentTimeMillis()
                Tried(k, heldLoss(gathered, near, k), System.currentTimeMillis() - at)
            }
        val chosen = oneStandardError(tried, { it.held }, compareByDescending { it.k })
        log.info(
            "{} items × {} values, {} blocks of input, {} works kept, their neighbours {} in {} ms: {}; " +
                "chosen k={} in {} ms",
            portions.items,
            side.valueCount,
            side.inputs.size,
            ref.size,
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
        val held = Answered()
        answeredGathered(gathered, near, chosen.k, held)
        if (ref.leftOut > 0) answeredLeftOut(portions, gathered, chosen.k, held, stop)
        val layout = side.inputs.map { it.name to it.dim }
        val model = KnnModel(chosen.k, layout, ref.ids, ref.vectors, ref.labels, gathered.total.share())
        return Fitted(side.valueKeys, model, report, held.scores())
    }

    /**
     * The loss of the [k] nearest: of every work gathered, its chances by the works gathered of the other folds, drawn
     * towards the share of every value among every work of the other folds.
     */
    private fun heldLoss(gathered: Gathered, near: Neighbours, k: Int): HeldLoss {
        val ref = gathered.reference
        val held = HeldLoss()
        gathered.folds.forEachIndexed { f, fold ->
            val share = (gathered.total - fold).share()
            for (i in 0 until ref.size) {
                if (ref.folds[i] != f) continue
                val c = ref.labels.chances(near.rows[i], near.likeness[i], share, k) { ref.folds[it] != f }
                ref.labels.forEach(i) { v, w, y -> held.add(w.toDouble(), SuggestionPairs.logLoss(c[v].toDouble(), y)) }
            }
        }
        return held
    }

    /**
     * LEAVE ONE OUT, of the works gathered the user answered on: of every value answered, its chance by the [k] nearest
     * of the other works gathered ([near] leaves the work itself out, as the model kept does) drawn towards the value's
     * share among every other work — the work's own pairs taken out of it.
     */
    private fun answeredGathered(gathered: Gathered, near: Neighbours, k: Int, into: Answered) {
        val ref = gathered.reference
        for (i in 0 until ref.size) {
            if (ref.asked[i].isEmpty()) continue
            val share = gathered.total.shareWithout(ref.labels, i)
            into.add(ref.ids[i], ref.asked[i], ref.labels.chances(near.rows[i], near.likeness[i], share, k) { true })
        }
    }

    /**
     * LEAVE ONE OUT, of the works the user answered on left out of those gathered, in a walk of the [portions] of its
     * own: of every value answered, its chance by the [k] nearest of the works gathered, drawn towards the value's
     * share among every other work.
     */
    private fun answeredLeftOut(
        portions: TaskPortions,
        gathered: Gathered,
        k: Int,
        into: Answered,
        stop: () -> Boolean,
    ) {
        val ref = gathered.reference
        val known = ref.ids.toHashSet()
        val layout = portions.values.inputs.map { it.name to it.dim }
        portions.forEach { portion ->
            stopIf(stop)
            val rows =
                (0 until portion.itemCount).filter {
                    portion.itemIds[it] !in known &&
                        (portion.confirmed[it].isNotEmpty() || portion.rejected[it].isNotEmpty())
                }
            if (rows.isEmpty()) return@forEach
            val few = portion.ofItems(rows.toIntArray())
            val y = requireNotNull(Neighbours.project(few.inputs, few.itemCount, layout)) { "Other blocks of input" }
            val near = Neighbours.among(y, few.itemCount, ref.vectors, ref.size, IntArray(few.itemCount) { -1 }, onnx)
            val own = Labels.of(SuggestionPairs(few))
            for (r in 0 until few.itemCount) {
                val share = gathered.total.shareWithout(own, r)
                val chances = ref.labels.chances(near.rows[r], near.likeness[r], share, k) { true }
                into.add(few.itemIds[r], few.confirmed[r] + few.rejected[r], chances)
            }
        }
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? =
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != FORMAT) return null
            val keys = List(input.readInt()) { input.readUTF() }
            if (keys != task.valueKeys) return null
            val model = KnnModel.read(ByteArray(input.readInt()).also(input::readFully)) ?: return null
            Fitted(keys, model)
        } catch (_: IOException) {
            null
        }

    override fun scoring(model: ByteArray, task: SuggestionTask): SuggestionScores? =
        KnnModel.read(model)?.on(task, onnx)

    /** Every value's share among the works the model was fitted on. */
    override fun weights(model: ByteArray): DoubleArray? = KnnModel.read(model)?.share

    /**
     * What one walk gathered: the works as neighbours ([reference]), the sums of the pairs of every value of the works
     * of every fold ([folds]) and of every work ([total]), whether any work has a value ([valued]).
     */
    internal class Gathered(val reference: Reference, val folds: List<Labels.Sums>, val valued: Boolean) {
        val total = FoldSums.total(folds)
    }

    private class Tried(val k: Int, val held: HeldLoss, val millis: Long)

    /**
     * The [model] fitted — the works gathered and the k chosen — of the values [valueKeys]: the state a refit knows,
     * packed whole, the model made of it alone. [heldOut]: of the fitting alone, not packed.
     */
    private class Fitted(
        val valueKeys: List<String>,
        val model: KnnModel,
        override val report: FitReport? = null,
        override val heldOut: HeldOutScores? = null,
    ) : FittedSuggester {
        override fun model(task: SuggestionTask): ByteArray = model.pack()

        override fun pack(): ByteArray {
            val works = model.pack()
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(FORMAT)
                out.writeInt(valueKeys.size)
                valueKeys.forEach(out::writeUTF)
                out.writeInt(works.size)
                out.write(works)
            }
            return bytes.toByteArray()
        }
    }

    /** Of every value the user answered on of every work — confirmed, then rejected — the score held out. */
    private class Answered {
        private val items = ArrayList<String>()
        private val values = ArrayList<Int>()
        private val scores = ArrayList<Float>()

        /** The [chances] of the values [asked] of the work [id]. */
        fun add(id: String, asked: IntArray, chances: FloatArray) {
            asked.forEach { v ->
                items += id
                values += v
                scores += chances[v]
            }
        }

        fun scores() = HeldOutScores(items, values.toIntArray(), scores.toFloatArray())
    }

    companion object {
        /** The format of the state packed: the keys of the values and the model. */
        private const val FORMAT = 2
        private const val K = "k"

        /** Gives the fitting up once [stop] says so: newer data came, and it is fitted on that. */
        private fun stopIf(stop: () -> Boolean) {
            if (stop()) throw CancellationException("the nearest works given up for newer changes")
        }
    }
}
