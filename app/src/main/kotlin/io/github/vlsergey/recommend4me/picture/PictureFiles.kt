package io.github.vlsergey.recommend4me.picture

import org.glavo.avif.AvifImageReader
import org.glavo.avif.AvifPixelFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/**
 * The picture files of one source's items, in `<source folder>/images/<item>/`:
 * `<position>-preview.<ext>` always, `<position>.<ext>` (the original) only for works graded "can
 * be played" or more. Files on disk, not in the database: the originals run to a hundred
 * gigabytes, and the database files are copied whole.
 */
class PictureFiles(sourceFolder: Path) {

    private val dir: Path = sourceFolder.resolve("images")

    fun path(relative: String): Path = dir.resolve(relative)

    /** Writes the bytes as `<item>/<name>.<ext>`, atomically; returns the relative path. */
    fun save(itemId: String, name: String, bytes: ByteArray, contentType: String): String {
        val relative = "${folderOf(itemId)}/$name.${extension(contentType)}"
        val target = dir.resolve(relative)
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return relative
    }

    fun delete(relatives: Collection<String>) {
        relatives.forEach { Files.deleteIfExists(dir.resolve(it)) }
    }

    companion object {
        init {
            // An application started by a launcher hides the WebP reader from ImageIO's own look-up
            ImageIO.scanForPlugins()
        }

        private val EXTENSIONS = mapOf(
            "image/webp" to "webp", "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/avif" to "avif",
        )

        /** An item's id as a folder name: what a file system would not take is replaced. */
        fun folderOf(itemId: String): String = itemId.replace(Regex("[^A-Za-z0-9._-]"), "_")

        fun extension(contentType: String) = EXTENSIONS[contentType] ?: "img"

        fun contentType(file: String) =
            EXTENSIONS.entries.firstOrNull { file.endsWith("." + it.value) }?.key ?: "application/octet-stream"

        /**
         * The picture, its first frame for an animation; null when no reader knows the format.
         * AVIF goes to javif (pure Java AV1): ImageIO has no reader for it.
         */
        fun decode(bytes: ByteArray): BufferedImage? =
            if (isAvif(bytes)) decodeAvif(bytes) else ImageIO.read(ByteArrayInputStream(bytes))

        /** An ISO-BMFF file whose `ftyp` brand is `avif` (still) or `avis` (sequence). */
        fun isAvif(bytes: ByteArray): Boolean {
            if (bytes.size < 12 || String(bytes, 4, 4, Charsets.ISO_8859_1) != "ftyp") return false
            val brand = String(bytes, 8, 4, Charsets.ISO_8859_1)
            return brand == "avif" || brand == "avis"
        }

        private fun decodeAvif(bytes: ByteArray): BufferedImage = AvifImageReader.open(bytes).use { reader ->
            val frame = reader.readFrame(0)
            val (w, h) = frame.width() to frame.height()
            val argb = when (frame.pixelFormat()) {
                AvifPixelFormat.ARGB_8888 -> frame.intPixels()
                // 10 and 12 bits come as 16 bits a channel: the top byte of each is enough here
                else -> frame.longPixels().let { wide ->
                    IntArray(wide.size) { i ->
                        val p = wide[i]
                        fun top(shift: Int) = (p ushr shift).toInt() and 0xFF
                        (top(56) shl 24) or (top(40) shl 16) or (top(24) shl 8) or top(8)
                    }
                }
            }
            val type = if (reader.info().alphaPresent()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
            BufferedImage(w, h, type).apply { setRGB(0, 0, w, h, argb, 0, w) }
        }
    }
}
