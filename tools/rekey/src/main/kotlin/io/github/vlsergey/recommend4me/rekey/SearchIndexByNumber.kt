package io.github.vlsergey.recommend4me.rekey

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.apache.lucene.codecs.FieldsProducer
import org.apache.lucene.codecs.StoredFieldsReader
import org.apache.lucene.index.BaseTermsEnum
import org.apache.lucene.index.CodecReader
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.FieldInfo
import org.apache.lucene.index.FilterCodecReader
import org.apache.lucene.index.ImpactsEnum
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.PostingsEnum
import org.apache.lucene.index.SlowImpactsEnum
import org.apache.lucene.index.StoredFieldVisitor
import org.apache.lucene.index.Terms
import org.apache.lucene.index.TermsEnum
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.Bits
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.FixedBitSet

/**
 * THE INDEX OF THE SEARCH OF A CONTENT TYPE (`search-index`, Lucene: `LuceneIndex`) KEYED BY THE WORKS' NUMBERS: the
 * document of every work, its key (the field `id`, "source/site") made "source/number", copied whole into a new index
 * beside the old one — its segments added as they are, but the key, stored and indexed: no text read or stemmed again —
 * the index's own data (its format, whether it holds every work) carried over; put in the place of the old one, kept
 * beside ("search-index-by-site"). A document of a work no longer kept is not carried over.
 */
class SearchIndexByNumber(private val folder: Path, private val numbers: Numbers) {

    fun rekey(name: String, dryRun: Boolean) {
        val old = folder.resolve(FOLDER)
        if (!old.exists()) return
        val new = folder.resolve("$FOLDER-by-number")
        FSDirectory.open(old).use { dir ->
            DirectoryReader.open(dir).use { reader ->
                log("$name search: ${reader.numDocs()} documents of ${reader.leaves().size} segments")
                if (dryRun) return
                Files.createDirectories(new)
                FSDirectory.open(new).use { out ->
                    val config = IndexWriterConfig().setOpenMode(IndexWriterConfig.OpenMode.CREATE)
                    IndexWriter(out, config).use { w ->
                        val segments = reader.leaves().map { Rekeyed(it.reader() as CodecReader, numbers) }
                        w.addIndexes(*segments.toTypedArray())
                        w.setLiveCommitData(reader.indexCommit.userData.entries)
                        w.commit()
                        log(
                            "$name search: ${w.docStats.numDocs} documents, ${segments.sumOf { it.gone }} of works gone"
                        )
                    }
                }
            }
        }
        Files.move(old, folder.resolve("$FOLDER-by-site"))
        Files.move(new, old)
    }

    /**
     * A segment whose documents are keyed by the works' numbers: the stored key of each read once, the key of its
     * number given in its place — stored and as the term of its field —, a document of a work gone deleted.
     */
    private class Rekeyed(input: CodecReader, numbers: Numbers) : FilterCodecReader(input) {
        private val keys = arrayOfNulls<String>(input.maxDoc())
        private val live = FixedBitSet(input.maxDoc())
        var gone = 0
            private set

        init {
            val stored = input.storedFields()
            val before = input.liveDocs
            for (doc in 0 until input.maxDoc()) {
                if (before != null && !before.get(doc)) continue
                val key = stored.document(doc).get(ID)
                val number = key?.let(numbers::keyOf)
                if (number == null) gone++
                else {
                    keys[doc] = number
                    live.set(doc)
                }
            }
        }

        override fun getLiveDocs(): Bits = live

        override fun numDocs(): Int = live.cardinality()

        override fun getFieldsReader(): StoredFieldsReader = RekeyedStored(`in`.fieldsReader, keys)

        override fun getPostingsReader(): FieldsProducer = RekeyedPostings(`in`.postingsReader, keys)

        override fun getCoreCacheHelper(): IndexReader.CacheHelper? = null

        override fun getReaderCacheHelper(): IndexReader.CacheHelper? = null
    }

