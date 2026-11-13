package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.SearchApi
import io.github.vlsergey.recommend4me.api.model.CatalogueEntryRef
import io.github.vlsergey.recommend4me.api.model.FacetValueUse
import io.github.vlsergey.recommend4me.api.model.LinkedItem
import io.github.vlsergey.recommend4me.api.model.SearchJudgment
import io.github.vlsergey.recommend4me.api.model.SearchQuality
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetValues
import io.github.vlsergey.recommend4me.search.SearchJudged
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.FoundEntry
import io.github.vlsergey.recommend4me.tag.NamedTag
import io.github.vlsergey.recommend4me.tag.TagFinding
import io.github.vlsergey.recommend4me.tag.Tags
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * WHAT THE USER FINDS BY WHAT IS TYPED: the works by a title or a page's address, the values of a facet by their names,
 * and the user's word on what the search of the list found, with how well it finds.
 */
@RestController
class SearchController(
    private val stores: Stores,
    private val searchQuality: io.github.vlsergey.recommend4me.search.SearchQuality,
    private val tags: Tags,
    private val tagFinding: TagFinding,
    private val events: ApplicationEventPublisher,
    /** The user's languages: of an entry of a catalogue, its label and description in the first that has them. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) : SearchApi {

    private fun Map<String, String>.first(): String? = languages.firstNotNullOfOrNull { this[it] }

    override fun judgeSearch(type: String, searchJudgment: SearchJudgment): ResponseEntity<Unit> {
        val store = stores.type(type) ?: return ResponseEntity.notFound().build()
        val j = searchJudgment
        store.searchJudgments.judge(j.query, ItemKey(j.source, j.item), j.relevant, Instant.now())
        // The order of what a search finds is learnt of the judgments again, in the background
        events.publishEvent(SearchJudged(type))
        return ResponseEntity.noContent().build()
    }

    override fun getSearchQuality(type: String, top: Int): ResponseEntity<SearchQuality> =
        searchQuality.measure(type, top, LocaleContextHolder.getLocale().language)?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    override fun listFacetValues(
        source: String,
        facet: String,
        query: String?,
        limit: Int,
    ): ResponseEntity<List<FacetValueUse>> {
        val store =
            stores.source(source)?.takeIf { it.schema.facet(facet) != null } ?: return ResponseEntity.notFound().build()
        val piece = query?.trim()?.lowercase().orEmpty()
        val wanted = limit.coerceIn(1, MAX_VALUES)
        val values =
            if (facet in store.tagFacets) tagValues(store, piece, wanted) else siteValues(store, facet, piece, wanted)
        return values?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    /**
     * Of a facet of tags: the normalized tags, found by the names on this site, on any, then by meaning
     * ([TagFinding.find]); when none goes by the name typed, the entries of the catalogues after them — a new tag each
     * ([TagFinding.findOrNew]). Null when there are no tags to find.
     */
    private fun tagValues(store: SourceStore, piece: String, wanted: Int): List<FacetValueUse>? {
        val scope = tags.scope() ?: return null
        fun use(t: NamedTag) = FacetValueUse(t.stats.key, t.name.name, t.stats.counts.works, null)
        // A tag the user ignores is not offered to add
        return if (piece.isEmpty()) tags.list(scope, null, ignored = false).take(wanted).map(::use)
        else
            tagFinding.findOrNew(scope, store.id, piece, wanted).mapNotNull { f ->
                f.tag?.let(::use) ?: f.entry?.let(::newTag)
            }
    }

    /** An entry [e] of a catalogue offered as a new tag. */
    private fun newTag(e: FoundEntry) =
        FacetValueUse(
            NEW_TAG + e.catalogue + ":" + e.entry.id,
            e.entry.labels.first() ?: e.entry.id,
            0,
            null,
            CatalogueEntryRef(e.catalogue, e.entry.id, e.entry.url, e.entry.descriptions.first()),
        )

    /** Of any other facet, its values whose name or key holds the [piece] typed. */
    private fun siteValues(store: SourceStore, facet: String, piece: String, wanted: Int): List<FacetValueUse> =
        FacetValues.of(store, facet)
            .asSequence()
            .filter { piece.isEmpty() || piece in it.name.lowercase() || piece in it.key.lowercase() }
            // A value beginning with what was typed before one only holding it
            .sortedBy { if (it.name.lowercase().startsWith(piece)) 0 else 1 }
            .take(wanted)
            .map { FacetValueUse(it.key, it.name, it.items, it.nameKey) }
            .toList()

    override fun lookupItems(type: String, query: String, limit: Int): ResponseEntity<List<LinkedItem>> {
        val typeStore = stores.type(type) ?: return ResponseEntity.notFound().build()
        val line = query.trim()
        return ResponseEntity.ok(
            if (line.isEmpty()) emptyList() else lookup(typeStore, line, limit.coerceIn(1, MAX_LOOKUP))
        )
    }

    /**
     * The works of the type whose page is at the address [line] — what the user pastes from the site — or titled so.
     */
    private fun lookup(typeStore: TypeStore, line: String, wanted: Int): List<LinkedItem> {
        val found = LinkedHashMap<ItemKey, LinkedItem>()
        // The address of a work's page first: what the user pastes from the site
        if (line.startsWith("http"))
            typeStore.sources.forEach { s ->
                val id = runCatching { s.source.itemIdOf(line) }.getOrNull() ?: return@forEach
                val head = s.items.find(id) ?: return@forEach
                found[ItemKey(s.id, id)] =
                    LinkedItem(s.id, id, Corrected.title(head.title, s.corrections.fieldsOf(id)), head.url)
            }
        typeStore.sources.forEach { s ->
            val titled =
                s.corrections
                    .allFields()
                    .filter { (_, f) -> f[Corrected.TITLE]?.lowercase()?.contains(line.lowercase()) == true }
                    .keys
            (s.items.findByTitle(line, wanted) + s.items.heads(titled).values).forEach { head ->
                found.putIfAbsent(
                    ItemKey(s.id, head.id),
                    LinkedItem(s.id, head.id, Corrected.title(head.title, s.corrections.fieldsOf(head.id)), head.url),
                )
            }
        }
        return found.values.take(wanted)
    }

    private companion object {
        /** The key a new tag of an entry of a catalogue is offered by: no tag's ([FacetValueUse.entry]). */
        const val NEW_TAG = "new:"

        /** The most values of a facet found by a name at once. */
        const val MAX_VALUES = 500

        /** The most works found by a title or an address at once. */
        const val MAX_LOOKUP = 100
    }
}
