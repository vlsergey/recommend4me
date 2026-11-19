package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.encoder.ImageEncoder
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.source.TypeStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
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

/**
 * The average work counted of every work of a catalogue ([Averages]), and the mean of its pictures. Kept in the model
 * database as [pack] writes it ([AverageWorkRepository]): what a training reads instead of every work.
 */
class AverageWork(val averages: Averages, val pictureCenter: FloatArray?) {

    /** The average work as bytes: [unpack] reads them. */
    fun pack(): ByteArray = packed { out ->
        out.writeInt(averages.vectors.size)
        averages.vectors.forEach { (block, spread) ->
            out.writeUTF(block)
            out.writeFloats(spread.center)
            out.writeFloat(spread.spread)
        }
        out.writeInt(averages.numbers.size)
        averages.numbers.forEach { (key, spread) ->
            out.writeUTF(key)
            out.writeFloat(spread.mean)
            out.writeFloat(spread.sd)
        }
        out.writeInt(averages.shares.size)
        averages.shares.forEach { (feature, share) ->
            out.writeUTF(feature)
            out.writeFloat(share)
        }
        out.writeBoolean(pictureCenter != null)
        pictureCenter?.let(out::writeFloats)
    }

    companion object {
        /** The format of [pack]: a kept average of another is none, counted again. */
        const val FORMAT = 1

        /** The average work [pack] wrote. */
        fun unpack(bytes: ByteArray): AverageWork =
            unpacked(bytes) { input ->
                val vectors =
                    (0 until input.readInt()).associate {
                        input.readUTF() to VectorSpread(input.readFloats(), input.readFloat())
                    }
                val numbers =
                    (0 until input.readInt()).associate {
                        input.readUTF() to NumberSpread(input.readFloat(), input.readFloat())
                    }
                val shares = (0 until input.readInt()).associate { input.readUTF() to input.readFloat() }
                val center = if (input.readBoolean()) input.readFloats() else null
                AverageWork(Averages(vectors, numbers, shares), center)
            }

        /**
         * What the vectors of the works of the [type] are made by — the encoders of the texts and of the pictures, the
         * directions of the sets: an average work counted of vectors made otherwise is none.
         */
        fun basis(plugins: Plugins, type: TypeStore): String =
            listOf(
                    plugins.textEncoder()?.id,
                    plugins.imageEncoder()?.id,
                    type.embeddings.ids().toSortedMap().toString(),
                )
                .joinToString("|")
    }
}

/**
 * A vector of an item as it is read — of a [picture], or of another kind: what [AverageCount] counts along a scoring.
 */
fun interface VectorSeen {
    fun seen(block: String, v: FloatArray, picture: Boolean)
}

/**
 * THE AVERAGE WORK COUNTED AS EVERY WORK STREAMS PAST, nothing of a work kept once it is counted: every vector of an
 * item as it is read ([seen]) — as a sweep of the scores reads a portion's — and every work's features as a portion of
 * them is read ([add]): its blocks of the manners, its numbers, its categorical features. A catalogue of a million
 * works is counted in the memory of its sums, and the sums go from one turn of a sweep to its next as bytes ([pack]).
 */
internal class AverageCount(
    private val textEncoder: TextEncoder?,
    private val imageEncoder: ImageEncoder?,
    private val embeddings: Map<SetKind, Long>,
) : VectorSeen {
    private val spreads = VectorSpreads()
    private var pictureSum = imageEncoder?.let { DoubleArray(it.dim) }
    private var pictures = 0
    private val numbers = HashMap<String, NumberSum>()
    private val features = HashMap<String, Int>()
    private var works = 0

    /** A vector of an item, read by whoever reads it — the scoring of a portion, chunks of it side by side. */
    @Synchronized
    override fun seen(block: String, v: FloatArray, picture: Boolean) {
        spreads.add(block, v)
        val sum = pictureSum
        if (picture && sum != null && v.size == sum.size) {
            for (k in v.indices) sum[k] += v[k].toDouble()
            pictures++
        }
    }

    /**
     * A work as the model reads it ([work]: of several items, made of all of them), the numbers of the item that names
     * it ([own]), and the blocks of its manners.
     */
    @Synchronized
    fun add(work: CatalogueItem, own: CatalogueItem, manners: Map<String, FloatArray>) {
        works++
        work.categorical.forEach { features.merge(it, 1, Int::plus) }
        own.numeric.forEach { (key, value) -> numbers.getOrPut(key) { NumberSum() }.add(value) }
        manners.forEach { (block, v) -> spreads.add(block, v) }
    }

    /** Every work of the [portion] [add]ed. */
    fun addAll(portion: WorkPortion) =
        portion.catalogue.items.forEach { add(it, portion.own.getValue(it.key), portion.manners.blocks(it.key)) }

    /** The average work of every work counted. */
    @Synchronized
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

    /** The sums so far as bytes: [resume] goes on with them. */
    @Synchronized
    fun pack(): ByteArray = packed { out ->
        spreads.writeTo(out)
        out.writeBoolean(pictureSum != null)
        pictureSum?.let { sum ->
            out.writeInt(sum.size)
            sum.forEach(out::writeDouble)
        }
        out.writeInt(pictures)
        out.writeInt(numbers.size)
        numbers.forEach { (key, sum) ->
            out.writeUTF(key)
            sum.writeTo(out)
        }
        out.writeInt(features.size)
        features.forEach { (feature, n) ->
            out.writeUTF(feature)
            out.writeInt(n)
        }
        out.writeInt(works)
    }

    /** The count goes on with the sums [pack] wrote: of a count of none yet. */
    fun resume(bytes: ByteArray): AverageCount = also {
        unpacked(bytes) { input ->
            spreads.readFrom(input)
            val sum = if (input.readBoolean()) DoubleArray(input.readInt()) { input.readDouble() } else null
            // Of a picture encoder of another length — changed since — the pictures counted anew
            if (sum != null && sum.size == pictureSum?.size) pictureSum = sum
            pictures = input.readInt().takeIf { pictureSum === sum } ?: 0
            repeat(input.readInt()) { numbers[input.readUTF()] = NumberSum.readFrom(input) }
            repeat(input.readInt()) { features[input.readUTF()] = input.readInt() }
            works = input.readInt()
        }
    }

    /** The sum and the sum of the squares of a number over the works that have it. */
    private class NumberSum(var count: Int = 0, private var sum: Double = 0.0, private var squares: Double = 0.0) {

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

        fun writeTo(out: DataOutputStream) {
            out.writeInt(count)
            out.writeDouble(sum)
            out.writeDouble(squares)
        }

        companion object {
            fun readFrom(input: DataInputStream) = NumberSum(input.readInt(), input.readDouble(), input.readDouble())
        }
    }

    private companion object {
        /** The fewest works a number is of for its spread to be counted. */
        const val MIN_NUMBERS = 2

        /** The least spread of a number: of a number alike in every work, a division by it, not by nought. */
        const val LEAST_SD = 1e-6
    }
}

/** What [write] writes, deflated. */
private fun packed(write: (DataOutputStream) -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(DeflaterOutputStream(bytes)).use(write)
    return bytes.toByteArray()
}

/** What [read] reads of the [bytes] [packed] wrote. */
private fun <T> unpacked(bytes: ByteArray, read: (DataInputStream) -> T): T =
    DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes))).use(read)

private fun DataOutputStream.writeFloats(v: FloatArray) {
    writeInt(v.size)
    v.forEach(::writeFloat)
}

private fun DataInputStream.readFloats(): FloatArray = FloatArray(readInt()) { readFloat() }
