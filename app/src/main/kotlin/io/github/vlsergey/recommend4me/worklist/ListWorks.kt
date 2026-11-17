package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkClusters
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreMode
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.BytesRef

/** An item of the list found: its key, the work it is of, its [rank] among the work's items, the shown one 0. */
class FoundItem(val key: ItemKey, val work: ItemKey, val rank: Long)

/**
 * THE WORKS OF SEVERAL ITEMS OF THE INDEX OF THE LIST ([ListIndex]): few — made by hand — so read item by item, while
 * every other item is a work of its own and a row by itself, sorted as it is, no group looked up for it.
 */
internal object ListWorks {

    /** Every item of a work of several that [query] finds, by its key. */
    fun linkedOf(s: IndexSearcher, query: Query): Map<ItemKey, FoundItem> =
        found(
                s,
                BooleanQuery.Builder()
                    .add(query, BooleanClause.Occur.FILTER)
                    .add(TermQuery(Term(ListDocuments.LINKED, ListDocuments.YES)), BooleanClause.Occur.FILTER)
                    .build(),
            )
            .associateBy { it.key }

    /** Every item [query] finds, with its work and its rank: of few items alone — every one is read. */
    fun found(s: IndexSearcher, query: Query): List<FoundItem> {
        val weight = s.createWeight(s.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1f)
        val out = ArrayList<FoundItem>()
        s.indexReader.leaves().forEach { leaf ->
            val r = leaf.reader()
            val live = r.liveDocs
            val stored = r.storedFields()
            val works = r.getSortedDocValues(ListDocuments.WORK)
            val ranks = r.getNumericDocValues(ListDocuments.RANK)
            val docs = weight.scorer(leaf)?.iterator() ?: return@forEach
            var doc = docs.nextDoc()
            while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                if (live == null || live.get(doc)) {
                    val key = ItemKey.parse(stored.document(doc, KEY_ONLY).get(ListDocuments.KEY))
                    val work =
                        if (works != null && works.advanceExact(doc))
                            ItemKey.parse(works.lookupOrd(works.ordValue()).utf8ToString())
                        else key
                    val rank = if (ranks != null && ranks.advanceExact(doc)) ranks.longValue() else 0L
                    out += FoundItem(key, work, rank)
                }
                doc = docs.nextDoc()
            }
        }
        return out
    }

    /**
     * The works of the items [keys] as the cards read them: of a work of several every item of it, the one it is shown
     * with first — of the index, not of every work of the type.
     */
    fun clustersOf(s: IndexSearcher, keys: Collection<ItemKey>): WorkClusters {
        val works =
            if (keys.isEmpty()) emptySet()
            else
                linkedOf(s, TermInSetQuery(ListDocuments.KEY, keys.map { BytesRef(it.toString()) })).values.mapTo(
                    HashSet()
                ) {
                    it.work
                }
        val members =
            if (works.isEmpty()) emptyList()
            else found(s, TermInSetQuery(ListDocuments.WORK, works.map { BytesRef(it.toString()) }))
        return WorkClusters.of(
            members.groupBy { it.work }.values.filter { it.size > 1 }.map { g -> g.sortedBy { it.rank }.map { it.key } }
        )
    }

    private val KEY_ONLY = setOf(ListDocuments.KEY)
}
