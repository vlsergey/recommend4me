package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.vector.Vectors
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.CompressionOptions
import org.rocksdb.CompressionType
import org.rocksdb.DBOptions
import org.rocksdb.RocksDB

/** A paragraph's vector and the hash of its text, as kept ([PartStore.paragraphs]). */
class KeptParagraph(val vector: FloatArray, val hash: String)

/**
 * THE TEXTS OF THE PARTS OF ONE SOURCE'S WORKS AND THEIR VECTORS, KEPT OUTSIDE THE DATABASE (user, 2026-11-17): a
 * million works of a dump are tens of gigabytes of text and hundreds of their vectors, which a database file of H2
 * would hold badly — slow to compact and to copy, all of it lost with a file broken. Kept by RocksDB — a library's
 * store of keys and values, no format of our own — in the source's folder ([FOLDER]), every part by its item and its
 * id:
 * - its text in [TEXT], compressed by zstd with a dictionary trained on the texts themselves, the same words of every
 *   chapter compressed once;
 * - the vectors of its windows in [WINDOWS] and of its paragraphs with the hashes of their texts in [PARAGRAPHS], half
 *   precision, as they are: numbers do not compress.
 *
 * What a part has — whether its text is kept, its windows encoded and by what — the database says ([PartRepository]);
 * this holds the bytes. A value written goes to the store's log first: a stop between keeps it, or loses it whole.
 */
class PartStore(folder: Path) : AutoCloseable {
    private val handles = ArrayList<ColumnFamilyHandle>()
    private val options = DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
    private val textOptions =
        ColumnFamilyOptions()
            .setCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setCompressionOptions(
                CompressionOptions().setMaxDictBytes(DICTIONARY_BYTES).setZStdMaxTrainBytes(DICTIONARY_TRAINING_BYTES)
            )
            .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setBottommostCompressionOptions(
                CompressionOptions()
                    .setEnabled(true)
                    .setMaxDictBytes(DICTIONARY_BYTES)
                    .setZStdMaxTrainBytes(DICTIONARY_TRAINING_BYTES)
            )
    private val vectorOptions = ColumnFamilyOptions().setCompressionType(CompressionType.NO_COMPRESSION)
    private val db: RocksDB

