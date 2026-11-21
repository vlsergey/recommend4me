package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.WorkScope
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.FieldExistsQuery
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.MatchNoDocsQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery

/**
 * THE DOCUMENTS A SWEEP OF A STAGE IS TO DO ([StageSweeps]): of every source, those with a date of what the stage reads
 * ([WorkStage.reads]) past its mark and up to the sweep's horizon — every one of a mark at nought — not done by the
 * sweep yet. Ranges of the dates of the documents, no document compared with another.
 */
internal object StageDue {

    /** The documents the [stage]'s sweep of the [scope] begun [began] is to do, of the [sources]. */
    fun of(index: ListIndex, stage: WorkStage, scope: WorkScope, sources: Collection<String>, began: Long): Query {
        val bySource = sources.mapNotNull { source ->
            val from = StageMarks.mark(index, stage, scope, source)
            val to = StageMarks.horizon(index, stage, scope, source) ?: return@mapNotNull null
            changed(stage, from, to)?.let { changed ->
                BooleanQuery.Builder()
                    .add(TermQuery(Term(ListDocuments.SOURCE, source)), BooleanClause.Occur.FILTER)
                    .add(changed, BooleanClause.Occur.FILTER)
                    .build()
            }
        }
        if (bySource.isEmpty()) return MatchNoDocsQuery()
        return BooleanQuery.Builder()
            .add(any(bySource), BooleanClause.Occur.FILTER)
            .add(notDone(stage, began), BooleanClause.Occur.FILTER)
            .build()
    }

    /**
     * Of a stage whose own store tells what waits for it, the [pending] documents not done by its sweep begun [began].
     */
    fun pending(pending: Query, stage: WorkStage, began: Long): Query =
        BooleanQuery.Builder()
            .add(pending, BooleanClause.Occur.FILTER)
            .add(notDone(stage, began), BooleanClause.Occur.FILTER)
            .build()

    /**
     * The documents with a date of what the [stage] reads past [from] and up to [to]: every one of a mark at nought;
     * none of a source whose mark is at its horizon.
     */
    private fun changed(stage: WorkStage, from: Long, to: Long): Query? =
        when {
            from <= 0L -> MatchAllDocsQuery()
            from >= to -> null
            else -> any(stage.reads.map { NumericDocValuesField.newSlowRangeQuery(it.field, from + 1, to) })
        }

    /** The documents of any of the [queries]. */
    private fun any(queries: List<Query>): Query =
        BooleanQuery.Builder()
            .apply { queries.forEach { add(it, BooleanClause.Occur.SHOULD) } }
            .setMinimumNumberShouldMatch(1)
            .build()

    /**
     * The documents not done by the sweep of the [stage] begun [began] — a document done by it holds its beginning —, a
     * document written before the stage was one of none.
     */
    private fun notDone(stage: WorkStage, began: Long): Query =
        any(
            listOf(
                NumericDocValuesField.newSlowRangeQuery(stage.field, Long.MIN_VALUE, began - 1),
                BooleanQuery.Builder()
                    .add(MatchAllDocsQuery(), BooleanClause.Occur.MUST)
                    .add(FieldExistsQuery(stage.field), BooleanClause.Occur.MUST_NOT)
                    .build(),
            )
        )
}
