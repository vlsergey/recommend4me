package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.work.WorkClusters
import java.time.Instant
import kotlin.concurrent.withLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/** How many works have a site's tag: all of them, the graded, the liked. */
class Uses {
    var works = 0
    var graded = 0
    var liked = 0
}

/** The user merged, named, linked or told apart tags of a content type: what the works have changed. */
data class TagsChanged(val type: String)

/** A site's tag bound to a normalized tag: its source, the site's key of it, its name there, its works. */
class TagVariant(val site: SiteTagRef, val name: String, val uses: Uses)

/**
 * A normalized tag: its key (its id written out), the sites' tags bound to it, its works, and the graded works the user
 * said have it not ([rejected]). A work is in the sets once, by the item that names it ([WorkClusters.representative]),
 * whatever items of it have the tag.
 */
class TagStats(val key: String) {
    val variants = LinkedHashMap<SiteTagRef, TagVariant>()
    val items = HashSet<ItemKey>()
    val graded = HashSet<ItemKey>()
    val liked = HashSet<ItemKey>()
    val rejected = HashSet<ItemKey>()

    /**
     * How many works have it, graded and liked among them: of the sets when counted, as kept when read ([Tags.stats]).
     */
    var counts = Counts(0, 0, 0)

    /** The sources whose site's tags are bound to it. */
    var sources: List<String> = emptyList()

    val id: Long
        get() = TagRepository.idOf(key)!!

    fun variant(site: SiteTagRef, name: String) = variants.getOrPut(site) { TagVariant(site, name, Uses()) }
}

/**
 * A tag named, with the tags directly above and under it, named, the entries of the catalogues it is bound to, the one
 * bound first first, and what its sites' tags' own pages say it means ([descriptions], read for one tag alone) — and
 * the sites whose word on it is asked of the browser, not come yet ([awaitedDescriptions]).
 */
class NamedTag(
    val stats: TagStats,
    val name: ShownName,
    val explicit: String?,
    val parents: List<Pair<String, String>> = emptyList(),
    val children: List<Pair<String, String>> = emptyList(),
    val entries: List<BoundEntry> = emptyList(),
    val descriptions: List<SiteDescription> = emptyList(),
    val awaitedDescriptions: List<String> = emptyList(),
    val offers: List<CatalogueOffer> = emptyList(),
    /** The user ignores it: kept with its works, not read by the model of the grades. */
    val ignored: Boolean = false,
    /** It keeps its works out of the library ([KeptOutWorks]). */
    val keepsOut: Boolean = false,
)

/** The entry of a catalogue a tag is bound to, as the catalogue gave it when last asked. */
class BoundEntry(val catalogue: String, val entry: TagEntry) {
    /** Every name of it in every language kept: the label and the aliases — a tag is found by any of them. */
    val names: List<String>
        get() = (entry.labels.values + entry.aliases.values.flatten()).distinct()
}

/** What a site says one of its tags means: the site's tag and the text. */
class SiteDescription(val site: SiteTagRef, val text: String)

/**
 * What the sites say a tag's sites' tags mean ([read]), and the sites asked for their word, not come yet ([awaited]).
 */
class SiteDescriptions(val read: List<SiteDescription> = emptyList(), val awaited: List<String> = emptyList())

/** An entry a catalogue found, and the tags bound to it already: their keys and names. */
class FoundEntry(val catalogue: String, val entry: TagEntry, val tags: List<Pair<String, String>>)

/** What the user said a tag is to another. */
enum class TagRelation {
    NONE,

    /** One tag: a site's tag bound to it, or a tag merged into it. */
    MERGED,

    /** Under it: a work with this one has the other. */
    CHILD,

    /** Above it: a work with the other has this one. */
    PARENT,

    /** A different tag, not offered as alike. */
    DIFFERENT,

    /** Under it through other tags: read only — said of the tags between. */
    DESCENDANT,

    /** Above it through other tags: read only — said of the tags between. */
    ANCESTOR,
}

