package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import java.util.BitSet
import org.apache.lucene.index.DocValues
import org.apache.lucene.index.DocValuesType
import org.apache.lucene.index.FieldInfo
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.LeafReader
import org.apache.lucene.index.StoredFieldVisitor
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.util.NumericUtils

/**
 * A DOCUMENT OF THE INDEX OF THE LIST AS IT IS WRITTEN, what a writing of a source whole compares the row read of the
 * databases with ([sameAs]): of a row alike nothing is written, and the stages of the tasks over its work stay (owner,
 * 2026-11-21: "read really everything, and rewrite what changed"). Its work and place in it, its dates, whether graded
 * and marked, its title, the values of every facet and every number — what the databases make of it; the values the
 * models gave it and its predictions are the index's own, read of it to make the row, not compared.
 */
class KeptRow(
    val work: String,
    val rank: Long,
    val linked: Boolean,
    val updated: Long,
    val added: Long,
    val graded: Long,
    val marked: Long,
    val title: String,
    val facets: Map<String, List<String>>,
    val numbers: Map<String, Double>,
)

/** Whether the document [kept] is this row's as the databases make it now: of the same row, nothing to write. */
fun ListRow.sameAs(kept: KeptRow): Boolean =
    work.toString() == kept.work &&
        rank.toLong() == kept.rank &&
        linked == kept.linked &&
        updatedAt.toEpochMilli() == kept.updated &&
        firstSeenAt.toEpochMilli() == kept.added &&
        (if (values.graded) 1L else 0L) == kept.graded &&
        (if (values.marked) 1L else 0L) == kept.marked &&
        title == kept.title &&
        facets.filterValues { it.isNotEmpty() } == kept.facets &&
        numbers == kept.numbers

/** THE DOCUMENTS OF THE INDEX OF THE LIST AS WRITTEN, of the items asked for by their keys ([KeptRow]). */
object ListKeptRows {

    /** Of the items [ids] of the [source], their documents as the [reader] holds them; an item of none left out. */
    fun of(reader: IndexReader, source: String, ids: Collection<String>): Map<String, KeptRow> {
        val out = HashMap<String, KeptRow>()
        reader.leaves().forEach { leaf ->
            val r = leaf.reader()
            // Of every key its live document, read in the order of the documents: the values are read forward alone
            val found = sortedMapOf<Int, String>()
            ids.forEach { id ->
                val docs = r.postings(Term(ListDocuments.KEY, ItemKey(source, id).toString())) ?: return@forEach
                var doc = docs.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS && r.liveDocs?.get(doc) == false) doc = docs.nextDoc()
                if (doc != DocIdSetIterator.NO_MORE_DOCS) found[doc] = id
            }
            if (found.isNotEmpty()) {
                val segment = Segment(r)
                found.forEach { (doc, id) -> out[id] = segment.of(doc) }
            }
        }
        return out
    }

    /** The documents of one segment, read forward. */
    private class Segment(private val r: LeafReader) {
        private val work = DocValues.getSorted(r, ListDocuments.WORK)
        private val rank = DocValues.getNumeric(r, ListDocuments.RANK)
        private val updated = DocValues.getNumeric(r, ListDocuments.UPDATED)
        private val added = DocValues.getNumeric(r, ListDocuments.ADDED)
        private val graded = DocValues.getNumeric(r, ListDocuments.GRADED)
        private val marked = DocValues.getNumeric(r, ListDocuments.MARKED)
        private val title = DocValues.getBinary(r, ListDocuments.TITLE)
        private val stored = r.storedFields()

        /** The documents of works of several items. */
        private val linked =
            BitSet().also { set ->
                r.postings(Term(ListDocuments.LINKED, ListDocuments.YES))?.let { docs ->
                    var doc = docs.nextDoc()
                    while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                        set.set(doc)
                        doc = docs.nextDoc()
                    }
                }
            }

        /** Every number of the segment, by its id, as its doc values. */
        private val numbers =
            r.fieldInfos
                .filter { it.name.startsWith(NUMBER) && it.docValuesType == DocValuesType.SORTED_NUMERIC }
                .associate { it.name.removePrefix(NUMBER) to DocValues.getSortedNumeric(r, it.name) }

        fun of(doc: Int): KeptRow {
            val visitor = Facets()
            stored.document(doc, visitor)
            return KeptRow(
                work = if (work.advanceExact(doc)) work.lookupOrd(work.ordValue()).utf8ToString() else "",
                rank = if (rank.advanceExact(doc)) rank.longValue() else -1,
                linked = linked.get(doc),
                updated = if (updated.advanceExact(doc)) updated.longValue() else -1,
                added = if (added.advanceExact(doc)) added.longValue() else -1,
                graded = if (graded.advanceExact(doc)) graded.longValue() else -1,
                marked = if (marked.advanceExact(doc)) marked.longValue() else -1,
                title = if (title.advanceExact(doc)) title.binaryValue().utf8ToString() else "",
                facets = visitor.values,
                numbers =
                    numbers
                        .filterValues { it.advanceExact(doc) }
                        .mapValues { (_, v) -> NumericUtils.sortableLongToDouble(v.nextValue()) },
            )
        }
    }

    /** What a stored document is read for: the values of its facets, in their order. */
    private class Facets : StoredFieldVisitor() {
        val values = LinkedHashMap<String, MutableList<String>>()

        override fun needsField(fieldInfo: FieldInfo): Status =
            if (ListDocuments.facetIdOf(fieldInfo.name) != null) Status.YES else Status.NO

        override fun stringField(fieldInfo: FieldInfo, value: String) {
            values.getOrPut(ListDocuments.facetIdOf(fieldInfo.name)!!) { ArrayList() } += value
        }
    }

    /** The prefix of a number's field ([ListDocuments.numberField]). */
    private val NUMBER = ListDocuments.numberField("")
}
