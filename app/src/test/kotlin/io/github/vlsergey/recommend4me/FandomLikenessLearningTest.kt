package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.MethodChoice
import io.github.vlsergey.recommend4me.api.model.ModelState
import io.github.vlsergey.recommend4me.model.ModelsController
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.universe.FandomLikeness
import io.github.vlsergey.recommend4me.universe.FandomLikenessController
import io.github.vlsergey.recommend4me.universe.FandomLikenessFit
import io.github.vlsergey.recommend4me.universe.FandomLikenessLearning
import io.github.vlsergey.recommend4me.universe.FandomRelationChangeEvent
import io.github.vlsergey.recommend4me.universe.FandomVectorChangeEvent
import io.github.vlsergey.recommend4me.universe.FandomWay
import io.github.vlsergey.recommend4me.universe.withUniverses
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus

/**
 * When the likeness of the fandoms' names is learnt again: at once of the user's word on the universes; of what changed
 * by no word of the user's — the names of the sites' fandoms encoded, what a site tells of its fandoms — counted beside
 * the likeness, and learnt again once they come to a hundredth of the universes it was learnt of, the count let go
 * then. On the page of the models: learnt when the user asks, the ways a site's fandom is read compared when asked, and
 * the way the universes alike one are found by chosen.
 */
class FandomLikenessLearningTest : BaseTest() {
    @Autowired lateinit var events: ApplicationEventPublisher

    @Autowired lateinit var models: ModelsController

    @Autowired lateinit var page: FandomLikenessController

    private val likeness
        get() = stores.withUniverses().first().fandomLikeness

    private fun planned() = tasks.planned().any { it.work == Task(FandomLikenessLearning.LEARNING) }

    /** The background done, and a likeness kept as learnt of [universes] universes. */
    private fun learntOf(universes: Int) {
        background.run()
        likeness.keep(FandomLikeness(floatArrayOf(1f, 1f)), FandomLikenessFit(10, universes, null, null), Instant.now())
    }

    @Test
    fun `what changed by no word of the user's is counted, and learnt once it is a hundredth of the universes`() {
        learntOf(1000)
        events.publishEvent(FandomVectorChangeEvent(9))
        assertFalse(planned(), "9 changes of 1000 universes")
        assertEquals(9, likeness.autoChanges())

        events.publishEvent(FandomRelationChangeEvent(bySite = true))
        assertTrue(planned(), "10 of 1000")
        background.run()
        assertEquals(0, likeness.autoChanges(), "the count let go once learnt")
    }

    @Test
    fun `the user's word on the universes is learnt at once`() {
        learntOf(1000)
        events.publishEvent(FandomRelationChangeEvent(bySite = false))
        assertTrue(planned())
        background.run()
    }

    @Test
    fun `the page of the models shows the likeness, learns it and compares the ways when asked, a way chosen kept`() {
        background.run()
        val type = stores.withUniverses().first().type.id
        fun shown() = models.listModels(type).body!!.single { it.kind == ModelState.Kind.FANDOMS }
        assertEquals(FandomWay.SYNONYMS.name, shown().method, "the synonyms of none chosen")

        assertEquals(HttpStatus.NO_CONTENT, page.fitFandomLikeness(type).statusCode)
        assertTrue(tasks.due(Task(FandomLikenessLearning.LEARNING)), "learnt when asked")
        background.run()

        val started = page.startFandomWayComparison(type).body!!
        assertNull(started.finishedAt, "compared in the queue")
        background.run()
        val compared = page.getFandomWayComparison(type).body!!
        assertNotNull(compared.finishedAt)
        assertNull(compared.error)
        assertEquals(FandomWay.entries.map { it.name }, compared.ways.map { it.way.name })

        assertEquals(HttpStatus.BAD_REQUEST, page.chooseFandomWay(type, MethodChoice("NONE")).statusCode)
        assertEquals(HttpStatus.NO_CONTENT, page.chooseFandomWay(type, MethodChoice(FandomWay.NAMES.name)).statusCode)
        assertEquals(FandomWay.NAMES.name, shown().method)
        page.chooseFandomWay(type, MethodChoice(FandomWay.SYNONYMS.name))
    }
}