/**
 * THE NORMALIZED TAGS OF A CONTENT TYPE ([TagRepository]), AS READ. Every site's tag is bound to a normalized tag
 * ([TagNormalization]); the user merges, names and links them ([TagEditing]), binds them to the entries of catalogues
 * ([TagEntries]); the application only offers tags alike by the meaning of their names ([SimilarTags]) and the offers
 * of their graph ([HierarchyOffers]); it merges nothing by itself.
 *
 * A tag is shown by the name the user gave it, else by its most used name on the sites ([TagNaming]): worked out after
 * the works and their grades change and kept with its statistics ([TagRenaming]); nothing is kept in memory.
 */
@Service
class Tags(
    private val normalization: TagNormalization,
    private val events: ApplicationEventPublisher,
    private val plugins: Plugins,
    /** The languages a tag's entry is asked in, the user's first: its label in the first that has one names the tag. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) {

    /** What the tags are read over: every source of every type with a layer of tags; null when none has one. */
    fun scope(): TagScope? = normalization.scope()

    /**
     * Every normalized tag as kept in the dictionaries: its sites' tags with their names and works, its works and its
     * sources as last counted ([TagRenaming], in the background) — no work read now, no sets of works.
     */
    fun stats(scope: TagScope): Map<String, TagStats> {
        val out = HashMap<String, TagStats>()
        scope.tags.stats.siteTags().forEach { r ->
            val t = out.getOrPut(TagRepository.key(r.normalized)) { TagStats(TagRepository.key(r.normalized)) }
            // A site's tag no work has now is no variant of the tag, as of the counting
            if (r.name != null)
                t.variants[r.site] =
                    TagVariant(
                        r.site,
                        r.name,
                        Uses().apply {
                            works = r.counts.works
                            graded = r.counts.graded
                            liked = r.counts.liked
                        },
                    )
        }
        scope.tags.stats.counts().forEach { (id, c) ->
            out.getOrPut(TagRepository.key(id)) { TagStats(TagRepository.key(id)) }
                .apply {
                    counts = c.counts
                    sources = c.sources
                }
        }
        scope.tags.ids().forEach { id -> out.getOrPut(TagRepository.key(id)) { TagStats(TagRepository.key(id)) } }
        return out
    }

    /** The works of the tags [keys]: by a site's tag bound to them, or by the user's word — a read of those alone. */
    fun itemsOf(scope: TagScope, facet: String, keys: Collection<String>): Set<ItemKey> {
        val ids = keys.mapNotNull(TagRepository::idOf).toSet()
        if (ids.isEmpty()) return emptySet()
        val wanted = keys.toSet()
        val sites = scope.tags.bindings().filterValues { it in ids }.keys.groupBy({ it.source }, { it.tag })
        return scope.sources
            .flatMap { s ->
                val bySite =
                    normalization
                        .siteFacetOf(s, facet)
                        ?.let { f -> sites[s.id]?.let { s.items.itemsWith(f, it).values.flatten() } }
                        .orEmpty()
                val byUser =
                    s.corrections
                        .allFacets()
                        .filter { it.facet == facet && it.added && it.key in wanted }
                        .map { it.itemId }
                (bySite + byUser).map { ItemKey(s.id, it) }
            }
            .toSet()
    }

    /** What every tag of one reading is named: of [stats] — of those kept when not given. */
    fun naming(scope: TagScope, stats: Map<String, TagStats> = stats(scope)) =
        Naming(
            stats,
            scope.tags.stats.explicitNames(),
            boundEntries(scope),
            TagHierarchy(scope.tags.relations.parents()),
            scope.tags.ignored(),
            scope.tags.keepOut.tags(),
            languages,
        )

    /**
     * The entries of the catalogues every tag is bound to, by the tag's id: the bindings and the entries kept, read at
     * once.
     */
    private fun boundEntries(scope: TagScope): Map<Long, List<BoundEntry>> {
        val refs = scope.tags.entries.entriesOfTags()
        val kept = scope.tags.entries.keptEntries(refs.values.flatten().toSet())
        return refs.mapValues { (_, list) ->
            list.mapNotNull { ref -> kept[ref]?.let { BoundEntry(ref.catalogue, it) } }
        }
    }

    /**
     * What the sites' own pages say the sites' tags of [t] mean, in the order of its sites' tags. A site's tag not
     * described yet is asked of the browser, once
     * ([Source.describing][io.github.vlsergey.recommend4me.source.Source.describing]): the request is written, not
     * waited for — the sites whose word is awaited told.
     */
    private fun descriptionsOf(scope: TagScope, facet: String, t: TagStats): SiteDescriptions {
        val bySource = t.variants.keys.groupBy { it.source }
        val awaited = ArrayList<String>()
        val read =
            scope.sources
                .filter { it.id in bySource }
                .flatMap { s ->
                    val siteFacet = normalization.siteFacetOf(s, facet) ?: return@flatMap emptyList()
                    val tags = bySource.getValue(s.id).map { it.tag }
                    val found = s.items.facetDescriptions(siteFacet, tags)
                    val wanted = s.source.describing(siteFacet, tags.filter { it !in found })
                    if (wanted.isNotEmpty() && s.writing.withLock { s.wanted.want(wanted, Instant.now()) }.isNotEmpty())
                        awaited += s.id
                    found.map { (tag, text) -> SiteTagRef(s.id, tag) to text }
                }
                .toMap()
        return SiteDescriptions(
            t.variants.keys.mapNotNull { site -> read[site]?.let { SiteDescription(site, it) } },
            awaited,
        )
    }

    /**
     * The tags of a type with their names, the most used first; [query] — of a name of theirs on a site, the user's or
     * its entry's, any case; [with] — the sites a tag is used on and the catalogues it is bound to an entry of, every
     * one; [without] — none of them ([sources]); [ignored] — the tags the user ignores alone, or those not; either when
     * null.
     */
    fun list(
        scope: TagScope,
        query: String?,
        with: Set<String> = emptySet(),
        without: Set<String> = emptySet(),
        ignored: Boolean? = null,
    ): List<NamedTag> {
        val n = naming(scope)
        val wanted = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return n.stats.values
            .filter { t -> wanted == null || n.allNames(t).any { it.lowercase().contains(wanted) } }
            .map(n::named)
            .filter { t ->
                val from = t.stats.sources.toSet() + t.entries.map { it.catalogue }
                (ignored == null || t.ignored == ignored) && from.containsAll(with) && from.none { it in without }
            }
            .sortedWith(compareByDescending<NamedTag> { it.stats.counts.works }.thenBy { it.name.name.lowercase() })
    }

    /**
     * What a tag may come from: the sites of every type that have the free tags, then the catalogues tags are bound to
     * entries of.
     */
    fun sources(scope: TagScope, facet: String): List<String> =
        scope.sources.filter { normalization.siteFacetOf(it, facet) != null }.map { it.id } +
            plugins.tagCatalogues.map { it.id }

    /** The tag [tag] named, with what its sites say it means and the other tags the catalogues offer above or under. */
    fun one(scope: TagScope, facet: String, tag: String): NamedTag? {
        val n = naming(scope)
        return n.stats[tag]?.let {
            n.named(it, descriptionsOf(scope, facet, it), CatalogueGraph(scope, n, languages).offersOf(tag))
        }
    }

    /** The works [items] read again — their chances, their search — and the model of the grades trained again. */
    fun told(scope: TagScope, items: Collection<ItemKey>) {
        items.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            events.publishEvent(ItemsChanged(source, ids))
        }
        scope.types.forEach { events.publishEvent(TagsChanged(it)) }
    }

    companion object {
        /** A grade above the middle of the scale: of five, 4 and 5. */
        fun liked(grade: Int): Boolean = grade > (Grades.MIN + Grades.MAX) / 2.0
    }
}
