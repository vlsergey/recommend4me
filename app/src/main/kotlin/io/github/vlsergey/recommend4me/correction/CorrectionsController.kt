package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.api.CorrectionsApi
import io.github.vlsergey.recommend4me.api.model.FacetAnswer
import io.github.vlsergey.recommend4me.api.model.FacetCorrection as ApiFacetCorrection
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
import io.github.vlsergey.recommend4me.tag.Tags
import io.github.vlsergey.recommend4me.work.WorkEvents
import java.time.Instant
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

private fun noField(store: SourceStore, field: String) =
    Refusal.NO_FIELD.refuse(HttpStatus.BAD_REQUEST, "The source ${store.id} has no field '$field'", "field" to field)

/** The site's word is shown and read as it is: only the application's layer over it is corrected. */
private fun siteField(store: SourceStore, def: FacetDef) =
    Refusal.SITE_FIELD.refuse(
        HttpStatus.BAD_REQUEST,
        "The field '${def.key}' of ${store.id} is the site's word, it is not corrected",
        "field" to I18nKeys.facetLabel(store.id, def),
    )

/** The source [source] when it has the item [item]; null when it has not. */
private fun Stores.withItem(source: String, item: String): SourceStore? =
    source(source)?.takeIf { it.items.find(item) != null }

/**
 * The [facet] of the [store] as the user answers on it; refused when the source has no such facet, or it is the site's
 * word — shown and read as it is: only the application's layer over it is corrected.
 */
private fun editableFacet(store: SourceStore, facet: String): FacetDef {
    val def = store.schema.facet(facet) ?: throw noField(store, facet)
    if (!def.editable) throw siteField(store, def)
    return def
}

/**
 * The tag an entry of a catalogue ([c] offered it as a new tag) is bound to, made of it when there is none; null when
 * the answer names no entry, or its facet is not of tags.
 */
private fun tagOfEntry(tags: Tags, store: SourceStore, c: ApiFacetCorrection): String? =
    c.entry
        ?.takeIf { c.facet in store.tagFacets }
        ?.let { e ->
            val scope =
                tags.scope()
                    ?: throw Refusal.NO_VALUE.refuse(
                        HttpStatus.BAD_REQUEST,
                        "No tags to make one of ${e.catalogue}:${e.entry}",
                    )
            try {
                tags.tagOfEntry(scope, c.facet, e.catalogue, e.entry)
            } catch (x: NoSuchElementException) {
                throw Refusal.NO_VALUE.refuse(HttpStatus.BAD_REQUEST, x.message ?: "No entry ${e.catalogue}:${e.entry}")
                    .apply { initCause(x) }
            }
        }

/** "title", "text:<a text of the source>", "number:<a number of the source>". */
private fun knownField(store: SourceStore, field: String): Boolean =
    when {
        field == Corrected.TITLE -> true
        field.startsWith("text:") -> store.schema.text(field.removePrefix("text:")) != null
        field.startsWith("number:") -> store.schema.number(field.removePrefix("number:")) != null
        else -> false
    }

/**
 * The work [other] of a source is joined with the work [item] of the [store]: refused of two content types, and of a
 * work with itself.
 */
private fun refuseJoin(store: SourceStore, item: String, other: SourceStore, otherItem: String) {
    if (other.type != store.type) {
        throw Refusal.JOIN_TYPES.refuse(
            HttpStatus.BAD_REQUEST,
            "A work of ${store.type} is not joined with one of ${other.type}",
        )
    }
    if (other.id == store.id && otherItem == item) {
        throw Refusal.JOIN_SELF.refuse(HttpStatus.BAD_REQUEST, "The work ${store.id}/$item is not joined with itself")
    }
}

