package io.github.vlsergey.recommend4me.worklist

import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.BytesRef

/**
 * How many items of the list have each value of a filter facet ([counts]), of how many items in all of the sources that
 * have it ([total]), and how many of them have none ([without]).
 */
class ValueCounts(val counts: Map<String, Int>, val total: Int, val without: Int)

/**
 * THE VALUES OF A FILTER FACET COUNTED OF THE INDEX OF THE LIST ([ListIndex]), exactly: every item that has a value —
 * the site's, the model's kept, the user's word on top — counted, no work read, no model run.
 */
object ListCounts {

    /** The values of the filter facet [facetId] of the items of the [sources] that have it. */
    fun of(index: ListIndex, facetId: String, sources: Collection<String>): ValueCounts = index.searching { s ->
        val field = ListDocuments.facetField(facetId)
        val counts = HashMap<String, Int>()
        index.valuesOf(s, facetId).forEach { term -> counts[term.utf8ToString()] = live(s, Term(field, term)) }
        ValueCounts(
            counts,
            s.count(TermInSetQuery(ListDocuments.SOURCE, sources.map(::BytesRef))),
            s.count(TermQuery(Term(field, ListDocuments.NONE))),
        )
    }

    /** How many items not removed have the [term]: its documents walked, not its count, which holds the removed. */
    private fun live(s: IndexSearcher, term: Term): Int {
        var n = 0
        s.indexReader.leaves().forEach { leaf ->
            val r = leaf.reader()
            val live = r.liveDocs
            val docs = r.postings(term) ?: return@forEach
            var doc = docs.nextDoc()
            while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                if (live == null || live.get(doc)) n++
                doc = docs.nextDoc()
            }
        }
        return n
    }
}
