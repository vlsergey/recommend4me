package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.index.LeafReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.util.BytesRef
import org.apache.lucene.util.NumericUtils

/**
 * THE VALUES OF THE ITEMS AS THE LIST KEEPS THEM ([ListDocuments.of]): of every filter facet, the site's, the model's
 * kept and the user's word on top — what the filters pass a work by, and the cards of a page show (user, 2026-11-17: no
 * model run for a card). Read of the documents of the items alone, by their keys.
 */
object ListFacetValues {

    /** Of the items [keys], every filter facet's values by its filter id; an item of no document none. */
    fun of(index: ListIndex, keys: Collection<ItemKey>): Map<ItemKey, Map<String, List<String>>> {
        if (keys.isEmpty()) return emptyMap()
        val terms = keys.distinct().map { BytesRef(it.toString()) }
        return index.searching { s ->
            val stored = s.storedFields()
            s.search(TermInSetQuery(ListDocuments.KEY, terms), terms.size).scoreDocs.associate { hit ->
                val doc = stored.document(hit.doc)
                val values = LinkedHashMap<String, MutableList<String>>()
                doc.fields.forEach { f ->
                    ListDocuments.facetIdOf(f.name())?.let { id ->
                        values.getOrPut(id) { ArrayList() } += f.stringValue()
                    }
                }
                ItemKey.parse(doc.get(ListDocuments.KEY)) to values
            }
        }
    }

    /**
     * Of every item of the [source] the index holds, its values of the filter facets [facetIds] by their numbers — a
     * value one number whichever item has it, its name in [ItemRows.names] — and its numbers of [numberIds]: what a
     * computation over every work reads of the index, in one pass of its doc values, no database read and no value a
     * string an item (user, 2026-11-18).
     */
    fun everyItem(
        index: ListIndex,
        source: String,
        facetIds: Collection<String>,
        numberIds: Collection<String>,
    ): ItemRows = index.searching { s ->
        val rows = ItemRows()
        s.indexReader.leaves().forEach { leaf -> Leaf(leaf.reader(), rows, facetIds, numberIds).read(source) }
        rows
    }

    /** One segment of the index read into [rows]: its own numbers of the values given every item's numbers. */
    private class Leaf(
        private val r: LeafReader,
        private val rows: ItemRows,
        facetIds: Collection<String>,
        numberIds: Collection<String>,
    ) {
        private val keys = r.getSortedDocValues(ListDocuments.KEY)
        private val sets = facetIds.mapNotNull { id ->
            r.getSortedSetDocValues(ListDocuments.valuesField(id))?.let { id to it }
        }
        private val numbers = numberIds.mapNotNull { id ->
            r.getSortedNumericDocValues(ListDocuments.numberField(id))?.let { id to it }
        }

        // Of every facet, the number of every value of the segment's, given at its first meeting
        private val numbered = sets.map { (_, dv) -> IntArray(dv.valueCount.toInt()) { -1 } }

        fun read(source: String) {
            val keys = keys ?: return
            val live = r.liveDocs
            val docs = r.postings(Term(ListDocuments.SOURCE, source)) ?: return
            var doc = docs.nextDoc()
            while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                if ((live == null || live.get(doc)) && keys.advanceExact(doc)) {
                    val id = ItemKey.parse(keys.lookupOrd(keys.ordValue()).utf8ToString()).id
                    rows.values[id] = valuesOf(doc)
                    numbers.forEach { (numberId, dv) ->
                        if (dv.advanceExact(doc))
                            rows.numbers.getOrPut(numberId) { HashMap() }[id] =
                                NumericUtils.sortableLongToDouble(dv.nextValue())
                    }
                }
                doc = docs.nextDoc()
            }
        }

        private fun valuesOf(doc: Int): IntArray {
            var out = IntArray(0)
            sets.forEachIndexed { f, (facetId, dv) ->
                if (!dv.advanceExact(doc)) return@forEachIndexed
                val count = dv.docValueCount()
                val at = out.size
                out = out.copyOf(at + count)
                for (k in 0 until count) {
                    val ord = dv.nextOrd().toInt()
                    var number = numbered[f][ord]
                    if (number < 0) {
                        number = rows.numberOf("$facetId:${dv.lookupOrd(ord.toLong()).utf8ToString()}")
                        numbered[f][ord] = number
                    }
                    out[at + k] = number
                }
            }
            return out
        }
    }
}

/**
 * EVERY ITEM OF A SOURCE AS THE INDEX OF THE LIST KEEPS IT ([ListFacetValues.everyItem]): its values by their numbers
 * ([values], by the item's id), their [names] by number — "<facet id>:<value>" —, and its numbers by their id, then the
 * item's ([numbers]).
 */
class ItemRows {
    val names = ArrayList<String>()
    val values = HashMap<String, IntArray>()
    val numbers = HashMap<String, HashMap<String, Double>>()
    private val byName = HashMap<String, Int>()

    internal fun numberOf(name: String): Int = byName.getOrPut(name) { names.size.also { names += name } }
}
