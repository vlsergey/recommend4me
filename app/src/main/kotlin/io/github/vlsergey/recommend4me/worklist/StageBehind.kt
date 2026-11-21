package io.github.vlsergey.recommend4me.worklist

import org.apache.lucene.index.DocValues
import org.apache.lucene.index.LeafReaderContext
import org.apache.lucene.search.ConstantScoreScorer
import org.apache.lucene.search.ConstantScoreWeight
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.QueryVisitor
import org.apache.lucene.search.ScoreMode
import org.apache.lucene.search.Scorer
import org.apache.lucene.search.ScorerSupplier
import org.apache.lucene.search.TwoPhaseIterator
import org.apache.lucene.search.Weight

/**
 * THE DOCUMENTS WHOSE WORK CHANGED AFTER THE [stage] WAS DONE OF IT (owner, 2026-11-21: what changes in a work is what
 * its stages are done again for, not a document made anew to forget them): of every document dated
 * ([ListDocuments.CHANGED_AT]) — its item written, its chapters' windows or its sets or its cast encoded, the user's
 * word on its work — the stage's time before that date, never done of none. A document never dated is behind no stage.
 */
class StageBehind(private val stage: WorkStage) : Query() {

    override fun createWeight(searcher: IndexSearcher, scoreMode: ScoreMode, boost: Float): Weight =
        object : ConstantScoreWeight(this, boost) {
            override fun scorerSupplier(context: LeafReaderContext): ScorerSupplier? {
                val r = context.reader()
                val changed = r.getNumericDocValues(ListDocuments.CHANGED_AT) ?: return null
                val done = DocValues.getNumeric(r, stage.field)
                val behind =
                    object : TwoPhaseIterator(changed) {
                        override fun matches(): Boolean {
                            val doc = changed.docID()
                            val at = if (done.advanceExact(doc)) done.longValue() else 0L
                            return at < changed.longValue()
                        }

                        override fun matchCost(): Float = MATCH_COST
                    }
                val scorer = ConstantScoreScorer(score(), scoreMode, behind)
                return object : ScorerSupplier() {
                    override fun get(leadCost: Long): Scorer = scorer

                    override fun cost(): Long = changed.cost()
                }
            }

            // The stages and the dates are written in place: a reading of one commit is not another's
            override fun isCacheable(ctx: LeafReaderContext): Boolean = false
        }

    override fun visit(visitor: QueryVisitor) = visitor.visitLeaf(this)

    override fun toString(field: String?): String = "StageBehind(${stage.field})"

    override fun equals(other: Any?): Boolean = other is StageBehind && other.stage == stage

    override fun hashCode(): Int = stage.hashCode()

    private companion object {
        /** What telling one document costs: two numbers read. */
        const val MATCH_COST = 2f
    }
}
