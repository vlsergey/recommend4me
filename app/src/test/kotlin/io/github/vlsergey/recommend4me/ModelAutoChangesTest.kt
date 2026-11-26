package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.model.ModelAutoChanges
import io.github.vlsergey.recommend4me.part.SiteItemChapterChangeEvent
import io.github.vlsergey.recommend4me.setvector.SiteItemSetVectorChangeEvent
import io.github.vlsergey.recommend4me.task.Task
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher

/**
 * What changes the models of the fields and of the grades learn from by no word of the user's — a chapter of a work the
 * user rated, its texts' or its sets' vectors — is counted beside the model, and the model fitted again once the
 * changes are a hundredth of the works it learnt of, the count let go then (owner, 2026-11-24: a chapter of a rated
 * work read fitted every model again, and every work's fields and expectation were made anew from the first); the
 * user's word fits them at once.
 */
class ModelAutoChangesTest : SiteTestBase() {
    @Autowired lateinit var events: ApplicationEventPublisher

    private val type
        get() = stores.typeOf(site)

    private val changes
        get() = type.autoChanges

    private fun fields() = Task(TaskKind.FIT_FIELD_MODELS, type = type.id)

    private fun grades() = Task(TaskKind.FIT_GRADE_MODELS, type = type.id)

    private fun chapter(id: Long) = events.publishEvent(SiteItemChapterChangeEvent(site, listOf(id)))

    @Test
    fun `a chapter of a rated work is counted, the models of the fields fitted at a hundredth of their works`() {
        capture("ac1|The lantern keeper|space|A lantern lit on a reef||")
        capture("ac2|The quiet harbour|space|A harbour no ship leaves||")
        gradesController.rateItem(site, idOf(site, "ac1"), RatingRequest(4))
        background.run()
        changes.learnt(ModelAutoChanges.fields(site), 1000)

        repeat(9) { chapter(idOf(site, "ac1")) }
        chapter(idOf(site, "ac2"))
        assertFalse(tasks.due(fields()), "9 chapters of a rated work, one of a work never rated")
        assertEquals(9, changes.of(ModelAutoChanges.fields(site))?.changes)

        chapter(idOf(site, "ac1"))
        assertTrue(tasks.due(fields()), "10 of 1000")
        background.run()
        assertEquals(0, changes.of(ModelAutoChanges.fields(site))?.changes, "the count let go once fitted")
    }

    @Test
    fun `the sets' vectors are counted beside the model of the grades, learnt at a hundredth of its grades`() {
        capture("ac3|The salt road|space|A road of salt to the sea||")
        gradesController.rateItem(site, idOf(site, "ac3"), RatingRequest(5))
        background.run()
        changes.learnt(ModelAutoChanges.GRADES, 1000)

        events.publishEvent(SiteItemSetVectorChangeEvent(type.id, 9))
        assertFalse(tasks.due(grades()), "9 changes of 1000 grades")
        events.publishEvent(SiteItemSetVectorChangeEvent(type.id, 1))
        assertTrue(tasks.due(grades()), "10 of 1000")
        background.run()
        assertEquals(0, changes.of(ModelAutoChanges.GRADES)?.changes, "the count let go once learnt")
    }

    @Test
    fun `the user's answer fits the models of the fields and of the grades at once`() {
        capture("ac4|The tide clock|space|A clock kept by the tide||")
        background.run()
        changes.learnt(ModelAutoChanges.fields(site), 1000)
        changes.learnt(ModelAutoChanges.GRADES, 1000)
        corrections.correctFacet(
            site,
            idOf(site, "ac4"),
            FacetCorrection(facet = "tags", added = true, key = tag("space")),
        )
        assertTrue(tasks.due(fields()))
        assertTrue(tasks.due(grades()))
        background.run()
    }
}
