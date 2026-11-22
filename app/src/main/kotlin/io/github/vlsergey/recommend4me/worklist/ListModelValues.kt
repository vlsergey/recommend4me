package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.FieldInfo
import org.apache.lucene.index.StoredFieldVisitor
import org.apache.lucene.index.StoredFields
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.util.BytesRef

/**
 * THE VALUES THE MODELS GAVE THE ITEMS, AS THE INDEX OF THE LIST KEEPS THEM — the one place they are kept (user,
 * 2026-11-18) ([ListDocuments.modelField]): of an item, by the facet's key, its values beyond the site's own. Read as
 * written so far ([ListIndex.written]): what the filling of the values compares the new with, what a whole source made
 * anew keeps till the filling, what the documents of the search are made of.
 */
object ListModelValues {
    /** Of the items [ids] of the [source]: item to facet to values; an item of none, or of no document, left out. */
    fun of(index: ListIndex, source: String, ids: Collection<String>): Map<String, Map<String, List<String>>> =
        index.written { reader ->
            val searcher = IndexSearcher(reader)
            val out = HashMap<String, Map<String, List<String>>>()
            ids.distinct().chunked(CHUNK).forEach { chunk ->
                val query = TermInSetQuery(ListDocuments.KEY, chunk.map { BytesRef(ItemKey(source, it).toString()) })
                searcher.search(query, chunk.size).scoreDocs.forEach { hit ->
                    read(searcher.storedFields(), hit.doc)?.let { (key, values) -> out[key.id] = values }
                }
            }
            out
        }

    private fun every(reader: DirectoryReader, source: String): Map<String, Map<String, List<String>>> {
        val out = HashMap<String, Map<String, List<String>>>()
        reader.leaves().forEach { leaf ->
            val r = leaf.reader()
            val live = r.liveDocs
            val stored = r.storedFields()
            val docs = r.postings(Term(ListDocuments.SOURCE, source)) ?: return@forEach
            var doc = docs.nextDoc()
            while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                if (live == null || live.get(doc)) read(stored, doc)?.let { (key, values) -> out[key.id] = values }
                doc = docs.nextDoc()
            }
        }
        return out
    }

    /** The key of the document [doc] and its models' values; none of a document of no such value. */
    private fun read(
        stored: StoredFields,
        doc: Int,
    ): Pair<ItemKey, Map<String, List<String>>>? {
        val visitor = Visitor()
        stored.document(doc, visitor)
        val key = visitor.key ?: return null
        return if (visitor.values.isEmpty()) null else ItemKey.parse(key) to visitor.values
    }

    /** What a stored document is read for: its key and the fields of the models' values, nothing else. */
    private class Visitor : StoredFieldVisitor() {
        var key: String? = null
        val values = HashMap<String, MutableList<String>>()

        override fun needsField(fieldInfo: FieldInfo): Status =
            if (fieldInfo.name == ListDocuments.KEY || ListDocuments.modelFacetOf(fieldInfo.name) != null) Status.YES
            else Status.NO

        override fun stringField(fieldInfo: FieldInfo, value: String) {
            val facet = ListDocuments.modelFacetOf(fieldInfo.name)
            if (facet == null) key = value else values.getOrPut(facet) { ArrayList() } += value
        }
    }

    /** The items asked for by one query: the length of its list, not a meaning. */
    private const val CHUNK = 1000
}
