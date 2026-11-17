package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.ScoreMode
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.BytesRef

/**
 * THE ITEMS OF A SOURCE CHOSEN BY THEIR VALUES, of the index of the list ([ListIndex]): those whose filter facet has,
 * of every group of values asked, one at least — the values as the user sees them, the site's, the model's kept, the
 * user's word on top. No database read, no model run.
 */
object ListSelection {

    /** The ids of the items of the [source] whose facet [facetId] has a value of every one of the [groups]. */
    fun idsOf(index: ListIndex, source: String, facetId: String, groups: List<Set<String>>): Set<String> =
        index.searching { s ->
            val field = ListDocuments.facetField(facetId)
            val q =
                BooleanQuery.Builder().add(TermQuery(Term(ListDocuments.SOURCE, source)), BooleanClause.Occur.FILTER)
            groups.forEach { g -> q.add(TermInSetQuery(field, g.map(::BytesRef)), BooleanClause.Occur.FILTER) }
            val weight = s.createWeight(s.rewrite(q.build()), ScoreMode.COMPLETE_NO_SCORES, 1f)
            val out = HashSet<String>()
            s.indexReader.leaves().forEach { leaf ->
                val stored = leaf.reader().storedFields()
                val live = leaf.reader().liveDocs
                val docs = weight.scorer(leaf)?.iterator() ?: return@forEach
                var doc = docs.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live == null || live.get(doc))
                        stored.document(doc, KEY_ONLY).get(ListDocuments.KEY)?.let { out += ItemKey.parse(it).id }
                    doc = docs.nextDoc()
                }
            }
            out
        }

    private val KEY_ONLY = setOf(ListDocuments.KEY)
}
