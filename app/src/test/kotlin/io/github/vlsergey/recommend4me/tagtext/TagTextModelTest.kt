package io.github.vlsergey.recommend4me.tagtext

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagTextModelTest {

    private fun answer(tag: Long, closeness: Double, verdict: TagTextVerdict) = TagTextAnswer("w", tag, "c", 0, verdict, closeness.toFloat())

    @Test
    fun `nothing is fitted of answers of one kind`() {
        assertNull(TagTextModel.fit(listOf(answer(1, 0.6, TagTextVerdict.YES), answer(2, 0.5, TagTextVerdict.YES))))
        // Not enough teaches nothing
        assertNull(TagTextModel.fit(listOf(answer(1, 0.6, TagTextVerdict.YES), answer(2, 0.5, TagTextVerdict.ENOUGH_NOT))))
    }

    @Test
    fun `the closer the likelier, and a tag always called wrong sinks below the others however close`() {
        // Tag 1 of the subject: yes when close, wrong when far; tag 2 structural: wrong however close
        val answers = (0 until 20).flatMap { k ->
            val close = 0.55 + k * 0.005
            val far = 0.30 + k * 0.005
            listOf(
                answer(1, close, TagTextVerdict.YES),
                answer(1, far, TagTextVerdict.WRONG),
                answer(2, close, TagTextVerdict.WRONG),
                answer(3, close, TagTextVerdict.YES),
                answer(3, far, TagTextVerdict.WRONG),
            )
        }
        val model = TagTextModel.fit(answers)!!
        assertTrue(model.slope > 0, "slope ${model.slope}")
        assertTrue(model.chance(1, 0.6) > model.chance(1, 0.35), "closer likelier")
        assertTrue(model.chance(2, 0.6) < model.chance(1, 0.6) && model.chance(2, 0.6) < 0.5, "the structural tag ${model.chance(2, 0.6)}")
        // A tag never answered on: told by its closeness alone
        assertTrue(model.chance(99, 0.6) > model.chance(99, 0.35))
        // The fitting lowers the loss it is fitted by
        val zero = TagTextModel(0.0, 0.0, emptyMap())
        assertTrue(TagTextModel.loss(model, answers) < TagTextModel.loss(zero, answers))
    }
}
