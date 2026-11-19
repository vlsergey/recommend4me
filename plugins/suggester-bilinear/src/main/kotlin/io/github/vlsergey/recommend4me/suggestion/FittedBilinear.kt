package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs
import kotlin.math.ln

/**
 * THE STATE OF A FITTING OF THE BILINEAR MODEL ([BilinearSuggester]): the model of every fold, fitted with the [rank]
 * and the [lambda] chosen, on the [design] of the facet fitted on; the folds' weights at the penalties beside it
 * ([nearby]), for a refit to go on from; every value's share of the works fitted on ([shares]). The model the items are
 * scored by made of it and of the value side ([model]); kept and read again ([pack], [read]).
 */
internal class FittedBilinear(
    val design: Design,
    val rank: Int,
    val lambda: Double,
    val folds: List<Weights>,
    val nearby: Map<Double, List<Weights>> = emptyMap(),
    /** What the fitting that made it tried and chose; none of a state unpacked. */
    override val report: FitReport? = null,
    /**
     * The scores of the pairs the user answered on, each by the fold that held its item out, as the model kept scores
     * them ([ScoredPortions]); none of a state unpacked.
     */
    override val heldOut: HeldOutScores? = null,
    /**
     * Every value's share of the works fitted on, of the values of the [design] in their order ([ScoredPortions]); none
     * of a state kept before they were.
     */
    val shares: FloatArray? = null,
) : FittedSuggester {

    /** The state as the fitting hands it over: with its [report], the scores held out and the values' shares. */
    fun handedOver(report: FitReport, heldOut: HeldOutScores, shares: FloatArray) =
        FittedBilinear(design, rank, lambda, folds, nearby, report, heldOut, shares)

    /** The folds' weights at the penalty kept nearest to [penalty]. */
    fun nearest(penalty: Double): List<Weights> =
        (nearby + (lambda to folds)).minBy { abs(ln(it.key / penalty)) }.value.takeIf { it.size == folds.size } ?: folds

    /**
     * The scoring of the values of the value side [task], kept with every value's share of the works fitted on: what
     * the model tells of the values over the works as a whole — a value the fitting did not know of none.
     */
    override fun model(task: SuggestionTask): ByteArray {
        val scoring = scoringOf(task)
        val kept = shares ?: return scoring.pack()
        val known = design.valueIndices(task)
        return scoring
            .withShares(FloatArray(task.valueCount) { known[it].let { k -> if (k >= 0) kept[k] else 0f } })
            .pack()
    }

    /**
     * The scoring of the items against the values of [task]: every fold's coordinates of its values — their names and
     * groups carried by B, their own coordinates added — and their biases.
     */
    fun scoringOf(task: SuggestionTask): Scoring {
        val m = task.valueCount
        val z = design.values(task)
        val known = design.valueIndices(task)
        val dz = design.valueWidth
        val sides = folds.map { w ->
            val v = Floats(m * rank)
            Blas.product(z.held, dz, m, dz, Floats.of(w.b), rank, v)
            // Transposed for the product
            val vt = FloatArray(rank * m)
            val bias = FloatArray(m)
            for (j in 0 until m) {
                val own = known[j]
                for (q in 0 until rank) vt[q * m + j] = v[j * rank + q] + (if (own >= 0) w.e[own * rank + q] else 0f)
                if (own >= 0) bias[j] = w.bias[own]
            }
            vt to bias
        }
        return Scoring(
            design,
            rank,
            task.valueKeys,
            folds.map { it.a },
            folds.map { it.pair },
            sides.map { it.first },
            sides.map { it.second },
            folds.map { it.b },
        )
    }

    override fun pack(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes, BUFFER).buffered(BUFFER)).use { out ->
            out.writeInt(FORMAT)
            design.write(out)
            out.writeInt(rank)
            out.writeDouble(lambda)
            out.writeInt(folds.size)
            folds.forEach { it.write(out) }
            out.writeInt(nearby.size)
            nearby.forEach { (penalty, weights) ->
                out.writeDouble(penalty)
                out.writeInt(weights.size)
                weights.forEach { it.write(out) }
            }
            Design.writeFloats(out, shares ?: FloatArray(0))
        }
        return bytes.toByteArray()
    }

    companion object {
        /**
         * The version of what [pack] writes; 5: fitted in single precision, every facet fitted again; 6: the weights at
         * the penalties beside the chosen one too; 7: the card's verdict, how many groups of names it was slower at; 8:
         * no weight of the counts of the values' names in the texts ([Bilinear.PAIR]) — a state of 7 read without it
         * ([Weights.withoutCounts]); 9: fitted a portion at a time, the values' shares of the works kept, no card's
         * verdict — one of 7 or 8 read past it, without shares.
         */
        private const val FORMAT = 9

        /** The oldest version read. */
        private const val OLDEST_READ = 7

        /** The buffer of the compressed state read and written: the numbers go in blocks of many kilobytes. */
        private const val BUFFER = 1 shl 16

        /**
         * A state kept by [pack], of [folds] folds, read against the value side [task]; null of another version, of
         * other bytes, or of a layout that does not read the task.
         */
        fun read(bytes: ByteArray, task: SuggestionTask, folds: Int): FittedBilinear? = runCatching {
            // Buffered: the stream is read in many small pieces — the numbers in blocks, the keys one by one
            DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes), BUFFER).buffered(BUFFER)).use { input ->
                // A state of 7 read with the weight of the counts of names left out — the core gave none since
                // before it was fitted; of 7 and 8 the card's verdict of a step since gone read past, no shares
                val format = input.readInt()
                if (format !in OLDEST_READ..FORMAT) return null
                fun weights() = Weights.read(input).let { if (format == OLDEST_READ) it.withoutCounts() else it }
                val design = Design.read(input)
                val rank = input.readInt()
                val lambda = input.readDouble()
                val weights = List(input.readInt()) { weights() }
                val nearby = List(input.readInt()) { input.readDouble() to List(input.readInt()) { weights() } }.toMap()
                val shares = if (format == FORMAT) Design.readFloats(input).takeIf { it.isNotEmpty() } else null
                if (format < FORMAT) input.readInt()
                if (weights.size != folds || !design.reads(task)) null
                else FittedBilinear(design, rank, lambda, weights, nearby, shares = shares)
            }
        }
            .getOrNull()
    }
}
