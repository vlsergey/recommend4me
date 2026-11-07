package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.GradesApi
import io.github.vlsergey.recommend4me.api.model.InterestRequest
import io.github.vlsergey.recommend4me.api.model.ItemSummary
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.work.WorkEvents
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** The user's grade of a work and mark of interest in it, given and taken back; each answered with the work's card. */
@RestController
class GradesController(
    private val stores: Stores,
    private val cards: ItemCards,
    private val workEvents: WorkEvents,
) : GradesApi {

    override fun rateItem(source: String, item: String, ratingRequest: RatingRequest): ResponseEntity<ItemSummary> =
        stores.answerOn(source, item) { store, head ->
            if (ratingRequest.grade !in Grades.RANGE) {
                throw Refusal.BAD_GRADE.refuse(
                    HttpStatus.BAD_REQUEST,
                    "A grade is ${Grades.MIN}..${Grades.MAX}, not ${ratingRequest.grade}",
                    "min" to Grades.MIN,
                    "max" to Grades.MAX,
                )
            }
            store.ratings.rate(item, head.version, ratingRequest.grade, Instant.now())
            workEvents.gradesChanged(ItemKey(source, item))
            card(ItemKey(source, item))
        }

    override fun unrateItem(source: String, item: String, version: String?): ResponseEntity<ItemSummary> =
        stores.answerOn(source, item) { store, head ->
            store.ratings.unrate(item, version ?: head.version)
            workEvents.gradesChanged(ItemKey(source, item))
            card(ItemKey(source, item))
        }

    override fun markInterest(
        source: String,
        item: String,
        interestRequest: InterestRequest,
    ): ResponseEntity<ItemSummary> =
        stores.answerOn(source, item) { store, _ ->
            store.interest.mark(item, interestRequest.interesting, Instant.now())
            workEvents.interestChanged(ItemKey(source, item))
            card(ItemKey(source, item))
        }

    override fun unmarkInterest(source: String, item: String): ResponseEntity<ItemSummary> =
        stores.answerOn(source, item) { store, _ ->
            store.interest.forget(item)
            workEvents.interestChanged(ItemKey(source, item))
            card(ItemKey(source, item))
        }

    /** The card of the work [key] as the list shows it now; 404 when there is none. */
    private fun card(key: ItemKey): ResponseEntity<ItemSummary> =
        cards.cards(stores.typeOf(key.source), listOf(key)).firstOrNull()?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
}
