package io.github.vlsergey.recommend4me.encoder

import ai.onnxruntime.OnnxTensor
import io.github.vlsergey.recommend4me.onnx.Onnx
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.file.Files
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The image tower of SigLIP2 NaFlex (google/siglip2-so400m-patch16-naflex), exported by tools/export_siglip2_naflex.py
 * into the models folder.
 *
 * THE PICTURE KEEPS ITS PROPORTIONS. It is resized, aspect ratio kept, to the largest size whose 16×16 patches number
 * at most [MAX_PATCHES], and fed as a sequence of patches with a mask: a 1600×400 banner becomes 32×8 patches, not a
 * squeezed square.
 *
 * WHAT THE GRAPH CANNOT DO IS DONE HERE: the positional embeddings are a 16×16 grid that the model resizes to every
 * picture's patch grid (antialiased bilinear) — a resize whose size is the data. The grid is read from the export,
 * resized by [Resample.f32] and handed to the graph.
 *
 * Preparation follows the image processor: PIL's bilinear resize ([Resample.u8]), pixels to −1…1, patches flattened as
 * (y, x, rgb).
 */
class Siglip2Encoder(private val onnx: Onnx) : ImageEncoder {

    /** Spatial size of a prepared picture in patches, and its patches, (y, x, rgb), −1…1. */
    class Prepared(val rows: Int, val cols: Int, val patches: FloatArray) : PreparedPicture {
        val count: Int
            get() = rows * cols
    }

    /** The positional grid as DIM planes of GRID×GRID, ready for [Resample.f32]. */
    private val grid: FloatArray by lazy {
        val p = onnx.path(POSITIONS)
        check(Files.isRegularFile(p)) { "The positional grid of SigLIP2 is missing: ${p.toAbsolutePath()}" }
        val raw = FloatArray(GRID * GRID * DIM)
        ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(raw)
        // Stored as (y·GRID + x, d); the resampler wants (d, y, x)
        FloatArray(raw.size) { i ->
            val d = i / (GRID * GRID)
            val yx = i % (GRID * GRID)
            raw[yx * DIM + d]
        }
    }

    override val id = ENCODER
    override val dim = DIM

    override fun ready(): Boolean = onnx.has(MODEL) && onnx.has(POSITIONS)

    override fun prepare(image: BufferedImage): Prepared {
        val (h, w) = sizeFor(image.height, image.width)
        val rgb = ByteArray(image.width * image.height * CHANNELS)
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        for (i in argb.indices) {
            rgb[i * CHANNELS] = (argb[i] shr RED_SHIFT).toByte()
            rgb[i * CHANNELS + 1] = (argb[i] shr GREEN_SHIFT).toByte()
            rgb[i * CHANNELS + 2] = argb[i].toByte()
        }
        val resized = Resample.u8(rgb, image.width, image.height, CHANNELS, w, h)
        val rows = h / PATCH
        val cols = w / PATCH
        val patches = FloatArray(rows * cols * PATCH_VALUES)
        for (pr in 0 until rows) for (pc in 0 until cols) patch(
            resized,
            w,
            pr,
            pc,
            patches,
            (pr * cols + pc) * PATCH_VALUES,
        )
        return Prepared(rows, cols, patches)
    }

    /**
     * The patch of row [pr] and column [pc] of the picture [resized] of width [w], its values put in [patches] from
     * [at] on, pixel by pixel, a channel each, of 0..255 made −1..1.
     */
    private fun patch(resized: ByteArray, w: Int, pr: Int, pc: Int, patches: FloatArray, at: Int) {
        var to = at
        for (y in 0 until PATCH) for (x in 0 until PATCH) {
            val pixel = ((pr * PATCH + y) * w + pc * PATCH + x) * CHANNELS
            for (c in 0 until CHANNELS) patches[to++] = (resized[pixel + c].toInt() and UNSIGNED_BYTE) / HALF_BYTE - 1f
        }
    }

    /** Unit vectors of prepared pictures, [BATCH] at a time on half the cores, [GPU_BATCH] on the card. */
    override fun encode(pictures: List<PreparedPicture>): List<FloatArray> = embed(pictures.map { it as Prepared })

