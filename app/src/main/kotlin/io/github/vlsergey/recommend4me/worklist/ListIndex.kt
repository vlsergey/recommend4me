package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.worklist.ListDocuments.KEY
import io.github.vlsergey.recommend4me.worklist.ListDocuments.SOURCE
import java.nio.file.Path
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.MultiTerms
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.store.FSDirectory
import org.apache.lucene.util.BytesRef

/**
 * THE INDEX OF THE LIST OF WORKS OF ONE CONTENT TYPE (user, 2026-11-17): what the screen of the list is filtered,
 * sorted, counted and cut into pages by, read of it alone — no database read at a request, no model run, of a million
 * works. A document of every item ([ListRow]): its key and source, the work it is of — a work of several items one row,
 * grouped by it —, the values of every filter facet as terms, every number as a point and a sorted value, its dates,
 * and what changes of its work without the item — graded, the prediction, the chance of interest, their product — as
 * values updated in place ([updateValues]): a training writes the prediction of every work without a document made
 * anew.
 *
 * The databases are the truth: the index is made of them ([ListIndexing]) and made anew whole when it is of another
 * [FORMAT]. Written in the queue of the computations alone, one writing at a time; read by any request.
 */
class ListIndex(dir: Path) : AutoCloseable {

    private val writer =
        IndexWriter(
            FSDirectory.open(dir),
            IndexWriterConfig().setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND).setRAMBufferSizeMB(BUFFER_MB),
        )
    private val searchers = SearcherManager(writer, SearcherFactory())

    /** Whether the index is of this [FORMAT]: else it is to be made anew whole before it is read. */
    fun current(): Boolean =
        DirectoryReader.open(writer).use {
            it.indexCommit.userData[FORMAT_KEY] == FORMAT.toString() && it.numDocs() > 0
        }

    /** Every document removed: the index made anew after. */
    fun clear() {
        writer.deleteAll()
    }

    /** The documents of the [rows] written in place of those of their items, and those of the items [gone] removed. */
    fun replace(rows: List<ListRow>, gone: Collection<ItemKey> = emptyList()) {
        rows.forEach { writer.updateDocument(Term(KEY, it.key.toString()), ListDocuments.of(it)) }
        gone.forEach { writer.deleteDocuments(Term(KEY, it.toString())) }
    }

    /** The documents of the [source] whose items are not among [present] removed. */
    fun keepOnly(source: String, present: Set<String>) {
        keysWith(Term(SOURCE, source))
            .filter { ItemKey.parse(it).id !in present }
            .forEach { writer.deleteDocuments(Term(KEY, it)) }
    }

    /** The keys of the items of works of several items as written so far: those a regrouping may have moved. */
    fun linked(): List<ItemKey> = keysWith(Term(ListDocuments.LINKED, ListDocuments.YES)).map(ItemKey::parse)

    /**
     * The keys of the documents with the [term], as written so far — what the requests read is not refreshed for it: a
     * writing under way is not shown half done.
     */
    private fun keysWith(term: Term): List<String> =
        DirectoryReader.open(writer).use { reader ->
            val out = ArrayList<String>()
            reader.leaves().forEach { leaf ->
                val r = leaf.reader()
                val live = r.liveDocs
                val stored = r.storedFields()
                val docs = r.postings(term) ?: return@forEach
                var doc = docs.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live == null || live.get(doc)) stored.document(doc, setOf(KEY)).get(KEY)?.let(out::add)
                    doc = docs.nextDoc()
                }
            }
            out
        }

    /**
     * Of every item of [values], what changes of its work without it, written in place; an item of no document none.
     */
    fun updateValues(values: Map<ItemKey, RowValues>) {
        values.forEach { (key, v) -> writer.updateDocValues(Term(KEY, key.toString()), *ListDocuments.valueFields(v)) }
    }

    /** What was written made what the requests read, and kept. */
    fun commit() {
        writer.setLiveCommitData(mapOf(FORMAT_KEY to FORMAT.toString()).entries)
        writer.commit()
        searchers.maybeRefreshBlocking()
    }

    /** The [block] of a reading of the index as it was last committed. */
    fun <T> searching(block: (IndexSearcher) -> T): T {
        val searcher = searchers.acquire()
        try {
            return block(searcher)
        } finally {
            searchers.release(searcher)
        }
    }

    /** Every value of the filter facet [facetId] some item has, the mark of none left out. */
    fun valuesOf(searcher: IndexSearcher, facetId: String): List<BytesRef> {
        val terms = MultiTerms.getTerms(searcher.indexReader, ListDocuments.facetField(facetId)) ?: return emptyList()
        val out = ArrayList<BytesRef>()
        val it = terms.iterator()
        var term = it.next()
        while (term != null) {
            if (term.utf8ToString() != ListDocuments.NONE) out += BytesRef.deepCopyOf(term)
            term = it.next()
        }
        return out
    }

    override fun close() {
        searchers.close()
        writer.close()
    }

    companion object {
        /** How the documents are made ([ListDocuments]): an index of another made anew whole. */
        const val FORMAT = 2
        private const val FORMAT_KEY = "format"

        /** The memory the writer gathers documents in before it writes them out. */
        private const val BUFFER_MB = 256.0
    }
}