    init {
        RocksDB.loadLibrary()
        val path = Files.createDirectories(folder.resolve(FOLDER))
        db =
            RocksDB.open(
                options,
                path.toString(),
                listOf(
                    ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, vectorOptions),
                    ColumnFamilyDescriptor(TEXT.toByteArray(), textOptions),
                    ColumnFamilyDescriptor(WINDOWS.toByteArray(), vectorOptions),
                    ColumnFamilyDescriptor(PARAGRAPHS.toByteArray(), vectorOptions),
                ),
                handles,
            )
    }

    private val texts = handles[1]
    private val windows = handles[2]
    private val paragraphs = handles[3]

    fun text(itemId: String, partId: String): String? = db.get(texts, key(itemId, partId))?.toString(Charsets.UTF_8)

    /** The texts of the parts [partIds] of the item, by part; a part of none left out. */
    fun texts(itemId: String, partIds: Collection<String>): Map<String, String> =
        multi(texts, itemId, partIds) { it.toString(Charsets.UTF_8) }

    /**
     * Every text kept, in the order of the keys — the parts of an item one after another —, read as one pass of the
     * store: its item and its UTF-8 bytes.
     */
    fun forEachText(action: (itemId: String, bytes: ByteArray) -> Unit) {
        db.newIterator(texts).use { cursor ->
            cursor.seekToFirst()
            while (cursor.isValid) {
                val key = cursor.key().toString(Charsets.UTF_8)
                action(key.substringBefore(SEPARATOR), cursor.value())
                cursor.next()
            }
        }
    }

    fun putText(itemId: String, partId: String, text: String) =
        db.put(texts, key(itemId, partId), text.toByteArray(Charsets.UTF_8))

    /**
     * The vectors of the windows of the parts [partIds] of the item in their order, by part; a part of none left out.
     */
    fun windows(itemId: String, partIds: Collection<String>): Map<String, List<FloatArray>> =
        multi(windows, itemId, partIds, ::vectorsOf)

    fun putWindows(itemId: String, partId: String, vectors: List<FloatArray>) =
        db.put(windows, key(itemId, partId), bytesOf(vectors))

    /**
     * The paragraphs of the parts [partIds] of the item, by part, each in their order with its vector and its text's
     * hash, null one not encoded; a part of none left out.
     */
    fun paragraphs(itemId: String, partIds: Collection<String>): Map<String, List<KeptParagraph?>> =
        multi(paragraphs, itemId, partIds, ::paragraphsOf)

    fun putParagraphs(itemId: String, partId: String, vectors: List<FloatArray?>, hashes: List<String>) {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(vectors.size)
            vectors.forEachIndexed { i, v ->
                d.writeBoolean(v != null)
                if (v != null) {
                    d.writeUTF(hashes[i])
                    val half = Vectors.half(v)
                    d.writeInt(half.size)
                    d.write(half)
                }
            }
        }
        db.put(paragraphs, key(itemId, partId), out.toByteArray())
    }

    /** Everything of the item gone: its texts and their vectors, as the work is removed. */
    fun forget(itemId: String) {
        val from = (itemId + SEPARATOR).toByteArray(Charsets.UTF_8)
        val to = (itemId + AFTER_SEPARATOR).toByteArray(Charsets.UTF_8)
        listOf(texts, windows, paragraphs).forEach { db.deleteRange(it, from, to) }
    }

    override fun close() {
        handles.forEach(ColumnFamilyHandle::close)
        db.close()
        listOf(options, textOptions, vectorOptions).forEach { it.close() }
    }

    private fun <T> multi(
        family: ColumnFamilyHandle,
        itemId: String,
        partIds: Collection<String>,
        read: (ByteArray) -> T,
    ): Map<String, T> {
        if (partIds.isEmpty()) return emptyMap()
        val ids = partIds.distinct()
        val values = db.multiGetAsList(List(ids.size) { family }, ids.map { key(itemId, it) })
        return ids.indices.mapNotNull { i -> values[i]?.let { ids[i] to read(it) } }.toMap()
    }

    private companion object {
        /** The store's folder in the source's own. */
        const val FOLDER = "parts"

        const val TEXT = "text"
        const val WINDOWS = "windows"
        const val PARAGRAPHS = "paragraphs"

        /** Between the item and the part in a key: no id has it. */
        const val SEPARATOR = '\u0000'

        /** Past every key of an item: the separator's next. */
        const val AFTER_SEPARATOR = '\u0001'

        /** The dictionary zstd compresses the texts of a file of the store with, trained on a hundred times as much. */
        const val DICTIONARY_BYTES = 64 * 1024
        const val DICTIONARY_TRAINING_BYTES = 100 * DICTIONARY_BYTES

        fun key(itemId: String, partId: String) = (itemId + SEPARATOR + partId).toByteArray(Charsets.UTF_8)

        fun bytesOf(vectors: List<FloatArray>): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                d.writeInt(vectors.size)
                vectors.forEach { v ->
                    val half = Vectors.half(v)
                    d.writeInt(half.size)
                    d.write(half)
                }
            }
            return out.toByteArray()
        }

        fun vectorsOf(bytes: ByteArray): List<FloatArray> =
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                List(d.readInt()) { Vectors.fromHalf(ByteArray(d.readInt()).also(d::readFully)) }
            }

        fun paragraphsOf(bytes: ByteArray): List<KeptParagraph?> =
            DataInputStream(ByteArrayInputStream(bytes)).use { d ->
                List(d.readInt()) {
                    if (!d.readBoolean()) null
                    else {
                        val hash = d.readUTF()
                        KeptParagraph(Vectors.fromHalf(ByteArray(d.readInt()).also(d::readFully)), hash)
                    }
                }
            }
    }
}
