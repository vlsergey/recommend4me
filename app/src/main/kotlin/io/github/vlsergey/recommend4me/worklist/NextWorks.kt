package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.task.TaskCursor
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

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

    /**
     * The next [n] items of the [scope] the condition [stale] picks, from the [cursor] on, the highest expected first.
     */
    fun next(index: ListIndex, scope: WorkScope, stale: Condition, cursor: TaskCursor, n: Int): List<TakenWork> =
        index.written { db ->
            next(db, scope, stale, cursor, n)
        }

    private fun next(db: DSLContext, scope: WorkScope, stale: Condition, cursor: TaskCursor, n: Int): List<TakenWork> =
        db.select(ITEM.ITEM_KEY, ITEM.PREDICTION)
            .from(ITEM)
            .where(ITEM.MARKED.eq(scope == WorkScope.MARKED), stale, after(cursor))
            .orderBy(ITEM.PREDICTION.desc().nullsLast(), ITEM.ITEM_KEY.asc())
            .limit(n)
            .fetch { r -> TakenWork(ItemKey.parse(r.value1()!!), r.value2() ?: Double.NEGATIVE_INFINITY) }

    /**
     * The items after the [cursor] in the order of the tasks: of a lower expectation, or of the same and a greater key
     * — the items of no expectation, of -∞, after every other.
     */
    private fun after(cursor: TaskCursor): Condition {
        val grade = cursor.grade
        val key = ITEM.ITEM_KEY.gt(cursor.work.orEmpty())
        return when (grade) {
            null -> DSL.noCondition()
            Double.NEGATIVE_INFINITY -> ITEM.PREDICTION.isNull.and(key)
            else -> DSL.or(ITEM.PREDICTION.lt(grade), ITEM.PREDICTION.eq(grade).and(key), ITEM.PREDICTION.isNull)
        }
    }
}
