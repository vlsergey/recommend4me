package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField

/**
 * THE WORKS OF THE FOURTH STAGE IN THEIR ORDER, of the index of the list (owner, 2026-11-19: "in low priority we start
 * taking 1k unrated works at a time and updating both the computed values and the prediction of the grade and interest
 * for them"; "the order: by the grade descending"): the items not graded and not done by the pass under way, the
 * highest predicted first, [next] a portion at a time; an item done marked with the pass ([passed]), so a prediction
 * written meanwhile does not move the pass.
 */
object ListPasses {
    /** The items [keys] of the [index] marked done by the pass [pass]; an item of no document none. */
    fun passed(index: ListIndex, keys: Collection<ItemKey>, pass: Long) =
        index.update(keys.associateWith { arrayOf(NumericDocValuesField(ListDocuments.PASS, pass)) })

    /** The next [n] items of the [index] the pass [pass] has not done, not graded, the highest predicted first. */
    fun next(index: ListIndex, pass: Long, n: Int): List<ItemKey> = index.written { reader ->
        val searcher = IndexSearcher(reader)
        val query =
            BooleanQuery.Builder()
                .add(MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                .add(NumericDocValuesField.newSlowExactQuery(ListDocuments.GRADED, 1L), BooleanClause.Occur.MUST_NOT)
                .add(NumericDocValuesField.newSlowExactQuery(ListDocuments.PASS, pass), BooleanClause.Occur.MUST_NOT)
                .build()
        val sort = Sort(SortField(ListDocuments.PREDICTION, SortField.Type.DOUBLE, true))
        val stored = searcher.storedFields()
        searcher.search(query, n, sort).scoreDocs.mapNotNull { hit ->
            stored.document(hit.doc, setOf(ListDocuments.KEY)).get(ListDocuments.KEY)?.let(ItemKey::parse)
        }
    }
}
