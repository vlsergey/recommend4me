package io.github.vlsergey.recommend4me.task

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.database.WholeReadRefused
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Every kind of work of the queue tells how it finds what it is to do, and one taking it by dates or keys reads no
 * table whole: the reading fails at once; a model fitted inside it reads whole.
 */
class TaskInputsTest {
    @Test
    fun `every kind of work tells its input`() =
        assertEquals(emptyList(), TaskKind.entries.filter { it !in TaskInputs.of })

    @Test
    fun `a stage reads no table whole, a fit inside it does`() {
        assertFailsWith<IllegalStateException> {
            TaskInputs.run(TaskKind.PREDICT_WORKS) { WholeReadRefused.check("every item") }
        }
        TaskInputs.run(TaskKind.PREDICT_WORKS) {
            TaskInputs.run(TaskKind.FIT_SET_DIRECTIONS) { WholeReadRefused.check("every item") }
        }
        TaskInputs.run(TaskKind.NAME_TAGS) { WholeReadRefused.check("every tag") }
    }
}
