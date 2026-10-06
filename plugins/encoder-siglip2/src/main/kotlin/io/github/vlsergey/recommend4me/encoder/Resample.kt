package io.github.vlsergey.recommend4me.encoder

import kotlin.math.abs
import kotlin.math.max

/**
 * The triangle resampler as PIL and torch do it, ported from local-ai-chroma-enf-lora's
 * `images/Resample.kt`: for a shrink the filter widens with the factor (antialiasing), for a
 * stretch it stays one pixel wide, and every output pixel is a normalised weighted sum of its span.
 *
 * Two flavours, because SigLIP2 NaFlex wants two exact answers: the picture resized as the image
 * processor resizes it (8-bit, PIL's fixed-point rounding), and the positional grid resized as
 * the model resizes it (float, torch's antialiased bilinear interpolation).
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

    /** PIL's normalize_coeffs_8bpc: the weights as fixed-point integers. */
    private fun fixed(w: DoubleArray): IntArray = IntArray(w.size) { x ->
        val v = w[x] * (1 shl PRECISION)
        if (w[x] < 0) (v - 0.5).toInt() else (v + 0.5).toInt()
    }

    /**
     * An 8-bit interleaved picture (`ch` channels) resized to outW×outH as PIL's
     * Image.resize(BILINEAR) does it: the horizontal pass, then the vertical, each rounded to bytes.
     */
    fun u8(src: ByteArray, w: Int, h: Int, ch: Int, outW: Int, outH: Int): ByteArray {
        var cur = src
        var cw = w
        if (outW != w) {
            val ws = weights(w, outW)
            val out = ByteArray(outW * h * ch)
            val ks = Array(outW) { fixed(ws.k[it]) }
            for (y in 0 until h) {
                val row = y * w * ch
                for (xx in 0 until outW) {
                    val xmin = ws.bounds[xx * 2]
                    val xmax = ws.bounds[xx * 2 + 1]
                    val k = ks[xx]
                    for (c in 0 until ch) {
                        var ss = 1L shl (PRECISION - 1)
                        for (x in 0 until xmax) ss += (cur[row + (x + xmin) * ch + c].toInt() and 0xff).toLong() * k[x]
                        out[(y * outW + xx) * ch + c] = clip8(ss shr PRECISION)
                    }
                }
            }
            cur = out
            cw = outW
        }
        if (outH != h) {
            val ws = weights(h, outH)
            val out = ByteArray(cw * outH * ch)
            val ks = Array(outH) { fixed(ws.k[it]) }
            for (yy in 0 until outH) {
                val ymin = ws.bounds[yy * 2]
                val ymax = ws.bounds[yy * 2 + 1]
                val k = ks[yy]
                for (x in 0 until cw) for (c in 0 until ch) {
                    var ss = 1L shl (PRECISION - 1)
                    for (y in 0 until ymax) ss += (cur[((y + ymin) * cw + x) * ch + c].toInt() and 0xff).toLong() * k[y]
                    out[(yy * cw + x) * ch + c] = clip8(ss shr PRECISION)
                }
            }
            cur = out
        }
        return cur
    }

    private fun clip8(v: Long): Byte = (if (v >= 255) 255 else if (v <= 0) 0 else v.toInt()).toByte()

    /**
     * Float planes (`ch` planes of h×w) resized to outW×outH as torch's antialiased bilinear
     * interpolation does it — the same weights, float arithmetic, no rounding between the passes.
     */
    fun f32(src: FloatArray, w: Int, h: Int, ch: Int, outW: Int, outH: Int): FloatArray {
        var cur = src
        var cw = w
        if (outW != w) {
            val ws = weights(w, outW)
            val out = FloatArray(ch * h * outW)
            for (c in 0 until ch) for (y in 0 until h) {
                val row = c * h * w + y * w
                for (xx in 0 until outW) {
                    val xmin = ws.bounds[xx * 2]
                    val xmax = ws.bounds[xx * 2 + 1]
                    val k = ws.k[xx]
                    var s = 0.0
                    for (x in 0 until xmax) s += cur[row + x + xmin] * k[x]
                    out[c * h * outW + y * outW + xx] = s.toFloat()
                }
            }
            cur = out
            cw = outW
        }
        if (outH != h) {
            val ws = weights(h, outH)
            val out = FloatArray(ch * outH * cw)
            for (c in 0 until ch) for (yy in 0 until outH) {
                val ymin = ws.bounds[yy * 2]
                val ymax = ws.bounds[yy * 2 + 1]
                val k = ws.k[yy]
                for (x in 0 until cw) {
                    var s = 0.0
                    for (y in 0 until ymax) s += cur[c * h * cw + (y + ymin) * cw + x] * k[y]
                    out[c * outH * cw + yy * cw + x] = s.toFloat()
                }
            }
            cur = out
        }
        return cur
    }
}