    /** The stored fields of a segment, the key of every document of its work's number. */
    private class RekeyedStored(private val inner: StoredFieldsReader, private val keys: Array<String?>) :
        StoredFieldsReader() {
        override fun document(docID: Int, visitor: StoredFieldVisitor) {
            inner.document(
                docID,
                object : StoredFieldVisitor() {
                    override fun needsField(fieldInfo: FieldInfo) = visitor.needsField(fieldInfo)

                    override fun stringField(fieldInfo: FieldInfo, value: String) =
                        visitor.stringField(fieldInfo, if (fieldInfo.name == ID) keys[docID] ?: value else value)

                    override fun binaryField(fieldInfo: FieldInfo, value: ByteArray) =
                        visitor.binaryField(fieldInfo, value)

                    override fun intField(fieldInfo: FieldInfo, value: Int) = visitor.intField(fieldInfo, value)

                    override fun longField(fieldInfo: FieldInfo, value: Long) = visitor.longField(fieldInfo, value)

                    override fun floatField(fieldInfo: FieldInfo, value: Float) = visitor.floatField(fieldInfo, value)

                    override fun doubleField(fieldInfo: FieldInfo, value: Double) =
                        visitor.doubleField(fieldInfo, value)
                },
            )
        }

        override fun clone(): StoredFieldsReader = RekeyedStored(inner.clone(), keys)

        override fun checkIntegrity() = inner.checkIntegrity()

        override fun close() = inner.close()
    }

    /** The postings of a segment, of its field of the keys the keys of the works' numbers, a document each. */
    private class RekeyedPostings(private val inner: FieldsProducer, keys: Array<String?>) : FieldsProducer() {
        private val ids: Terms

        init {
            val docs = keys.indices.filter { keys[it] != null }.sortedBy { BytesRef(keys[it]!!) }
            ids = KeyTerms(Array(docs.size) { BytesRef(keys[docs[it]]!!) }, IntArray(docs.size) { docs[it] })
        }

        override fun iterator(): MutableIterator<String> = inner.iterator()

        override fun terms(field: String): Terms? = if (field == ID) ids else inner.terms(field)

        override fun size(): Int = inner.size()

        override fun close() = inner.close()

        override fun checkIntegrity() = inner.checkIntegrity()
    }

    /** The keys of a segment's documents, ascending, a document each: of the field of the keys, docs alone. */
    private class KeyTerms(private val terms: Array<BytesRef>, private val docs: IntArray) : Terms() {
        override fun iterator(): TermsEnum = KeyTermsEnum(terms, docs)

        override fun size(): Long = terms.size.toLong()

        override fun getSumTotalTermFreq(): Long = terms.size.toLong()

        override fun getSumDocFreq(): Long = terms.size.toLong()

        override fun getDocCount(): Int = terms.size

        override fun hasFreqs() = false

        override fun hasOffsets() = false

        override fun hasPositions() = false

        override fun hasPayloads() = false
    }

    private class KeyTermsEnum(private val terms: Array<BytesRef>, private val docs: IntArray) : BaseTermsEnum() {
        private var at = -1

        override fun next(): BytesRef? = if (++at < terms.size) terms[at] else null

        override fun seekCeil(text: BytesRef): SeekStatus {
            val found = terms.binarySearch(text)
            at = if (found >= 0) found else -found - 1
            return when {
                found >= 0 -> SeekStatus.FOUND
                at >= terms.size -> SeekStatus.END
                else -> SeekStatus.NOT_FOUND
            }
        }

        override fun seekExact(ord: Long) {
            at = ord.toInt()
        }

        override fun term(): BytesRef = terms[at]

        override fun ord(): Long = at.toLong()

        override fun docFreq(): Int = 1

        override fun totalTermFreq(): Long = 1

        override fun postings(reuse: PostingsEnum?, flags: Int): PostingsEnum = OneDoc(docs[at])

        override fun impacts(flags: Int): ImpactsEnum = SlowImpactsEnum(postings(null, flags))
    }

    /** The postings of a key: its one document. */
    private class OneDoc(private val doc: Int) : PostingsEnum() {
        private var current = -1

        override fun docID(): Int = current

        override fun nextDoc(): Int {
            current = if (current < 0) doc else DocIdSetIterator.NO_MORE_DOCS
            return current
        }

        override fun advance(target: Int): Int {
            current = if (current < 0 && doc >= target) doc else DocIdSetIterator.NO_MORE_DOCS
            return current
        }

        override fun cost(): Long = 1

        override fun freq(): Int = 1

        override fun nextPosition(): Int = -1

        override fun startOffset(): Int = -1

        override fun endOffset(): Int = -1

        override fun getPayload(): BytesRef? = null
    }

    private companion object {
        const val FOLDER = "search-index"

        /** The field of a document's key, `LuceneIndex.ID`. */
        const val ID = "id"
    }
}
