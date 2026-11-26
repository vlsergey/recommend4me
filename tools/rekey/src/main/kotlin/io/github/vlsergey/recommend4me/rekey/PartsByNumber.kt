package io.github.vlsergey.recommend4me.rekey

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.CompressionOptions
import org.rocksdb.CompressionType
import org.rocksdb.DBOptions
import org.rocksdb.Options
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import org.rocksdb.WriteOptions

/**
 * THE STORE OF THE PARTS OF A SOURCE'S WORKS (`parts`, RocksDB: `PartStore`) KEYED BY THE WORKS' NUMBERS: every key of
 * every family "site's id\0…" made "number\0…", the value as it was. A new store beside the old one, read whole — the
 * old one opened to be read alone —, put in its place once written; the old one kept beside ("parts-by-site") till the
 * application is seen to read the new. A key of a work no longer kept is not carried over. The families made as the
 * application makes them (`PartStore`): the texts compressed by a dictionary, the vectors in blob files.
 */
class PartsByNumber(private val folder: Path, private val numbers: Map<String, Long>) {

    fun rekey(name: String, dryRun: Boolean) {
        val old = folder.resolve(FOLDER)
        if (!old.exists()) return
        val families = RocksDB.listColumnFamilies(Options(), old.toString())
        log("$name parts: ${families.size} families of the store")
        if (dryRun) return
        val new = folder.resolve("$FOLDER-by-number")
        val oldHandles = ArrayList<ColumnFamilyHandle>()
        val newHandles = ArrayList<ColumnFamilyHandle>()
        val oldDescriptors = families.map { ColumnFamilyDescriptor(it, ColumnFamilyOptions()) }
        val newDescriptors = families.map { ColumnFamilyDescriptor(it, optionsOf(String(it))) }
        RocksDB.openReadOnly(DBOptions(), old.toString(), oldDescriptors, oldHandles).use { from ->
            Files.createDirectories(new)
            val options = DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
            RocksDB.open(options, new.toString(), newDescriptors, newHandles).use { to ->
                families.indices.forEach { f ->
                    copy(name, String(families[f]), from, oldHandles[f], to, newHandles[f])
                }
                to.flushWal(true)
            }
        }
        Files.move(old, folder.resolve("$FOLDER-by-site"))
        Files.move(new, old)
    }

    /** Every key of the family [family] of [from] keyed by the work's number in [to]. */
    private fun copy(
        name: String,
        family: String,
        from: RocksDB,
        fromHandle: ColumnFamilyHandle,
        to: RocksDB,
        toHandle: ColumnFamilyHandle,
    ) {
        var gone = 0L
        Batches(to, toHandle, "$name parts $family").use { batches ->
            from.newIterator(fromHandle).use { cursor ->
                cursor.seekToFirst()
                while (cursor.isValid) {
                    val key = numbered(cursor.key())
                    if (key == null) gone++ else batches.put(key, cursor.value())
                    cursor.next()
                }
            }
            log("$name parts $family: ${batches.written} keys, $gone of works gone")
        }
    }

    /** Of a key "site's id\0…" the key "number\0…"; none of a work gone. */
    private fun numbered(key: ByteArray): ByteArray? {
        val at = key.indexOf(0.toByte())
        val number = if (at < 0) null else numbers[String(key, 0, at, Charsets.UTF_8)]
        return number?.let { it.toString().toByteArray(Charsets.UTF_8) + key.copyOfRange(at, key.size) }
    }

    /**
     * The writes of a family of a new store, [BATCH] at a time, of no log of the store's: the store is new, made whole
     * or not at all. How far they are told every [LOGGED] keys, of the [label].
     */
    private class Batches(private val to: RocksDB, private val family: ColumnFamilyHandle, private val label: String) :
        AutoCloseable {
        private val writing = WriteOptions().setDisableWAL(true)
        private var batch = WriteBatch()
        var written = 0L
            private set

        fun put(key: ByteArray, value: ByteArray) {
            batch.put(family, key, value)
            if (++written % BATCH == 0L) {
                to.write(writing, batch)
                batch.close()
                batch = WriteBatch()
            }
            if (written % LOGGED == 0L) log("$label: $written keys")
        }

        override fun close() {
            to.write(writing, batch)
            batch.close()
            writing.close()
        }
    }

    private companion object {
        const val FOLDER = "parts"

        /** The keys written a batch: memory, not meaning. */
        const val BATCH = 10_000L

        /** The keys between two lines of the log. */
        const val LOGGED = 1_000_000L

        /** `PartStore`'s: the dictionary of the texts' compression and the bytes it is trained on. */
        const val DICTIONARY_BYTES = 64 * 1024
        const val DICTIONARY_TRAINING_BYTES = 100 * DICTIONARY_BYTES

        /** `PartStore`'s: a value of this many bytes and more kept in the blob files, of files of this size. */
        const val BLOB_LEAST = 4L * 1024
        const val BLOB_FILE = 256L * 1024 * 1024

        /** The options of a family as `PartStore` makes them, by its name. */
        fun optionsOf(family: String): ColumnFamilyOptions =
            when (family) {
                "text" ->
                    ColumnFamilyOptions()
                        .setCompressionType(CompressionType.ZSTD_COMPRESSION)
                        .setCompressionOptions(dictionary())
                        .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
                        .setBottommostCompressionOptions(dictionary().setEnabled(true))
                "fullText" -> ColumnFamilyOptions().setCompressionType(CompressionType.NO_COMPRESSION)
                else ->
                    ColumnFamilyOptions()
                        .setCompressionType(CompressionType.NO_COMPRESSION)
                        .setEnableBlobFiles(true)
                        .setMinBlobSize(BLOB_LEAST)
                        .setBlobFileSize(BLOB_FILE)
                        .setBlobCompressionType(CompressionType.NO_COMPRESSION)
                        .setEnableBlobGarbageCollection(true)
            }

        fun dictionary(): CompressionOptions =
            CompressionOptions().setMaxDictBytes(DICTIONARY_BYTES).setZStdMaxTrainBytes(DICTIONARY_TRAINING_BYTES)
    }
}
