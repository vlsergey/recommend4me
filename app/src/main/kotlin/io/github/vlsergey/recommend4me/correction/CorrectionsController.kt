package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.api.CorrectionsApi
import io.github.vlsergey.recommend4me.api.model.FacetAnswer
import io.github.vlsergey.recommend4me.api.model.FieldCorrection
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemDetailsReader
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import org.springframework.context.ApplicationEventPublisher
import io.github.vlsergey.recommend4me.tag.Tags
import io.github.vlsergey.recommend4me.work.WorkEvents
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import io.github.vlsergey.recommend4me.api.model.FacetCorrection as ApiFacetCorrection

private fun noField(store: SourceStore, field: String) =
    Refusal.NO_FIELD.refuse(HttpStatus.BAD_REQUEST, "The source ${store.id} has no field '$field'", "field" to field)

/** The site's word is shown and read as it is: only the application's layer over it is corrected. */
private fun siteField(store: SourceStore, def: FacetDef) = Refusal.SITE_FIELD.refuse(
    HttpStatus.BAD_REQUEST, "The field '${def.key}' of ${store.id} is the site's word, it is not corrected", "field" to I18nKeys.facetLabel(store.id, def),
)

/**
 * The user's corrections of an item. Every correction is a change of the item's searchable data —
 * its texts are encoded again and it is indexed again — and of what the model reads.
 */
@RestController
class CorrectionsController(
    private val stores: Stores,
    private val details: ItemDetailsReader,
    private val recommendations: Recommendations,
    private val suggestions: FacetSuggestions,
    private val events: ApplicationEventPublisher,
    private val workEvents: WorkEvents,
    private val tags: Tags,
    private val answering: Answering,
) : CorrectionsApi {

    private fun corrected(store: SourceStore, item: String): ResponseEntity<ItemDetails> {
        events.publishEvent(ItemsChanged(store.id, listOf(item)))
        recommendations.scheduleRetrain(store.type, "correction")
        // Its chances made of the change as the work is read
        return details.read(ItemKey(store.id, item))?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    /**
     * An answer on a facet: [change] written, and nothing else — nothing of the work read back: a
     * second answer right after the first waits for no reading. The page shows the answer once it
     * is kept and reads the work anew once the answers have settled; its chances are made of the
     * answer when it is read ([FacetSuggestions.chances]).
     */
    private fun answered(store: SourceStore, item: String, facet: String, change: () -> Unit) = answering.answered(store, item, listOf(facet), change)

    private fun itemOf(source: String, item: String): SourceStore? =
        stores.source(source)?.takeIf { it.items.find(item) != null }

    override fun correctFacet(source: String, item: String, facetCorrection: ApiFacetCorrection): ResponseEntity<FacetAnswer> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        val c = facetCorrection
        val def = store.schema.facet(c.facet) ?: throw noField(store, c.facet)
        // The site's word is shown and read as it is: only the application's layer over it is corrected
        if (!def.editable) throw siteField(store, def)
        val name = c.name?.trim()?.takeIf { it.isNotEmpty() }
        // An entry of a catalogue offered as a new tag: the tag bound to it, made of it when there is none
        val ofEntry = c.entry?.takeIf { c.facet in store.tagFacets }?.let { e ->
            val scope = tags.scope() ?: throw Refusal.NO_VALUE.refuse(HttpStatus.BAD_REQUEST, "No tags to make one of ${e.catalogue}:${e.entry}")
            try {
                tags.tagOfEntry(scope, c.facet, e.catalogue, e.entry)
            } catch (x: NoSuchElementException) {
                throw Refusal.NO_VALUE.refuse(HttpStatus.BAD_REQUEST, x.message ?: "No entry ${e.catalogue}:${e.entry}")
            }
        }
        val key = ofEntry ?: c.key?.trim()?.takeIf { it.isNotEmpty() }
            ?: name?.let { FacetValues.keyOf(store, c.facet, it) }
            ?: throw Refusal.NO_VALUE.refuse(HttpStatus.BAD_REQUEST, "Neither a key nor a name of a value of '${c.facet}' given")
        if (!c.added) answering.refuseImplied(store, item, c.facet, key)
        answered(store, item, c.facet) {
            // A name is kept for a value the site does not name: one the user made up
            val known = store.names(c.facet, listOf(key)).isNotEmpty()
            answering.write(store, item, c.facet, key, c.added, name.takeIf { !known })
        }
        return ResponseEntity.ok(FacetAnswer(c.facet, key, name ?: ofEntry?.let { store.names(c.facet, listOf(it))[it] }))
    }

    override fun uncorrectFacet(source: String, item: String, facet: String, key: String): ResponseEntity<Unit> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        answered(store, item, facet) { store.corrections.unsetFacet(item, facet, key) }
        return ResponseEntity.noContent().build()
    }

    override fun completeFacet(source: String, item: String, facet: String): ResponseEntity<Unit> = complete(source, item, facet, true)

    override fun uncompleteFacet(source: String, item: String, facet: String): ResponseEntity<Unit> = complete(source, item, facet, false)

    /** The user's word that the item's values of the [facet] are all named, or its taking back; the facet's chances made current apart. */
    private fun complete(source: String, item: String, facet: String, complete: Boolean): ResponseEntity<Unit> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        val def = store.schema.facet(facet) ?: throw noField(store, facet)
        if (!def.editable) throw siteField(store, def)
        answered(store, item, facet) { store.corrections.setComplete(item, facet, complete, Instant.now()) }
        return ResponseEntity.noContent().build()
    }

    override fun correctField(source: String, item: String, field: String, fieldCorrection: FieldCorrection): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        if (!knownField(store, field)) throw noField(store, field)
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
        field.startsWith("text:") -> store.schema.text(field.removePrefix("text:")) != null
        field.startsWith("number:") -> store.schema.number(field.removePrefix("number:")) != null
        else -> false
    }

    override fun linkItem(source: String, item: String, itemRef: ItemRef): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        val other = itemOf(itemRef.source, itemRef.item)
            ?: throw Refusal.NO_SUCH_WORK.refuse(HttpStatus.BAD_REQUEST, "There is no work ${itemRef.source}/${itemRef.item}")
        if (other.type != store.type) {
            throw Refusal.JOIN_TYPES.refuse(HttpStatus.BAD_REQUEST, "A work of ${store.type} is not joined with one of ${other.type}")
        }
        if (other.id == store.id && itemRef.item == item) {
            throw Refusal.JOIN_SELF.refuse(HttpStatus.BAD_REQUEST, "The work $source/$item is not joined with itself")
        }
        stores.typeOf(source).links.link(ItemKey(source, item), ItemKey(itemRef.source, itemRef.item), Instant.now())
        workEvents.merged(ItemKey(source, item))
        return corrected(store, item)
    }

    override fun unlinkItem(source: String, item: String, otherSource: String, otherItem: String): ResponseEntity<ItemDetails> {
        val store = itemOf(source, item) ?: return ResponseEntity.notFound().build()
        stores.typeOf(source).links.unlink(ItemKey(source, item), ItemKey(otherSource, otherItem), Instant.now())
        return corrected(store, item)
    }
}
