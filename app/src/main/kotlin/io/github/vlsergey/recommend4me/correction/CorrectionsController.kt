package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.api.CorrectionsApi
import io.github.vlsergey.recommend4me.api.model.FieldCorrection
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.item.ItemDetailsReader
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import io.github.vlsergey.recommend4me.api.model.FacetCorrection as ApiFacetCorrection

/**
 * The user's corrections of an item. Every correction is a change of the item's searchable data —
 * its texts are encoded again and it is indexed again — and of what the model reads.
 */
@RestController
class CorrectionsController(
    private val stores: Stores,
    private val details: ItemDetailsReader,
    private val recommendations: Recommendations,
    private val events: ApplicationEventPublisher,
) : CorrectionsApi {

    private fun corrected(store: SourceStore, item: String): ResponseEntity<ItemDetails> {
        events.publishEvent(ItemsChanged(store.id, listOf(item)))
        recommendations.scheduleRetrain(store.type)
        return details.read(ItemKey(store.id, item))?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    private fun itemOf(source: String, item: String): SourceStore? =
        stores.source(source)?.takeIf { it.items.find(item) != null }

    override fun correctFacet(source: String, item: String, facetCorrection: ApiFacetCorrection): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        val c = facetCorrection
        if (store.source.schema.facet(c.facet) == null || c.key.isBlank()) return ResponseEntity.badRequest().build()
        store.corrections.setFacet(item, c.facet, c.key.trim(), c.added, c.name?.trim()?.takeIf { it.isNotEmpty() }, Instant.now())
        return corrected(store, item)
    }

    override fun uncorrectFacet(source: String, item: String, facet: String, key: String): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        store.corrections.unsetFacet(item, facet, key)
        return corrected(store, item)
    }

    override fun correctField(source: String, item: String, field: String, fieldCorrection: FieldCorrection): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        if (!knownField(store, field)) return ResponseEntity.badRequest().build()
        store.corrections.setField(item, field, fieldCorrection.content, Instant.now())
        return corrected(store, item)
    }

    override fun uncorrectField(source: String, item: String, field: String): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        store.corrections.unsetField(item, field)
        return corrected(store, item)
    }

    /** "title", "text:<a text of the source>", "number:<a number of the source>". */
    private fun knownField(store: SourceStore, field: String): Boolean = when {
        field == Corrected.TITLE -> true
        field.startsWith("text:") -> store.source.schema.text(field.removePrefix("text:")) != null
        field.startsWith("number:") -> store.source.schema.number(field.removePrefix("number:")) != null
        else -> false
    }

    override fun linkItem(source: String, item: String, itemRef: ItemRef): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        val other = itemOf(itemRef.source, itemRef.item) ?: return ResponseEntity.badRequest().build()
        if (other.type != store.type || (other.id == store.id && itemRef.item == item)) return ResponseEntity.badRequest().build()
        if (other.id == store.id) store.corrections.link(item, itemRef.item, Instant.now())
        else stores.typeOf(source).links.link(ItemKey(source, item), ItemKey(itemRef.source, itemRef.item), Instant.now())
        return corrected(store, item)
    }

    override fun unlinkItem(source: String, item: String, otherSource: String, otherItem: String): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        if (otherSource == source) store.corrections.unlink(item, otherItem)
        else stores.typeOf(source).links.unlink(ItemKey(source, item), ItemKey(otherSource, otherItem))
        return corrected(store, item)
    }
}