/**
 * The user's corrections of an item. Every correction of its fields and links is a change of the item's searchable data
 * — its texts are encoded again and it is indexed again — and of what the model reads. An answer on a facet is written,
 * and nothing else — nothing of the work read back: a second answer right after the first waits for no reading. The
 * page shows the answer once it is kept and reads the work anew once the answers have settled; its chances are made of
 * the answer when it is read ([FacetSuggestions.chances]).
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

    override fun correctFacet(
        source: String,
        item: String,
        facetCorrection: ApiFacetCorrection,
    ): ResponseEntity<FacetAnswer> {
        val store = stores.withItem(source, item) ?: return ResponseEntity.notFound().build()
        val c = facetCorrection
        editableFacet(store, c.facet)
        val name = c.name?.trim()?.takeIf { it.isNotEmpty() }
        // An entry of a catalogue offered as a new tag: the tag bound to it, made of it when there is none
        val ofEntry = tagOfEntry(tags, store, c)
        val key =
            ofEntry
                ?: c.key?.trim()?.takeIf { it.isNotEmpty() }
                ?: name?.let { FacetValues.keyOf(store, c.facet, it) }
                ?: throw Refusal.NO_VALUE.refuse(
                    HttpStatus.BAD_REQUEST,
                    "Neither a key nor a name of a value of '${c.facet}' given",
                )
        if (!c.added) answering.refuseImplied(store, item, c.facet, key)
        answering.answered(store, item, listOf(c.facet)) {
            // A name is kept for a value the site does not name: one the user made up
            val known = store.names(c.facet, listOf(key)).isNotEmpty()
            answering.write(store, item, c.facet, key, c.added, name.takeIf { !known })
        }
        return ResponseEntity.ok(
            FacetAnswer(c.facet, key, name ?: ofEntry?.let { store.names(c.facet, listOf(it))[it] })
        )
    }

    override fun uncorrectFacet(source: String, item: String, facet: String, key: String): ResponseEntity<Unit> {
        val store = stores.withItem(source, item) ?: return ResponseEntity.notFound().build()
        answering.answered(store, item, listOf(facet)) { store.corrections.unsetFacet(item, facet, key) }
        return ResponseEntity.noContent().build()
    }

    override fun completeFacet(source: String, item: String, facet: String): ResponseEntity<Unit> =
        complete(source, item, facet, true)

    override fun uncompleteFacet(source: String, item: String, facet: String): ResponseEntity<Unit> =
        complete(source, item, facet, false)

    /**
     * The user's word that the item's values of the [facet] are all named, or its taking back; the facet's chances made
     * current apart.
     */
    private fun complete(source: String, item: String, facet: String, complete: Boolean): ResponseEntity<Unit> {
        val store = stores.withItem(source, item) ?: return ResponseEntity.notFound().build()
        editableFacet(store, facet)
        answering.answered(store, item, listOf(facet)) {
            store.corrections.setComplete(item, facet, complete, Instant.now())
        }
        return ResponseEntity.noContent().build()
    }

    override fun correctField(
        source: String,
        item: String,
        field: String,
        fieldCorrection: FieldCorrection,
    ): ResponseEntity<ItemDetails> {
        val store = stores.withItem(source, item) ?: return ResponseEntity.notFound().build()
        if (!knownField(store, field)) throw noField(store, field)
        store.corrections.setField(item, field, fieldCorrection.content, Instant.now())
        return corrected(store, item)
    }

    override fun uncorrectField(source: String, item: String, field: String): ResponseEntity<ItemDetails> {
        val store = stores.withItem(source, item) ?: return ResponseEntity.notFound().build()
        store.corrections.unsetField(item, field)
        return corrected(store, item)
    }

    override fun linkItem(source: String, item: String, itemRef: ItemRef): ResponseEntity<ItemDetails> {
        val store = stores.withItem(source, item) ?: return ResponseEntity.notFound().build()
        val other =
            stores.withItem(itemRef.source, itemRef.item)
                ?: throw Refusal.NO_SUCH_WORK.refuse(
                    HttpStatus.BAD_REQUEST,
                    "There is no work ${itemRef.source}/${itemRef.item}",
                )
        refuseJoin(store, item, other, itemRef.item)
        stores.typeOf(source).links.link(ItemKey(source, item), ItemKey(itemRef.source, itemRef.item), Instant.now())
        workEvents.merged(ItemKey(source, item))
        return corrected(store, item)
    }

    override fun unlinkItem(
        source: String,
        item: String,
        otherSource: String,
        otherItem: String,
    ): ResponseEntity<ItemDetails> {
        val store = stores.withItem(source, item) ?: return ResponseEntity.notFound().build()
        stores.typeOf(source).links.unlink(ItemKey(source, item), ItemKey(otherSource, otherItem), Instant.now())
        return corrected(store, item)
    }
}
