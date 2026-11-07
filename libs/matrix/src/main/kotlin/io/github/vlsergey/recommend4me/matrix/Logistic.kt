package io.github.vlsergey.recommend4me.matrix

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.ByteOrder
import jdk.incubator.vector.FloatVector
import jdk.incubator.vector.VectorOperators

/*
 * The logistic loss of rows of scores and its slopes, in the wide registers when [Simd.on]: the loss of the bilinear
 * suggesters, tens of millions of scores a step of a fitting. Called as [Simd]'s, beside its inner loops of algebra.
 */

private val ORDER = ByteOrder.nativeOrder()

/**
 * The logistic loss of a row of scores, every one against the label nought: for each `z = row[j] + shift[j]` under [n],
 * `softplus(z)` is summed into `sums[0]`, and `σ(z)·scale` — the slope of the loss, scaled — is written over `row[j]`
 * and summed into `sums[1]`. In single precision, the exponent and the logarithm of a lane's worth at once: a large
 * universe makes tens of millions of these a step of a fitting.
 */
fun Simd.logisticRow(row: FloatArray, shift: FloatArray, n: Int, scale: Float, sums: FloatArray) {
    val seg = MemorySegment.ofArray(row)
    logistic(seg, 0, shift, null, null, n, scale, seg, 0, sums)
}

/**
 * [logisticRow] of the row of [n] scores at [at] of [m], in place: read and written where they lie, not copied out and
 * back.
 */
fun Simd.logisticRow(m: Floats, at: Int, shift: FloatArray, n: Int, scale: Float, sums: FloatArray) =
    logistic(m.seg, at, shift, null, null, n, scale, m.seg, at, sums)

/**
 * The weighted logistic loss of a row of scores against its labels: for each `z = row[j] + shift[j]` under [n],
 * `w[j]·(softplus(z) − y[j]·z)` is summed into `sums[0]`, and the slope `w[j]·(σ(z) − y[j])·scale` is written into
 * `slopes[j]` and summed into `sums[1]`. A pair of weight nought adds nothing and has the slope nought.
 */
fun Simd.weightedLogisticRow(
    row: FloatArray,
    shift: FloatArray,
    w: FloatArray,
    y: FloatArray,
    n: Int,
    scale: Float,
    slopes: FloatArray,
    sums: FloatArray,
) = logistic(MemorySegment.ofArray(row), 0, shift, w, y, n, scale, MemorySegment.ofArray(slopes), 0, sums)

private fun logistic(
    seg: MemorySegment,
    at: Int,
    shift: FloatArray,
    w: FloatArray?,
    y: FloatArray?,
    n: Int,
    scale: Float,
    out: MemorySegment,
    outAt: Int,
    sums: FloatArray,
) {
    val bytes = Float.SIZE_BYTES.toLong()
    var loss = 0f
    var slope = 0f
    if (Simd.on) {
        val sp = FloatVector.SPECIES_PREFERRED
        val width = sp.length()
        val one = FloatVector.broadcast(sp, 1f)
        var lossAcc = FloatVector.zero(sp)
        var slopeAcc = FloatVector.zero(sp)
        val full = sp.loopBound(n)
        var j = 0
        while (j < full) {
            val z =
                FloatVector.fromMemorySegment(sp, seg, (at + j) * bytes, ORDER).add(FloatVector.fromArray(sp, shift, j))
            val label = y?.let { FloatVector.fromArray(sp, it, j) }
            val weight = w?.let { FloatVector.fromArray(sp, it, j) }
            lane(z, label, weight, one, scale) { l, d ->
                d.intoMemorySegment(out, (outAt + j) * bytes, ORDER)
                lossAcc = lossAcc.add(l)
                slopeAcc = slopeAcc.add(d)
            }
            j += width
        }
        loss = lossAcc.reduceLanes(VectorOperators.ADD)
        slope = slopeAcc.reduceLanes(VectorOperators.ADD)
        // The last lanes one at a time: no scratch made for them — a row of a few values is all
        // tail, and a fitting takes millions of rows a step
        for (k in full until n) {
            loss += scalar(seg, at, k, shift, w, y, scale, out, outAt)
            slope += out.getAtIndex(ValueLayout.JAVA_FLOAT, (outAt + k).toLong())
        }
    } else
        for (j in 0 until n) {
            loss += scalar(seg, at, j, shift, w, y, scale, out, outAt)
            slope += out.getAtIndex(ValueLayout.JAVA_FLOAT, (outAt + j).toLong())
        }
    sums[0] += loss
    sums[1] += slope
}

/**
 * The score at `at + j` one number at a time — [logistic] without the wide registers: its slope written at `outAt + j`
 * (read in place first: the score and the slope may be one number), its loss answered.
 */
private fun scalar(
    seg: MemorySegment,
    at: Int,
    j: Int,
    shift: FloatArray,
    w: FloatArray?,
    y: FloatArray?,
    scale: Float,
    out: MemorySegment,
    outAt: Int,
): Float {
    val z = seg.getAtIndex(ValueLayout.JAVA_FLOAT, (at + j).toLong()) + shift[j]
    val e = kotlin.math.exp(-kotlin.math.abs(z))
    val label = y?.get(j) ?: 0f
    val weight = w?.get(j) ?: 1f
    out.setAtIndex(
        ValueLayout.JAVA_FLOAT,
        (outAt + j).toLong(),
        weight * ((if (z >= 0) 1 / (1 + e) else e / (1 + e)) - label) * scale,
    )
    return weight * (kotlin.math.max(z, 0f) + kotlin.math.ln1p(e) - label * z)
}

/**
 * The loss and the slope of a lane's worth of scores [z] against the [label]s (nought when none), each pair weighed by
 * its [weight] (one when none): softplus and sigmoid from one exponent, handed to [take].
 */
private inline fun lane(
    z: FloatVector,
    label: FloatVector?,
    weight: FloatVector?,
    one: FloatVector,
    scale: Float,
    take: (loss: FloatVector, slope: FloatVector) -> Unit,
) {
    val e = z.abs().neg().lanewise(VectorOperators.EXP)
    val inverse = one.div(one.add(e))
    var sigmoid = e.mul(inverse).blend(inverse, z.compare(VectorOperators.GE, 0f))
    var l = z.max(0f).add(e.lanewise(VectorOperators.LOG1P))
    if (label != null) {
        l = l.sub(label.mul(z))
        sigmoid = sigmoid.sub(label)
    }
    if (weight != null) {
        l = l.mul(weight)
        sigmoid = sigmoid.mul(weight)
    }
    take(l, sigmoid.mul(scale))
}
