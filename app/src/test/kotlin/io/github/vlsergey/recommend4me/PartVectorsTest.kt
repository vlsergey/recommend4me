package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.part.Paragraphs
import io.github.vlsergey.recommend4me.part.PartVectors
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.inBackground
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The vectors of the chapters of the works, a portion of works a turn: every part of the works of a turn encoded, its
 * windows and its paragraphs — the works of several parts, of parts more than a batch of the encoder —, each part once;
 * the portion's parts read by their works, not looked for past the last part given over the table (owner, 2026-11-24: a
 * turn of 50 works of ficbook took 47 s, its every batch walking the parts of 1.4 million works to the end of the
 * table).
 */
class PartVectorsTest : SiteTestBase() {
    @Autowired lateinit var partVectors: PartVectors

    @Autowired lateinit var plugins: Plugins

    private val ids = (1..3).map { "pv$it" }

    private fun text(id: String, n: Int) =
        "The $n-th chapter of $id: a road walked for days.\nA harbour of ships, and of a lantern lit on a reef."

    @Test
    fun `every part of the works of the turns encoded, windows and paragraphs, a part once`() {
        val store = stores.source(site)!!
        ids.forEach { capture("$it|The work $it|space|A work of chapters||") }
        // Of a work more parts than a batch of the encoder takes
        ids.forEachIndexed { k, id ->
            store.parts.save(id, (1..(k * 5 + 2)).map { n -> PartData("c$n", n, "Chapter $n", text(id, n), null) })
        }
        refresh(store)
        val encoder = plugins.textEncoder()!!
        inBackground {
            var cursor: TaskCursor? = TaskCursor.FIRST
            while (cursor != null) cursor = partVectors.turn("books", WorkScope.OTHER, cursor, 2)
        }

        val windows = store.parts.windows.windowCountsOf(ids, encoder.windowsId)
        assertEquals(ids.toSet(), windows.keys, "every work's windows")
        assertTrue(
            store.parts.windows.waitingOf(ids, encoder.windowsId, Paragraphs.keyOf(encoder.id)).isEmpty(),
            "every part's windows and paragraphs",
        )
        ids.forEachIndexed { k, id ->
            assertEquals(k * 5 + 2, store.parts.windows.windowsByPart(id, encoder.windowsId).size, "the parts of $id")
        }
    }
}
