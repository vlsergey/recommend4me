package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import kotlin.test.Test
import kotlin.test.assertEquals

class ListDocumentsTest {
    @Test
    fun `an item's row is appended in the order of the columns of the script, a column for every datum and stage`() {
        val appended =
            listOf(
                "ITEM_KEY",
                "SOURCE",
                "WORK",
                "WORK_RANK",
                "LINKED",
                "UPDATED",
                "ADDED",
                "GRADED",
                "MARKED",
                "PREDICTION",
                "INTEREST",
                "MODEL_INTEREST",
                "EXPECTED",
                "TITLE",
            ) + WorkDatum.entries.map { it.column.name } + WorkStage.entries.map { it.column.name }
        assertEquals(appended, ITEM.fields().map { it.name })
    }
}
