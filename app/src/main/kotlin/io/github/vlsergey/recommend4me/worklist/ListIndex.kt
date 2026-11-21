package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.worklist.ListDocuments.KEY
import io.github.vlsergey.recommend4me.worklist.ListDocuments.SOURCE
import java.nio.file.Path
import org.apache.lucene.document.Field
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.FSDirectory

/**
 * THE INDEX OF THE LIST OF WORKS OF ONE CONTENT TYPE (user, 2026-11-17): what the screen of the list is filtered,
 * sorted, counted and cut into pages by, read of it alone — no database read at a request, no model run, of a million
 * works. A document of every item ([ListRow]): its key and source, the work it is of — a work of several items one row,
 * grouped by it —, the values of every filter facet as terms, every number as a point and a sorted value, its dates,
 * and what changes of its work without the item — graded, the prediction, the chance of interest, their product — as
 * values updated in place ([update]): a training writes the prediction of every work without a document made anew.
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

    /** Since when each stage of the tasks over the works holds, kept with the commits ([StageTimes]). */
    val stages = StageTimes(writer.liveCommitData?.associate { it.key to it.value }.orEmpty() - FORMAT_KEY)

    /** Whether the index is of this [FORMAT]: else it is to be made anew whole before it is read. */
    fun current(): Boolean =
        DirectoryReader.open(writer).use {
            it.indexCommit.userData[FORMAT_KEY] == FORMAT.toString() && it.numDocs() > 0
        }

    /**
     * The format the documents are of, kept with the commits: of the last commit as the index opens, this one's once it
     * is made anew whole ([clear]) — a commit of anything else tells nothing of the documents written before.
     */
    @Volatile private var format: String? = writer.liveCommitData?.firstOrNull { it.key == FORMAT_KEY }?.value

    /** Every document removed: the index made anew after, of this [FORMAT]. */
    fun clear() {
        writer.deleteAll()
        format = FORMAT.toString()
    }

    /** The documents of the [rows] written in place of those of their items, and those of the items [gone] removed. */
    fun replace(rows: List<ListRow>, gone: Collection<ItemKey> = emptyList()) {
        rows.forEach { writer.updateDocument(Term(KEY, it.key.toString()), ListDocuments.of(it)) }
        gone.forEach { writer.deleteDocuments(Term(KEY, it.toString())) }
    }

    /** The documents of the [source] whose items are not among [present] removed: how many. */
    fun keepOnly(source: String, present: Set<String>): Int {
        val gone = keysWith(Term(SOURCE, source)).filter { ItemKey.parse(it).id !in present }
        gone.forEach { writer.deleteDocuments(Term(KEY, it)) }
        return gone.size
    }

    /** How many documents of the [source] there are, as written so far. */
    fun documentsOf(source: String): Int = written { IndexSearcher(it).count(TermQuery(Term(SOURCE, source))) }

    /** The [block] of a reading of the index as written so far, committed or not. */
    fun <T> written(block: (DirectoryReader) -> T): T = DirectoryReader.open(writer).use(block)

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
     * Of every item of [fields], the doc values written in place — what changes of its work without it
     * ([ListDocuments.valueFields]), the stages of the tasks over the works done ([WorkStages]); an item of no document
     * none.
     */
    fun update(fields: Map<ItemKey, Array<out Field>>) {
        fields.forEach { (key, of) -> writer.updateDocValues(Term(KEY, key.toString()), *of) }
    }

    /** What was written made what the requests read, and kept. */
    fun commit() {
        val data = stages.data() + listOfNotNull(format?.let { FORMAT_KEY to it })
        writer.setLiveCommitData(data.entries)
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

    override fun close() {
        searchers.close()
        writer.close()
    }

    companion object {
        /** How the documents are made ([ListDocuments]): an index of another made anew whole. */
        /**
         * 9 — the user's mark and the stages of the tasks over the works ([ListDocuments.MARKED], [WorkStages]); 10 —
         * the same, made anew: a commit of the stages' times told 9 of documents written before it; 11 — the vectors of
         * the site's text fields a stage too ([WorkStage.VECTORS]); 12 — the words and the characters of an item's kept
         * texts among its numbers ([io.github.vlsergey.recommend4me.part.PartCounts.WORDS_ID]); 13 — and of their
         * characters by their classes ([io.github.vlsergey.recommend4me.part.CharClasses]).
         */
        const val FORMAT = 13
        private const val FORMAT_KEY = "format"

        /** The memory the writer gathers documents in before it writes them out. */
        private const val BUFFER_MB = 256.0
    }
}
