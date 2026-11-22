package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.util.BytesRef

/**
 * THE TEXTS AND THE VECTORS THE DOCUMENTS OF THE LIST HOLD ([ListTexts]), read of the index: of every item of a source
 * in one pass of its doc values, or of the items asked for by their keys.
 */
object HeldTexts {
    /**
     * Of every item of the [source] the index holds, the value of each of the doc values [fields] it has, by the
     * field's place among them, as the index holds it — handed on at once, the bytes valid till the next is.
     */
    fun forEvery(index: ListIndex, source: String, fields: List<String>, action: (String, Int, BytesRef) -> Unit) =
        index.searching { s ->
            s.indexReader.leaves().forEach { leaf ->
                val r = leaf.reader()
                val keys = r.getSortedDocValues(ListDocuments.KEY) ?: return@forEach
                val values = fields.map { r.getBinaryDocValues(it) }
                if (values.all { it == null }) return@forEach
                val docs = r.postings(Term(ListDocuments.SOURCE, source)) ?: return@forEach
                val live = r.liveDocs
                var doc = docs.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                    if ((live == null || live.get(doc)) && keys.advanceExact(doc)) {
                        val id = ItemKey.parse(keys.lookupOrd(keys.ordValue()).utf8ToString()).id
                        values.forEachIndexed { k, dv ->
                            if (dv != null && dv.advanceExact(doc)) action(id, k, dv.binaryValue())
                        }
                    }
                    doc = docs.nextDoc()
                }
            }
        }

    /** Of the items [keys], the value of each of the doc values [fields] they have, by the field: as committed. */
    fun of(index: ListIndex, keys: Collection<ItemKey>, fields: List<String>): Map<ItemKey, Map<String, ByteArray>> =
        index.searching {
            of(it.indexReader, keys, fields)
        }

    /**
     * Of the items [keys], the value of each of the doc values [fields] they have, by the field, as the [reader] has
     * it.
     */
    fun of(reader: IndexReader, keys: Collection<ItemKey>, fields: List<String>): Map<ItemKey, Map<String, ByteArray>> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<ItemKey, HashMap<String, ByteArray>>()
        reader.leaves().forEach { leaf ->
            val r = leaf.reader()
            val live = r.liveDocs
            // Of every key its live document, in the order of the documents: the values are read forward alone
            val found = sortedMapOf<Int, ItemKey>()
            keys.forEach { key ->
                val docs = r.postings(Term(ListDocuments.KEY, key.toString())) ?: return@forEach
                var doc = docs.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live == null || live.get(doc)) found[doc] = key
                    doc = docs.nextDoc()
                }
            }
            fields.forEach { field ->
                val dv = r.getBinaryDocValues(field) ?: return@forEach
                found.forEach { (doc, key) ->
                    if (dv.advanceExact(doc))
                        out.getOrPut(key) { HashMap() }[field] = BytesRef.deepCopyOf(dv.binaryValue()).bytes
                }
            }
        }
        return out
    }
}
