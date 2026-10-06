package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.ItemsApi
import io.github.vlsergey.recommend4me.api.model.FacetFilter
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.ItemPage
import io.github.vlsergey.recommend4me.api.model.ItemSort
import io.github.vlsergey.recommend4me.api.model.ItemSummary
import io.github.vlsergey.recommend4me.api.model.Mark
import io.github.vlsergey.recommend4me.api.model.MatchFeedbackRequest
import io.github.vlsergey.recommend4me.api.model.PartText
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.mark.MarkKind
import io.github.vlsergey.recommend4me.mark.MarksChanged
import io.github.vlsergey.recommend4me.mark.ThingRef
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.picture.PictureService
import io.github.vlsergey.recommend4me.picture.Served
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectors
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Duration
import java.time.Instant
import io.github.vlsergey.recommend4me.api.model.ItemView as ApiItemView

private val log = LoggerFactory.getLogger(ItemsController::class.java)

@RestController
class ItemsController(
    private val stores: Stores,
    private val list: ItemList,
    private val cards: ItemCards,
    private val details: ItemDetailsReader,
    private val pictures: PictureService,
    private val contexts: SourceContexts,
    private val textVectors: TextVectors,
    private val recommendations: Recommendations,
    private val events: ApplicationEventPublisher,
) : ItemsApi {

    override fun listItems(
        type: String, view: ApiItemView, sort: ItemSort, sortNumber: String?, search: String?,
        hidden: List<String>?, hiddenWithout: List<String>?, source: List<String>?, offset: Int, limit: Int,
    ): ResponseEntity<ItemPage> = ResponseEntity.ok(
        list.page(
            type, ItemView.valueOf(view.name), ItemOrder.valueOf(sort.name), sortNumber, search,
            hidden.orEmpty().toSet(), hiddenWithout.orEmpty().toSet(), source?.toSet()?.takeIf { it.isNotEmpty() }, offset, limit,
        ),
    )

    override fun listFacets(type: String): ResponseEntity<List<FacetFilter>> = ResponseEntity.ok(list.facets(type))

    override fun getItem(source: String, item: String): ResponseEntity<ItemDetails> =
        details.read(ItemKey(source, item))?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun refreshItem(source: String, item: String): ResponseEntity<ItemDetails> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.items.find(item) == null) return ResponseEntity.notFound().build()
        if (store.source.modes.none { it != SourceMode.BROWSER }) return ResponseEntity.status(HttpStatus.CONFLICT).build()
        try {
            if (!store.source.refresh(item, contexts.of(source))) return ResponseEntity.status(HttpStatus.CONFLICT).build()
        } catch (e: Exception) {
            log.warn("{}: {} was not loaded: {}", source, item, e.message)
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build()
        }
        textVectors.refresh(store, only = listOf(item))
        recommendations.scoreItem(ItemKey(source, item))
        return getItem(source, item)
    }

    override fun rateItem(source: String, item: String, ratingRequest: RatingRequest): ResponseEntity<ItemSummary> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val head = store.items.find(item) ?: return ResponseEntity.notFound().build()
        if (ratingRequest.grade !in Grades.RANGE) return ResponseEntity.badRequest().build()
        store.ratings.rate(item, head.version, ratingRequest.grade, Instant.now())
        events.publishEvent(GradesChanged(store.type, source, item))
        return card(ItemKey(source, item))
    }

    override fun unrateItem(source: String, item: String): ResponseEntity<ItemSummary> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val head = store.items.find(item) ?: return ResponseEntity.notFound().build()
        store.ratings.unrate(item, head.version)
        events.publishEvent(GradesChanged(store.type, source, item))
        return card(ItemKey(source, item))
    }

    private fun card(key: ItemKey): ResponseEntity<ItemSummary> =
        cards.cards(stores.typeOf(key.source), listOf(key)).firstOrNull()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun getPicture(source: String, item: String, position: Int, full: Boolean): ResponseEntity<Resource> =
        when (val served = pictures.serve(ItemKey(source, item), position, full)) {
            null -> ResponseEntity.notFound().build()
            // A picture changes only together with its address, and then the file is replaced
            is Served.File -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(served.contentType))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(FileSystemResource(served.path))
            is Served.Redirect -> ResponseEntity.status(HttpStatus.FOUND).location(URI.create(served.url)).build()
        }

    override fun markPicture(source: String, item: String, position: Int, mark: Mark): ResponseEntity<Unit> {
        if (mark.mark != 1 && mark.mark != -1) return ResponseEntity.badRequest().build()
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val picture = store.pictures.find(item, position, null) ?: return ResponseEntity.notFound().build()
        store.marks.markPicture(item, position, picture.url, mark.mark, Instant.now())
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }

    override fun unmarkPicture(source: String, item: String, position: Int): ResponseEntity<Unit> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        store.marks.markPicture(item, position, "", null, Instant.now())
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }

    override fun markReview(source: String, item: String, reviewId: String, mark: Mark): ResponseEntity<Unit> {
        if (mark.mark != 1 && mark.mark != -1) return ResponseEntity.badRequest().build()
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (reviewId !in store.reviews.ids(item)) return ResponseEntity.notFound().build()
        store.marks.markReview(item, reviewId, mark.mark, Instant.now())
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }

    override fun unmarkReview(source: String, item: String, reviewId: String): ResponseEntity<Unit> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        store.marks.markReview(item, reviewId, null, Instant.now())
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }

    override fun getPart(source: String, item: String, partId: String): ResponseEntity<PartText> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val (part, content) = store.parts.text(item, partId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(PartText(part.partId, part.position, content, part.title))
    }

    override fun giveMatchFeedback(matchFeedbackRequest: MatchFeedbackRequest): ResponseEntity<Unit> {
        val r = matchFeedbackRequest
        if (r.verdict !in -1..1) return ResponseEntity.badRequest().build()
        val store = stores.source(r.markSource) ?: return ResponseEntity.notFound().build()
        store.marks.setFeedback(
            MarkKind.valueOf(r.kind.value),
            ThingRef(ItemKey(r.markSource, r.markItem), r.markRef),
            ThingRef(ItemKey(r.matchSource, r.matchItem), r.matchRef),
            r.verdict.takeIf { it != 0 }, Instant.now(),
        )
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }
}
