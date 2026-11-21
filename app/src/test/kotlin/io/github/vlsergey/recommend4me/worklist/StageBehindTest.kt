package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.apache.lucene.search.IndexSearcher

class StageBehindTest {
    private fun row(id: String, changed: Long?, fieldsAt: Long) =
        ListRow(
            key = ItemKey("site", id),
            work = ItemKey("site", id),
            rank = 0,
            linked = false,
            updatedAt = Instant.EPOCH,
            firstSeenAt = Instant.EPOCH,
            values = RowValues(false, null, null, null),
            facets = emptyMap(),
            numbers = emptyMap(),
            title = id,
            stages = WorkStages(0, fieldsAt, 0, 0),
            changedAt = changed?.let(Instant::ofEpochMilli),
        )

    @Test
    fun `a stage done before its work last changed is behind it, one done after or of a work never dated is not`() {
        ListIndex(Files.createTempDirectory("list-index")).use { index ->
            index.replace(
                listOf(
                    row("before", changed = 1000, fieldsAt = 500),
                    row("never", changed = 1000, fieldsAt = 0),
                    row("after", changed = 1000, fieldsAt = 1500),
                    row("undated", changed = null, fieldsAt = 0),
                )
            )
            index.commit()
            val behind = index.written { reader ->
                val searcher = IndexSearcher(reader)
                searcher.search(StageBehind(WorkStage.FIELDS), 10).scoreDocs.map {
                    searcher.storedFields().document(it.doc).get(ListDocuments.KEY)
                }
            }
            assertEquals(setOf("site/before", "site/never"), behind.toSet())
            // Done again after the change: no longer behind it
            WorkStage.FIELDS.done(index, listOf(ItemKey("site", "before")), 2000)
            index.commit()
            val after = index.written { IndexSearcher(it).count(StageBehind(WorkStage.FIELDS)) }
            assertEquals(1, after)
        }
    }
}
