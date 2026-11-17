package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.encoder.ImageEncoder
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.textvector.TextVectors
import kotlin.math.sqrt

/**
 * WHAT THE AVERAGE WORK OF THE CATALOGUE IS: where the vectors and the numbers of every work lie, and how often it has
 * a categorical feature. Counted as every work streams past ([AverageCount]), or told by a model kept
 * ([ModelMeta.averages]), counted when it was trained. [shares]: a feature not among them is counted of the works read.
 */
class Averages(
    /** The mean and the spread of every block but the likeness to the marks, over the vectors of every work. */
    val vectors: Map<String, VectorSpread>,
    val numbers: Map<String, NumberSpread>,
    val shares: Map<String, Float> = emptyMap(),
)

/** The average work counted of every work of a catalogue ([Averages]), and the mean of its pictures. */
class AverageWork(val averages: Averages, val pictureCenter: FloatArray?)

/**
 * THE AVERAGE WORK COUNTED AS EVERY WORK STREAMS PAST, nothing of a work kept once it is counted: the vectors of every
 * source read in one pass of each kind ([read]) — of the texts, the pictures, the sets — and every work's features as a
 * portion of them is read ([add]): its blocks of the manners, its numbers, its categorical features. A catalogue of a
 * million works is counted in the memory of its sums.
 */
internal class AverageCount(
    private val textEncoder: TextEncoder?,
    private val imageEncoder: ImageEncoder?,
    private val embeddings: Map<SetKind, Long>,
) {
    private val spreads = VectorSpreads()
    private val pictureSum = imageEncoder?.let { DoubleArray(it.dim) }
    private var pictures = 0
    private val numbers = HashMap<String, NumberSum>()
    private val features = HashMap<String, Int>()
    private var works = 0

    /** Every vector of the source [s]: of its texts, its pictures and its sets, each kind's time a lap of [laps]. */
    fun read(s: SourceStore, laps: Laps) {
        var count = 0
        fun put(block: String, v: FloatArray) {
            count++
            spreads.add(block, v)
        }
        if (textEncoder != null) {
            // The title and the site's lines too: every text that has vectors
            val blocks = TextVectors.blocks(s.id, s.schema).mapValues { it.value.first }
            s.textVectors.forEach(textEncoder.id, blocks.keys) { _, key, v -> put(blocks.getValue(key), v) }
            laps.lap("text vectors", count)
        }
        if (imageEncoder != null) {
            count = 0
            s.pictures.itemVectors.forEachItemVector(imageEncoder.id) { _, block, v ->
                put(block, v)
                if (v.size == pictureSum!!.size) {
                    for (k in v.indices) pictureSum[k] += v[k].toDouble()
                    pictures++
                }
            }
            laps.lap("picture vectors", count)
        }
        count = 0
        s.setVectors.forEach(embeddings) { _, key, v -> put(key, v) }
        laps.lap("set vectors", count)
    }

    /**
     * A work as the model reads it ([work]: of several items, made of all of them), the numbers of the item that names
     * it ([own]), and the blocks of its manners.
     */
    fun add(work: CatalogueItem, own: CatalogueItem, manners: Map<String, FloatArray>) {
        works++
        work.categorical.forEach { features.merge(it, 1, Int::plus) }
        own.numeric.forEach { (key, value) -> numbers.getOrPut(key) { NumberSum() }.add(value) }
        manners.forEach { (block, v) -> spreads.add(block, v) }
    }

    /** The average work of every work counted. */
    fun made(): AverageWork {
        val center =
            pictureSum
                ?.takeIf { pictures > 0 }
                ?.let { sum ->
                    FloatArray(sum.size) { (sum[it] / pictures).toFloat() }
                }
        val shares = if (works == 0) emptyMap() else features.mapValues { it.value.toFloat() / works }
        val spreadOf = numbers.filterValues { it.count >= MIN_NUMBERS }.mapValues { it.value.spread() }
        return AverageWork(Averages(spreads.spreads(), spreadOf, shares), center)
    }

    /** The sum and the sum of the squares of a number over the works that have it. */
    private class NumberSum {
        var count = 0
        private var sum = 0.0
        private var squares = 0.0

        fun add(value: Float) {
            count++
            sum += value
            squares += value.toDouble() * value
        }

        fun spread(): NumberSpread {
            val mean = sum / count
            val sd = sqrt((squares / count - mean * mean).coerceAtLeast(0.0)).coerceAtLeast(LEAST_SD)
            return NumberSpread(mean.toFloat(), sd.toFloat())
        }
    }

    private companion object {
        /** The fewest works a number is of for its spread to be counted. */
        const val MIN_NUMBERS = 2

        /** The least spread of a number: of a number alike in every work, a division by it, not by nought. */
        const val LEAST_SD = 1e-6
    }
}
