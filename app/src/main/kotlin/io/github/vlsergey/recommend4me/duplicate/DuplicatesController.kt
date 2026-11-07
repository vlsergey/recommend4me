package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.DuplicatesApi
import io.github.vlsergey.recommend4me.api.model.DuplicateAnswer as ApiDuplicateAnswer
import io.github.vlsergey.recommend4me.api.model.DuplicatePage
import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateSort
import io.github.vlsergey.recommend4me.api.model.DuplicateVerdict
import io.github.vlsergey.recommend4me.api.model.WorkPairJournalPage
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class DuplicatesController(
    private val stores: Stores,
    private val duplicates: Duplicates,
    private val pages: DuplicatePages,
    private val journal: DuplicateJournal,
) : DuplicatesApi {

    override fun getDuplicates(
        type: String,
        open: Boolean,
        offset: Int,
        limit: Int,
        sort: DuplicateSort,
        answers: List<DuplicateVerdict>?,
    ): ResponseEntity<DuplicatePage> {
        val choice = PairChoice(open, answers.orEmpty().map { DuplicateAnswer.valueOf(it.name) }.toSet(), sort)
        val page = pages.page(type, choice, offset, limit) ?: return ResponseEntity.notFound().build()
        // A search or a fitting planned or running: what there is, the page asks again
        return ResponseEntity.status(if (page.pending) HttpStatus.ACCEPTED else HttpStatus.OK).body(page)
    }

    override fun getItemDuplicates(source: String, item: String): ResponseEntity<List<DuplicatePair>> {
        if (stores.source(source)?.items?.exists(item) != true) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(pages.ofItem(ItemKey(source, item)))
    }

    override fun searchDuplicates(type: String): ResponseEntity<Unit> {
        val store = stores.type(type) ?: return ResponseEntity.notFound().build()
        duplicates.plan(store, "asked", asked = true)
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

    override fun unanswerDuplicate(
        type: String,
        source: String,
        item: String,
        otherSource: String,
        otherItem: String,
    ): ResponseEntity<Unit> {
        duplicates.unanswer(type, ItemKey(source, item), ItemKey(otherSource, otherItem))
        return ResponseEntity.noContent().build()
    }

    override fun getDuplicateJournal(type: String, offset: Int, limit: Int): ResponseEntity<WorkPairJournalPage> =
        journal.journal(type, offset, limit)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun undoDuplicateWord(type: String, id: Long): ResponseEntity<Unit> {
        if (!journal.undo(type, id))
            throw Refusal.NOT_UNDOABLE.refuse(
                HttpStatus.CONFLICT,
                "Word $id of $type is no word of it, or no longer what its pair has",
            )
        return ResponseEntity.noContent().build()
    }
}
