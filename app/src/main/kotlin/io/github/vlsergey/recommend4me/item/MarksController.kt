package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.MarksApi
import io.github.vlsergey.recommend4me.api.model.Mark
import io.github.vlsergey.recommend4me.api.model.MatchFeedbackRequest
import io.github.vlsergey.recommend4me.mark.MarkKind
import io.github.vlsergey.recommend4me.mark.MarksChanged
import io.github.vlsergey.recommend4me.mark.ThingRef
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

private fun badMark(mark: Int) = Refusal.BAD_MARK.refuse(HttpStatus.BAD_REQUEST, "A mark is +1 or -1, not $mark")

/**
 * The user's marks of a work's pictures and reviews, given and taken back, and the user's word on a match of a mark;
 * every change told ([MarksChanged]).
 */
@RestController
class MarksController(private val stores: Stores, private val events: ApplicationEventPublisher) : MarksApi {

    override fun markPicture(source: String, item: String, position: Int, mark: Mark): ResponseEntity<Unit> {
        if (mark.mark != 1 && mark.mark != -1) throw badMark(mark.mark)
        val store = stores.source(source)
        val picture = store?.pictures?.find(item, position, null)
        return if (store == null || picture == null) ResponseEntity.notFound().build()
        else {
            store.marks.markPicture(item, position, picture.url, mark.mark, Instant.now())
            marked(store)
        }
    }

    override fun unmarkPicture(source: String, item: String, position: Int): ResponseEntity<Unit> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        store.marks.markPicture(item, position, "", null, Instant.now())
        return marked(store)
    }

    override fun markReview(source: String, item: String, reviewId: String, mark: Mark): ResponseEntity<Unit> {
        if (mark.mark != 1 && mark.mark != -1) throw badMark(mark.mark)
        val store = stores.source(source)
        return if (store == null || reviewId !in store.reviews.ids(item)) ResponseEntity.notFound().build()
        else {
            store.marks.markReview(item, reviewId, mark.mark, Instant.now())
            marked(store)
        }
    }

    override fun unmarkReview(source: String, item: String, reviewId: String): ResponseEntity<Unit> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        store.marks.markReview(item, reviewId, null, Instant.now())
        return marked(store)
    }

    override fun giveMatchFeedback(matchFeedbackRequest: MatchFeedbackRequest): ResponseEntity<Unit> {
        val r = matchFeedbackRequest
        if (r.verdict !in -1..1)
            throw Refusal.BAD_ANSWER.refuse(HttpStatus.BAD_REQUEST, "An answer is +1, 0 or -1, not ${r.verdict}")
        val store = stores.source(r.markSource) ?: return ResponseEntity.notFound().build()
        store.marks.setFeedback(
            MarkKind.valueOf(r.kind.value),
            ThingRef(ItemKey(r.markSource, r.markItem), r.markRef),
            ThingRef(ItemKey(r.matchSource, r.matchItem), r.matchRef),
            r.verdict.takeIf { it != 0 },
            Instant.now(),
        )
        return marked(store)
    }

    /** The change of the marks of the [store]'s content type told; answered with no content. */
    private fun marked(store: SourceStore): ResponseEntity<Unit> {
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }
}
