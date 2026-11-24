package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.universe.FandomLikeness
import io.github.vlsergey.recommend4me.universe.FandomLikenessFit
import io.github.vlsergey.recommend4me.universe.FandomLikenessLearning
import io.github.vlsergey.recommend4me.universe.FandomRelationChangeEvent
import io.github.vlsergey.recommend4me.universe.FandomVectorChangeEvent
import io.github.vlsergey.recommend4me.universe.withUniverses
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher

/**
 * When the likeness of the fandoms' names is learnt again: at once of the user's word on the universes; of what changed
 * by no word of the user's — the names of the sites' fandoms encoded, what a site tells of its fandoms — counted beside
 * the likeness, and learnt again once they come to a hundredth of the universes it was learnt of, the count let go
 * then.
 */
class FandomLikenessLearningTest : BaseTest() {
    @Autowired lateinit var events: ApplicationEventPublisher

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
}
