package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.api.TagCataloguesApi
import io.github.vlsergey.recommend4me.api.model.FoundTagEntry
import io.github.vlsergey.recommend4me.api.model.HierarchyOffer as ApiHierarchyOffer
import io.github.vlsergey.recommend4me.api.model.HierarchyOfferKind
import io.github.vlsergey.recommend4me.api.model.HierarchyOffers as ApiHierarchyOffers
import io.github.vlsergey.recommend4me.api.model.TagAboveRequest
import io.github.vlsergey.recommend4me.api.model.TagEntryRequest
import io.github.vlsergey.recommend4me.api.model.TagInfo
import io.github.vlsergey.recommend4me.api.model.TagRef
import io.github.vlsergey.recommend4me.api.model.TooGeneralRequest
import java.time.Instant
import org.jooq.exception.IntegrityConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

private val log = LoggerFactory.getLogger(TagCataloguesController::class.java)

/** The entries of the catalogues the tags are bound to, and the offers of the tags' graph by them. */
@RestController
class TagCataloguesController(
    private val tags: Tags,
    private val entries: TagEntries,
    private val offers: HierarchyOffers,
    /** The user's languages: of an entry's texts, those of the first that has them are shown. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) : TagCataloguesApi {

    override fun findTagEntries(query: String): ResponseEntity<List<FoundTagEntry>> {
        val t = tags.requiredScope()
        return ResponseEntity.ok(
            entries.findEntries(t, query).map { f ->
                FoundTagEntry(
                    f.catalogue,
                    f.entry.id,
                    f.entry.url,
                    f.tags.map { (key, name) -> TagRef(key, name) },
                    f.entry.labels.inFirstOf(languages),
                    f.entry.descriptions.inFirstOf(languages),
                )
            }
        )
    }

    override fun bindTagEntry(tagEntryRequest: TagEntryRequest): ResponseEntity<TagInfo> {
        val t = tags.requiredScope()
        val r = tagEntryRequest
        return orNotFound { entries.bindEntry(t, t.facet, r.tag, r.catalogue, r.entry, r.bound) }
    }

    override fun listHierarchyOffers(): ResponseEntity<ApiHierarchyOffers> {
        val t = tags.requiredScope()
        val read = offers.kept(t)
        val (names, kept) = offers.namesOf(t, read.offers)
        fun label(ref: TagEntryRef) = kept[ref]?.labels?.inFirstOf(languages) ?: ref.entry
        return ResponseEntity.ok(
            ApiHierarchyOffers(
                pending = read.pending,
                offers =
                    read.offers.map { o ->
                        ApiHierarchyOffer(
                            kind = HierarchyOfferKind.valueOf(o.kind.name),
                            catalogue = o.catalogue,
                            tags = o.tags.map { TagRef(it, names[it] ?: it) },
                            path = o.way.map(::label),
                            graded = o.graded,
                            levels = o.levels,
                            existing = o.existing?.let { TagRef(it, names[it] ?: it) },
                            rejected = o.rejected,
                            entry = o.entry?.entry,
                            url = o.entry?.let { kept[it]?.url },
                        )
                    },
            )
        )
    }

    override fun judgeTagEntryTooGeneral(tooGeneralRequest: TooGeneralRequest): ResponseEntity<Unit> {
        val t = tags.requiredScope()
        val r = tooGeneralRequest
        return try {
            t.tags.entries.setTooGeneral(TagEntryRef(r.catalogue, r.entry), r.tooGeneral, Instant.now())
            ResponseEntity.noContent().build()
        } catch (e: IntegrityConstraintViolationException) {
            // An entry never read is none the offers are of
            log.debug("{}:{} is no entry read", r.catalogue, r.entry, e)
            ResponseEntity.notFound().build()
        }
    }

    override fun createTagAbove(tagAboveRequest: TagAboveRequest): ResponseEntity<TagInfo> {
        val t = tags.requiredScope()
        val r = tagAboveRequest
        return orNotFound { entries.createAbove(t, t.facet, r.catalogue, r.entry, r.children, r.tag) }
    }

    override fun refreshTagEntries(tag: String): ResponseEntity<TagInfo> {
        val t = tags.requiredScope()
        return orNotFound { entries.refreshEntries(t, t.facet, tag) }
    }

    /** The tag [act] gives back; not found when it gives none, or when a catalogue or its entry is not there. */
    private fun orNotFound(act: () -> NamedTag?): ResponseEntity<TagInfo> =
        try {
            act()?.let { ResponseEntity.ok(it.toApi(languages)) } ?: ResponseEntity.notFound().build()
        } catch (e: NoSuchElementException) {
            log.debug("Not found: {}", e.message, e)
            ResponseEntity.notFound().build()
        }
}
