package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.part.Paragraphs
import io.github.vlsergey.recommend4me.part.PartVectors
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.inBackground
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.StageMarks
import io.github.vlsergey.recommend4me.worklist.StageSweeps
import io.github.vlsergey.recommend4me.worklist.WorkStage
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

    @Autowired lateinit var lists: ListIndexes

    @Autowired lateinit var sweeps: StageSweeps

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
        turns()

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

    @Test
    fun `chapters older than the stage's mark wait till it is at nought, a new encoder setting it so by itself`() {
        val store = stores.source(site)!!
        capture("pv4|The work pv4|space|A work of chapters||")
        store.parts.save("pv4", listOf(PartData("c1", 1, "Chapter 1", text("pv4", 1), null)))
        refresh(store)
        val encoder = plugins.textEncoder()!!
        turns()
        assertEquals(setOf("pv4"), store.parts.windows.windowCountsOf(listOf("pv4"), encoder.windowsId).keys)

        // A chapter more, of a date the stage's mark is past: no work's chapters are read to find it
        store.parts.save("pv4", (1..2).map { n -> PartData("c$n", n, "Chapter $n", text("pv4", n), null) })
        refresh(store)
        markedPast(store.id)
        turns()
        assertEquals(1, waiting("pv4"), "the chapter of a date the mark is past waits")

        // The stage to be done of every work again: the chapter found
        sweeps.reset(WorkStage.WINDOWS, "books", stores.type("books")!!.sources.map { it.id })
        turns()
        assertEquals(0, waiting("pv4"))
    }

    /** Every turn of the works not marked or rated, a portion of two works each, till none is left. */
    private fun turns() = inBackground {
        var cursor: TaskCursor? = TaskCursor.FIRST
        while (cursor != null) cursor = partVectors.turn("books", WorkScope.OTHER, cursor, 2)
    }

    /** The parts of the work [id] whose windows or paragraphs wait to be encoded. */
    private fun waiting(id: String): Int {
        val encoder = plugins.textEncoder()!!
        return stores
            .source(site)!!
            .parts
            .windows
            .waitingOf(listOf(id), encoder.windowsId, Paragraphs.keyOf(encoder.id))
            .size
    }

    /** The stage of the chapters as having done every change of the [source] up to a date past every one written. */
    private fun markedPast(source: String) {
        val index = lists.of("books")
        WorkScope.entries.forEach {
            index.stages.changed(StageMarks.markKey(WorkStage.WINDOWS, it, source), Long.MAX_VALUE / 2)
            StageMarks.forgetSweep(index, WorkStage.WINDOWS, it)
        }
        index.commit()
    }
}
