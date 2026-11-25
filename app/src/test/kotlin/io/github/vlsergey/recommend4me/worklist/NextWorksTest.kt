package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.task.TaskCursor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.jooq.impl.DSL

/**
 * THE WORKS A TASK TAKES NEXT ([NextWorks.next]): the highest expected first, those of no expectation last, from the
 * cursor on, a portion at a time.
 */
class NextWorksTest {
    private fun row(id: String, prediction: Double?) =
        ListRow(
            key = ItemKey("site", id),
            work = ItemKey("site", id),
            rank = 0,
            linked = false,
            updatedAt = Instant.ofEpochMilli(1_700_000_000_000),
            firstSeenAt = Instant.ofEpochMilli(1_600_000_000_000),
            values = RowValues(false, prediction, null, null),
            facets = emptyMap(),
            numbers = emptyMap(),
            title = id,
        )

    private fun ListIndex.next(cursor: TaskCursor = TaskCursor.FIRST, n: Int = 10) =
        NextWorks.next(this, WorkScope.OTHER, DSL.trueCondition(), cursor, n).map { it.key.id }

    @Test
    fun `the works taken the highest expected first, from the cursor on`() {
        temporaryIndex { index ->
            index.replace(listOf(row("a", 4.0), row("b", 9.0), row("c", 6.0), row("d", null), row("e", 7.0)))
            index.commit()
            assertEquals(listOf("b", "e", "c", "a", "d"), index.next())
            // From the cursor on, a portion at a time
            assertEquals(listOf("b", "e"), index.next(n = 2))
            assertEquals(listOf("a", "d"), index.next(TaskCursor(6.0, "site:c"), n = 2))
        }
    }
}
