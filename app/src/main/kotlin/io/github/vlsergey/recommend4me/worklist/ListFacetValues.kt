package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
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
}
