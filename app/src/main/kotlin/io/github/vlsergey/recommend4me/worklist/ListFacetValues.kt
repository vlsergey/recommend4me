package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.index.MultiTerms
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.util.BytesRef

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
        titles: Boolean = false,
    ): ItemRows = index.searching { s ->
        EveryItem(source, facetIds.toList(), numberIds.toList(), titles)
            .read(s.indexReader.leaves().map { it.reader() })
    }
}
