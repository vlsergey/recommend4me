package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.DuplicatesApi
import io.github.vlsergey.recommend4me.api.model.DuplicatePage
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import io.github.vlsergey.recommend4me.api.model.DuplicateAnswer as ApiDuplicateAnswer

@RestController
class DuplicatesController(private val stores: Stores, private val duplicates: Duplicates) : DuplicatesApi {

    override fun getDuplicates(type: String, answered: Boolean, offset: Int, limit: Int): ResponseEntity<DuplicatePage> {
        val page = duplicates.page(type, answered, offset, limit) ?: return ResponseEntity.notFound().build()
        // A search or a fitting planned or running: what there is, the page asks again
        return ResponseEntity.status(if (page.pending) HttpStatus.ACCEPTED else HttpStatus.OK).body(page)
    }

    override fun searchDuplicates(type: String): ResponseEntity<Unit> {
        val store = stores.type(type) ?: return ResponseEntity.notFound().build()
        duplicates.plan(store, "asked")
        return ResponseEntity.accepted().build()
    }

    override fun answerDuplicate(type: String, duplicateAnswer: ApiDuplicateAnswer): ResponseEntity<Unit> {
        val a = duplicateAnswer.first.let { ItemKey(it.source, it.item) }
        val b = duplicateAnswer.second.let { ItemKey(it.source, it.item) }
        if (!duplicates.answer(type, a, b, DuplicateAnswer.valueOf(duplicateAnswer.verdict.name))) {
            throw Refusal.NO_SUCH_WORK.refuse(HttpStatus.BAD_REQUEST, "No such pair of works of $type: $a, $b")
        }
        return ResponseEntity.noContent().build()
    }

    override fun unanswerDuplicate(type: String, source: String, item: String, otherSource: String, otherItem: String): ResponseEntity<Unit> {
        duplicates.unanswer(type, ItemKey(source, item), ItemKey(otherSource, otherItem))
        return ResponseEntity.noContent().build()
    }
}
