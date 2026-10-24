package io.github.vlsergey.recommend4me.marking

import io.github.vlsergey.recommend4me.api.MarkingApi
import io.github.vlsergey.recommend4me.api.model.MarkAnswer
import io.github.vlsergey.recommend4me.api.model.MarkedWorks
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** The confirming of the application's marking, value by value ([Marking]). */
@RestController
class MarkingController(private val marking: Marking) : MarkingApi {

    override fun getMarkingWorks(facet: String, value: String, answered: Boolean, offset: Int, limit: Int): ResponseEntity<MarkedWorks> =
        ResponseEntity.ok(marking.works(facet, value, answered, offset, limit))

    override fun answerMarking(markAnswer: MarkAnswer): ResponseEntity<Unit> {
        val a = markAnswer
        return if (marking.answer(a.source, a.item, a.facet, a.value, a.verdict)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    override fun unanswerMarking(source: String, item: String, facet: String, value: String): ResponseEntity<Unit> =
        if (marking.unanswer(source, item, facet, value)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
}
