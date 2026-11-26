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
 *   precision, as they are: numbers do not compress. Kept apart from their keys, in files shared by thousands of parts;
 * - the codes of the vectors of its pieces in [PIECES], 32 bytes a piece — and of the texts of the item's fields by the
 *   key `text:<field>` in the place of a part's id;
 * - the full-text vectors of its windows in [FULL_TEXT] ([io.github.vlsergey.recommend4me.fulltext.FullTextVectors]),
 *   of its text fields' too: what a search reads whole, in the order of the keys — in files of their own, not apart
 *   from their keys, the store's files of tens of megabytes read one after another.
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
    // The vectors, written once and never changed, kept apart from their keys (RocksDB's integrated BlobDB): a part's
    // tens of kilobytes written once into files shared by thousands of parts, and the compaction moving the keys and
    // their references alone — not every part's vectors again at every level, 90-380 MB a compaction of ficbook's store
    // on the disk of the data (owner, 2026-11-25: "but not a file a chapter")
    private val vectorOptions =
        ColumnFamilyOptions()
            .setCompressionType(CompressionType.NO_COMPRESSION)
            .setEnableBlobFiles(true)
            .setMinBlobSize(BLOB_LEAST)
            .setBlobFileSize(BLOB_FILE)
            .setBlobCompressionType(CompressionType.NO_COMPRESSION)
            .setEnableBlobGarbageCollection(true)
    // The full-text vectors: read whole by every search, a part's few kilobytes beside its key, numbers that do not
    // compress
    private val fullTextOptions = ColumnFamilyOptions().setCompressionType(CompressionType.NO_COMPRESSION)
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
                    ColumnFamilyDescriptor(PIECES.toByteArray(), vectorOptions),
                    ColumnFamilyDescriptor(FULL_TEXT.toByteArray(), fullTextOptions),
                ),
                handles,
            )
    }

    private val texts = handles[1]
    private val windows = handles[2]
    private val paragraphs = handles[3]
    private val piecesFamily = handles[4]

    /**
     * The codes of the pieces of every text of an item — a part's id, or a text field's ([PieceRepository]) — one after
     * another ([io.github.vlsergey.recommend4me.piece.PieceCodebook]).
     */
    val pieces = PartValues(db, piecesFamily)

    private val fullTextFamily = handles[5]

    /**
     * The full-text vectors of the windows of every text of an item, by its id as [pieces]
     * ([FullTextVectorRepository]).
     */
    val fullText = PartValues(db, fullTextFamily)

    fun text(itemId: String, partId: String): String? = db.get(texts, key(itemId, partId))?.toString(Charsets.UTF_8)

    /** The texts of the parts [partIds] of the item, by part; a part of none left out. */
    fun texts(itemId: String, partIds: Collection<String>): Map<String, String> =
        multiGet(db, texts, itemId, partIds) { it.toString(Charsets.UTF_8) }

    /**
     * The texts of the [parts] of many items — item and part — read by one MultiGet in the order of their keys: the
     * store's files read one way along the disk, not a seek back and forth an item. A part of none left out.
     */
    fun texts(parts: Collection<Pair<String, String>>): Map<Pair<String, String>, String> {
        val asked = parts.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (asked.isEmpty()) return emptyMap()
        val values = db.multiGetAsList(List(asked.size) { texts }, asked.map { (item, part) -> key(item, part) })
        return asked.indices.mapNotNull { i -> values[i]?.let { asked[i] to it.toString(Charsets.UTF_8) } }.toMap()
    }

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
        multiGet(db, windows, itemId, partIds, ::vectorsOf)

    fun putWindows(itemId: String, partId: String, vectors: List<FloatArray>) =
        db.put(windows, key(itemId, partId), bytesOf(vectors))

    /**
     * The paragraphs of the parts [partIds] of the item, by part, each in their order with its vector and its text's
     * hash, null one not encoded; a part of none left out.
     */
    fun paragraphs(itemId: String, partIds: Collection<String>): Map<String, List<KeptParagraph?>> =
        multiGet(db, paragraphs, itemId, partIds, ::paragraphsOf)

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
        listOf(texts, windows, paragraphs, piecesFamily, fullTextFamily).forEach { db.deleteRange(it, from, to) }
    }

    override fun close() {
        handles.forEach(ColumnFamilyHandle::close)
        db.close()
        listOf(options, textOptions, vectorOptions, fullTextOptions).forEach { it.close() }
    }

    internal companion object {
        /** The store's folder in the source's own. */
        const val FOLDER = "parts"

        const val TEXT = "text"
        const val WINDOWS = "windows"
        const val PARAGRAPHS = "paragraphs"
        const val PIECES = "pieces"
        const val FULL_TEXT = "fullText"

        /** Between the item and the part in a key: no id has it. */
        const val SEPARATOR = '\u0000'

        /** Past every key of an item: the separator's next. */
        const val AFTER_SEPARATOR = '\u0001'

        /**
         * The least value kept apart from its key — a part's vectors, not a gap's —, and the size of a file of them.
         */
        const val BLOB_LEAST = 4L * 1024
        const val BLOB_FILE = 256L * 1024 * 1024

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

/**
 * One kind of value of the texts of an item kept as bytes, by the item and the text's id: a column family of the store.
 */
class PartValues(private val db: RocksDB, private val family: ColumnFamilyHandle) {
    fun put(itemId: String, partId: String, bytes: ByteArray) = db.put(family, PartStore.key(itemId, partId), bytes)

    fun delete(itemId: String, partId: String) = db.delete(family, PartStore.key(itemId, partId))

    /** The values of the texts [partIds] of the item, by text; a text of none left out. */
    fun of(itemId: String, partIds: Collection<String>): Map<String, ByteArray> =
        multiGet(db, family, itemId, partIds) { it }

    /** Every value kept, in the order of the keys — read as one pass of the store —: its item, its text, its bytes. */
    fun forEach(action: (itemId: String, partId: String, bytes: ByteArray) -> Unit) {
        db.newIterator(family).use { cursor ->
            cursor.seekToFirst()
            while (cursor.isValid) {
                val key = cursor.key().toString(Charsets.UTF_8)
                action(
                    key.substringBefore(PartStore.SEPARATOR),
                    key.substringAfter(PartStore.SEPARATOR),
                    cursor.value(),
                )
                cursor.next()
            }
        }
    }
}

/** The values of the [family] of the texts [partIds] of the item, read together, by text; a text of none left out. */
private fun <T> multiGet(
    db: RocksDB,
    family: ColumnFamilyHandle,
    itemId: String,
    partIds: Collection<String>,
    read: (ByteArray) -> T,
): Map<String, T> {
    if (partIds.isEmpty()) return emptyMap()
    val ids = partIds.distinct()
    val values = db.multiGetAsList(List(ids.size) { family }, ids.map { PartStore.key(itemId, it) })
    return ids.indices.mapNotNull { i -> values[i]?.let { ids[i] to read(it) } }.toMap()
}
