package io.github.vlsergey.recommend4me.encoder

import kotlin.math.abs
import kotlin.math.max

/**
 * The triangle resampler as PIL and torch do it, ported from local-ai-chroma-enf-lora's `images/Resample.kt`: for a
 * shrink the filter widens with the factor (antialiasing), for a stretch it stays one pixel wide, and every output
 * pixel is a normalised weighted sum of its span.
 *
 * Two flavours, because SigLIP2 NaFlex wants two exact answers: the picture resized as the image processor resizes it
 * (8-bit, PIL's fixed-point rounding), and the positional grid resized as the model resizes it (float, torch's
 * antialiased bilinear interpolation).
 */
object Resample {
    private const val PRECISION = 22

    private class Weights(val bounds: IntArray, val k: Array<DoubleArray>)

    /** PIL's precompute_coeffs for the bilinear filter (support 1). */
    private fun weights(inSize: Int, outSize: Int): Weights {
        val scale = inSize.toDouble() / outSize
        val filterscale = max(scale, 1.0)
        val support = 1.0 * filterscale
        val ss = 1.0 / filterscale
        val bounds = IntArray(outSize * 2)
        val k = Array(outSize) { DoubleArray(0) }
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin
            val w = DoubleArray(max(xmax, 0))
            var total = 0.0
            for (x in 0 until xmax) {
                val d = abs((x + xmin - center + 0.5) * ss)
                val v = if (d < 1.0) 1.0 - d else 0.0
                w[x] = v
                total += v
            }
            if (total != 0.0) for (x in 0 until xmax) w[x] /= total
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
            k[xx] = w
        }
        return Weights(bounds, k)
    }

    /** PIL's normalize_coeffs_8bpc: the weights as fixed-point integers, rounded half away from nought. */
    private fun fixed(w: DoubleArray): IntArray =
        IntArray(w.size) { x ->
            val v = w[x] * (1 shl PRECISION)
            if (w[x] < 0) (v - HALF).toInt() else (v + HALF).toInt()
        }

    /**
     * An 8-bit interleaved picture (`ch` channels) resized to outW×outH as PIL's Image.resize(BILINEAR) does it: the
     * horizontal pass, then the vertical, each rounded to bytes.
     */
    fun u8(src: ByteArray, w: Int, h: Int, ch: Int, outW: Int, outH: Int): ByteArray {
        val wide = if (outW != w) across8(src, w, h, ch, outW) else src
        return if (outH != h) down8(wide, if (outW != w) outW else w, h, ch, outH) else wide
    }

    /** The horizontal pass of [u8]: every row of the picture [src] of w×h resized to [outW], rounded to bytes. */
    private fun across8(src: ByteArray, w: Int, h: Int, ch: Int, outW: Int): ByteArray {
        val ws = weights(w, outW)
        val out = ByteArray(outW * h * ch)
        val ks = Array(outW) { fixed(ws.k[it]) }
        for (y in 0 until h) {
            val row = y * w * ch
            for (xx in 0 until outW) {
                val xmin = ws.bounds[xx * 2]
                val xmax = ws.bounds[xx * 2 + 1]
                for (c in 0 until ch) out[(y * outW + xx) * ch + c] =
                    clip8(sum8(src, row + xmin * ch + c, ch, xmax, ks[xx]) shr PRECISION)
            }
        }
        return out
    }

    /** The vertical pass of [u8]: every column of the picture [src] of cw×h resized to [outH], rounded to bytes. */
    private fun down8(src: ByteArray, cw: Int, h: Int, ch: Int, outH: Int): ByteArray {
        val ws = weights(h, outH)
        val out = ByteArray(cw * outH * ch)
        val ks = Array(outH) { fixed(ws.k[it]) }
        for (yy in 0 until outH) {
            val ymin = ws.bounds[yy * 2]
            val ymax = ws.bounds[yy * 2 + 1]
            for (x in 0 until cw) for (c in 0 until ch) {
                out[(yy * cw + x) * ch + c] =
                    clip8(sum8(src, (ymin * cw + x) * ch + c, cw * ch, ymax, ks[yy]) shr PRECISION)
            }
        }
        return out
    }

    /**
     * The fixed-point weighted sum of [count] bytes of [src], the first at [from], each [step] after the one before, by
     * the weights [k]; half a unit added, for the shift to round.
     */
    private fun sum8(src: ByteArray, from: Int, step: Int, count: Int, k: IntArray): Long {
        var ss = 1L shl (PRECISION - 1)
        for (x in 0 until count) ss += (src[from + x * step].toInt() and UNSIGNED_BYTE).toLong() * k[x]
        return ss
    }

    private fun clip8(v: Long): Byte = (if (v >= BYTE_MAX) BYTE_MAX else if (v <= 0) 0 else v.toInt()).toByte()

    /**
     * Float planes (`ch` planes of h×w) resized to outW×outH as torch's antialiased bilinear interpolation does it —
     * the same weights, float arithmetic, no rounding between the passes.
     */
    fun f32(src: FloatArray, w: Int, h: Int, ch: Int, outW: Int, outH: Int): FloatArray {
        val wide = if (outW != w) across32(src, w, h, ch, outW) else src
        return if (outH != h) down32(wide, if (outW != w) outW else w, h, ch, outH) else wide
    }

    /** The horizontal pass of [f32]: every row of every plane of w×h of [src] resized to [outW]. */
    private fun across32(src: FloatArray, w: Int, h: Int, ch: Int, outW: Int): FloatArray {
        val ws = weights(w, outW)
        val out = FloatArray(ch * h * outW)
        for (c in 0 until ch) for (y in 0 until h) {
            val row = c * h * w + y * w
            for (xx in 0 until outW) {
                val xmin = ws.bounds[xx * 2]
                val xmax = ws.bounds[xx * 2 + 1]
                out[c * h * outW + y * outW + xx] = sum32(src, row + xmin, 1, xmax, ws.k[xx]).toFloat()
            }
        }
        return out
    }

    /** The vertical pass of [f32]: every column of every plane of cw×h of [src] resized to [outH]. */
    private fun down32(src: FloatArray, cw: Int, h: Int, ch: Int, outH: Int): FloatArray {
        val ws = weights(h, outH)
        val out = FloatArray(ch * outH * cw)
        for (c in 0 until ch) for (yy in 0 until outH) {
            val ymin = ws.bounds[yy * 2]
            val ymax = ws.bounds[yy * 2 + 1]
            for (x in 0 until cw) out[c * outH * cw + yy * cw + x] =
                sum32(src, c * h * cw + ymin * cw + x, cw, ymax, ws.k[yy]).toFloat()
        }
        return out
    }

    /** The weighted sum of [count] floats of [src], the first at [from], each [step] after the one before, by [k]. */
    private fun sum32(src: FloatArray, from: Int, step: Int, count: Int, k: DoubleArray): Double {
        var s = 0.0
        for (x in 0 until count) s += src[from + x * step] * k[x]
        return s
    }

    /** Half a unit: the rounding of a weight to the nearest fixed-point integer. */
    private const val HALF = 0.5

    /** A byte's bits read as a number of 0..255, not of its sign. */
    private const val UNSIGNED_BYTE = 0xff

    /** The largest value of an 8-bit channel. */
    private const val BYTE_MAX = 255
}
