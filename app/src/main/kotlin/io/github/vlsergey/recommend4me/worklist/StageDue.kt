package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import org.jooq.Condition
import org.jooq.impl.DSL

/**
 * THE ITEMS A SWEEP OF A STAGE IS TO DO ([StageSweeps]): of every source, those with a date of what the stage reads
 * ([WorkStage.reads]) past its mark and up to the sweep's horizon — every one of a mark at nought — not done by the
 * sweep yet. Ranges of the dates of the items' rows, no item compared with another.
 */
internal object StageDue {

    /** The items the [stage]'s sweep of the [scope] begun [began] is to do, of the [sources]. */
    fun of(index: ListIndex, stage: WorkStage, scope: WorkScope, sources: Collection<String>, began: Long): Condition {
        val bySource = sources.mapNotNull { source ->
            val from = StageMarks.mark(index, stage, scope, source)
            val to = StageMarks.horizon(index, stage, scope, source) ?: return@mapNotNull null
            changed(stage, from, to)?.let { ITEM.SOURCE.eq(source).and(it) }
        }
        if (bySource.isEmpty()) return DSL.falseCondition()
        return DSL.or(bySource).and(notDone(stage, began))
    }

    /**
     * Of a stage whose own store tells what waits for it, the items not done by its sweep begun [began]: those waiting
     * picked apart ([NextWorks.next] among them).
     */
    fun pending(stage: WorkStage, began: Long): Condition = notDone(stage, began)

    /**
     * The items with a date of what the [stage] reads past [from] and up to [to]: every one of a mark at nought; none
     * of a source whose mark is at its horizon.
     */
    private fun changed(stage: WorkStage, from: Long, to: Long): Condition? =
        when {
            from <= 0L -> DSL.trueCondition()
            from >= to -> null
            else -> DSL.or(stage.reads.map { it.column.between(from + 1, to) })
        }

    /** The items not done by the sweep of the [stage] begun [began] — an item done by it holds its beginning. */
    private fun notDone(stage: WorkStage, began: Long): Condition = stage.column.isNull.or(stage.column.lt(began))
}
