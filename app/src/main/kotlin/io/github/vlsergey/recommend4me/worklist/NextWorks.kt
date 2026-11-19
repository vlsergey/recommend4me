package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.task.TaskCursor
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.FieldDoc
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.BytesRef

/** An item a task over the works takes, and its expected grade: none of no expectation. */
class TakenWork(val key: ItemKey, val grade: Double) {
    /** Where a task goes on from at this item, it among the next. */
    fun cursor() = TaskCursor(grade, key.toString())
}

/**
 * THE WORKS A TASK OVER THE WORKS TAKES NEXT, of the index of the list (owner, 2026-11-19: "by 1000 books, in the order
 * of the expected grades"; "the works of no prediction at the end"): the items of its [WorkScope] — the user marked
 * their works or not ([ListDocuments.MARKED]) — whose [WorkStage] is to be done ([stale]), the highest expected first,
 * of one expectation by their keys, from the cursor on: an item done is no longer to be done, and the next portion is
 * asked from the last one's place.
 */
object NextWorks {
    /** The priority of a task over the works of the [scope]: the marked and rated ahead of the others. */
    fun priority(scope: WorkScope): TaskPriority =
        if (scope == WorkScope.MARKED) TaskPriority.MARKED_WORKS else TaskPriority.OTHER_WORKS

    /** The next [n] items of the [scope] the query [stale] finds, from the [cursor] on, the highest expected first. */
    fun next(index: ListIndex, scope: WorkScope, stale: Query, cursor: TaskCursor, n: Int): List<TakenWork> =
        index.written { reader ->
            val searcher = IndexSearcher(reader)
            val marked = if (scope == WorkScope.MARKED) 1L else 0L
            val query =
                BooleanQuery.Builder()
                    .add(
                        NumericDocValuesField.newSlowExactQuery(ListDocuments.MARKED, marked),
                        BooleanClause.Occur.FILTER,
                    )
                    .add(stale, BooleanClause.Occur.FILTER)
                    .build()
            val sort =
                Sort(
                    SortField(ListDocuments.PREDICTION, SortField.Type.DOUBLE, true),
                    SortField(ListDocuments.KEY, SortField.Type.STRING),
                )
            // From the cursor on, it among them: a document of the cursor's place compares alike, and its number is
            // above none
            val hits =
                cursor.grade?.let { grade ->
                    val after = FieldDoc(-1, Float.NaN, arrayOf(grade, BytesRef(cursor.work.orEmpty())))
                    searcher.searchAfter(after, query, n, sort)
                } ?: searcher.search(query, n, sort)
            val stored = searcher.storedFields()
            hits.scoreDocs.mapNotNull { hit ->
                val key = stored.document(hit.doc, setOf(ListDocuments.KEY)).get(ListDocuments.KEY)
                val grade = (hit as FieldDoc).fields[0] as Double
                key?.let { TakenWork(ItemKey.parse(it), grade) }
            }
        }

    /** The documents of the items [keys] alone: what another store tells is to be done of them. */
    fun among(keys: Collection<ItemKey>): Query =
        TermInSetQuery(ListDocuments.KEY, keys.map { BytesRef(it.toString()) })

    /** The documents of the [stage] done before [since]: of every one when it never was. */
    fun before(stage: WorkStage, since: Long): Query =
        NumericDocValuesField.newSlowRangeQuery(stage.field, 0, since - 1)

    /** Of the sources [since] tells, the documents of each done before its time ([before]); none of no source. */
    fun before(stage: WorkStage, since: Map<String, Long>): Query =
        BooleanQuery.Builder()
            .apply {
                since.forEach { (source, at) ->
                    add(
                        BooleanQuery.Builder()
                            .add(TermQuery(Term(ListDocuments.SOURCE, source)), BooleanClause.Occur.FILTER)
                            .add(before(stage, at), BooleanClause.Occur.FILTER)
                            .build(),
                        BooleanClause.Occur.SHOULD,
                    )
                }
            }
            .setMinimumNumberShouldMatch(1)
            .build()
}
