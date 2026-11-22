package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.InterestRequest
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.worklist.HeldTexts
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.ListIndexing
import io.github.vlsergey.recommend4me.worklist.ListTexts
import io.github.vlsergey.recommend4me.worklist.WorkStage
import io.github.vlsergey.recommend4me.worklist.WorkStages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired

/**
 * The stages of the tasks over the works do again the works whose data they read changed, and only them: a mark of
 * interest — read by the expectations alone — leaves the vectors, the fields and the search of its work as they were;
 * an answer has the vectors of its work done again, of no other work.
 */
class WorkStagesTest : SiteTestBase() {
    @Autowired lateinit var lists: ListIndexes
    @Autowired lateinit var plugins: Plugins

    private fun stages(vararg ids: String): Map<String, WorkStages> =
        WorkStages.of(lists.of("books"), site, ids.toList())

    @Test
    fun `a stage does again the works whose data it reads changed, and no other`() {
        capture("ws1|The first lantern|space|A lantern lit on a far shore||")
        capture("ws2|The second lantern|space|A lantern lit on a near shore||")
        background.run()
        val written = stages("ws1", "ws2")
        assertTrue(written.getValue("ws1")[WorkStage.VECTORS] > 0, "the vectors of a new work made")

        // A mark of interest: none of the vectors, the fields, the search reads it
        gradesController.markInterest(site, "ws1", InterestRequest(true))
        background.run()
        val marked = stages("ws1", "ws2")
        listOf(WorkStage.VECTORS, WorkStage.FIELDS, WorkStage.TEXTS).forEach { stage ->
            assertEquals(written.getValue("ws1")[stage], marked.getValue("ws1")[stage], "$stage of the work marked")
        }

        // An answer: the vectors read it, of its work alone
        corrections.correctFacet(site, "ws2", FacetCorrection(facet = "tags", added = true, key = "lighthouse"))
        background.run()
        val answered = stages("ws1", "ws2")
        assertTrue(
            answered.getValue("ws2")[WorkStage.VECTORS] > marked.getValue("ws2")[WorkStage.VECTORS],
            "the vectors of the work answered on looked at again",
        )
        // Of the other work, what reads neither a model nor the search of the tests: the answer refits the models of
        // the fields, whose works are all done again after them, and the search of the tests keeps nothing — made anew
        // whole every time
        assertEquals(marked.getValue("ws1")[WorkStage.VECTORS], answered.getValue("ws1")[WorkStage.VECTORS])
    }

    @Test
    fun `a work's document holds its texts and the vector of its title, and the vectors made anew of a text changed`() {
        capture("wt1|The third lantern|space|A lantern lit on a high cliff||")
        background.run()
        val key = ItemKey(site, "wt1")
        val title = ListTexts.vectorField(plugins.textEncoder()!!.id, TextVectors.TITLE)
        val text = ListTexts.textField("annotation")
        fun held() = HeldTexts.of(lists.of("books"), listOf(key), listOf(title, text))[key].orEmpty()
        val first = held()
        assertTrue(title in first, "the vector of the title")
        assertEquals("A lantern lit on a high cliff", String(first.getValue(text)))

        // The title changed: the document holds the vector made of the new one
        capture("wt1|The lantern of the far north|space|A lantern lit on a high cliff||")
        background.run()
        val second = held()
        assertTrue(!first.getValue(title).contentEquals(second.getValue(title)), "the vector of the new title")
    }

    @Test
    fun `an index marked current as of nought is compared whole, every stage of its works kept`() {
        capture("wz1|The fourth lantern|space|A lantern lit in a deep bay||")
        background.run()
        val before = stages("wz1").getValue("wz1")
        val index = lists.of("books")
        index.stages.changed(ListIndexing.WRITTEN, 0)
        index.commit()
        listIndexing.plan("books", "start")
        background.run()
        val after = stages("wz1").getValue("wz1")
        // Of the stages of no model: the fields, the expectations and the search are done again as a model of the
        // shared application is fitted by the tests before, whatever the index
        listOf(WorkStage.VECTORS, WorkStage.WINDOWS, WorkStage.CAST, WorkStage.SETS).forEach {
            assertEquals(before[it], after[it], "$it of a work its document holds alike")
        }
        assertTrue((index.stages.since(ListIndexing.WRITTEN) ?: 0) > 0, "the index written again")
    }
}