    fun embed(pictures: List<Prepared>): List<FloatArray> {
        val out = ArrayList<FloatArray>(pictures.size)
        onnx.session(MODEL, THREADS)
        val size = if (onnx.onGpu(MODEL)) GPU_BATCH else BATCH
        // A batch is padded to its longest picture: similar sizes go together
        val order = pictures.indices.sortedBy { pictures[it].count }
        val vectors = arrayOfNulls<FloatArray>(pictures.size)
        for (batch in order.chunked(size)) {
            val n = batch.maxOf { pictures[it].count }
            val b = batch.size
            val patches = FloatArray(b * n * PATCH_VALUES)
            val positions = FloatArray(b * n * DIM)
            val mask = LongArray(b * n)
            batch.forEachIndexed { r, i ->
                val p = pictures[i]
                System.arraycopy(p.patches, 0, patches, r * n * PATCH_VALUES, p.patches.size)
                val planes = Resample.f32(grid, GRID, GRID, DIM, p.cols, p.rows)
                val cells = p.rows * p.cols
                for (cell in 0 until n) {
                    // Padding rows repeat the first position, as the model pads them; the mask hides them anyway
                    val source = if (cell < cells) cell else 0
                    val base = (r * n + cell) * DIM
                    for (d in 0 until DIM) positions[base + d] = planes[d * cells + source]
                    if (cell < cells) mask[r * n + cell] = 1
                }
            }
            val result =
                onnx.run(
                    MODEL,
                    mapOf(
                        "patches" to
                            OnnxTensor.createTensor(
                                onnx.env,
                                FloatBuffer.wrap(patches),
                                longArrayOf(b.toLong(), n.toLong(), PATCH_VALUES.toLong()),
                            ),
                        "positions" to
                            OnnxTensor.createTensor(
                                onnx.env,
                                FloatBuffer.wrap(positions),
                                longArrayOf(b.toLong(), n.toLong(), DIM.toLong()),
                            ),
                        "mask" to
                            OnnxTensor.createTensor(
                                onnx.env,
                                LongBuffer.wrap(mask),
                                longArrayOf(b.toLong(), n.toLong()),
                            ),
                    ),
                )
            batch.forEachIndexed { r, i -> vectors[i] = unit(result[r]) }
        }
        vectors.forEach { out += it!! }
        return out
    }

    private fun unit(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x.toDouble() * x
        n = sqrt(n)
        return if (n > LEAST_NORM) FloatArray(v.size) { (v[it] / n).toFloat() } else v
    }

    companion object {
        /** Stored with every vector: vectors of another model are of another space. */
        const val ENCODER = "siglip2-so400m-patch16-naflex-256"
        const val MODEL = "siglip2_naflex.onnx"
        const val POSITIONS = "siglip2_naflex_positions.bin"
        const val DIM = 1152
        const val PATCH = 16
        const val MAX_PATCHES = 256
        private const val GRID = 16

        /** The channels of a pixel: red, green, blue. */
        private const val CHANNELS = 3

        /** Where the red and the green of a pixel stand in its ARGB bits. */
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8

        /** A byte's bits read as a number of 0..255, not of its sign. */
        private const val UNSIGNED_BYTE = 0xff

        /** Half the range of a channel: 0..255 divided by it and less one is −1..1, as the model was trained. */
        private const val HALF_BYTE = 127.5f

        /** The least length a vector is made of unit length from: a shorter one has no direction to keep. */
        private const val LEAST_NORM = 1e-9
        private const val PATCH_VALUES = PATCH * PATCH * CHANNELS
        private const val BATCH = 8

        /** A card is fed many pictures at once: one run costs it little more than one picture. */
        private const val GPU_BATCH = 32

        /** Half the cores: the machine stays usable while the catalogue is analysed for days. */
        val THREADS = max(1, Runtime.getRuntime().availableProcessors() / 2)

        /**
         * The image processor's binary search for the largest scale whose patches, each side rounded up to a multiple
         * of [PATCH], number at most [MAX_PATCHES]. Returns (height, width).
         */
        fun sizeFor(height: Int, width: Int, eps: Double = 1e-5): Pair<Int, Int> {
            fun scaled(scale: Double, size: Int): Int = max(PATCH, (ceil(size * scale / PATCH) * PATCH).toInt())
            var lo = eps / 10
            var hi = 100.0
            while (hi - lo >= eps) {
                val scale = (lo + hi) / 2
                val patches = (scaled(scale, height) / PATCH) * (scaled(scale, width) / PATCH)
                if (patches <= MAX_PATCHES) lo = scale else hi = scale
            }
            return scaled(lo, height) to scaled(lo, width)
        }
    }
}
