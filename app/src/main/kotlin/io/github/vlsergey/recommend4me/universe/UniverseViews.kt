package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.SimilarUniverse
import io.github.vlsergey.recommend4me.api.model.UniverseEntryInfo
import io.github.vlsergey.recommend4me.api.model.UniverseInfo
import io.github.vlsergey.recommend4me.api.model.UniverseRelation
import io.github.vlsergey.recommend4me.api.model.UniverseSite
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagVectors
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import java.time.ZoneOffset
import org.springframework.stereotype.Component

/**
 * THE UNIVERSES AS THE SCREEN OF THE UNIVERSES SHOWS THEM: each with its sites' fandoms and their works, the entries of
 * the catalogue it is bound to, its characters and its place among the others ([all], [one]); and the universes alike
 * one ([similar]). Every part read once for the asking ([Reading]).
 */
@Component
class UniverseViews(private val stores: Stores, private val phrases: PhraseVectors) {

    /** What one asking reads: the dictionary, and of every source with fandoms its fandoms' names and works. */
    private inner class Reading {
        val universes: UniverseRepository = stores.withUniverses().first().universes
        val dictionary: FandomDictionary = universes.dictionary()
        val sites: List<Site> =
            stores
                .withUniverses()
                .flatMap { it.sources }
                .mapNotNull { s ->
                    UniverseFacet.fandomFacet(s.schema)?.let {
                        Site(s, it, s.items.facetNames(it), s.items.valueUses(it))
                    }
                }
        private val bySource = sites.associateBy { it.store.id }
        val names: Map<String, String> = universes.shown.of(null, dictionary)

        /** Every entry of the catalogue the dictionary keeps, by its key. */
        val entries: Map<UniverseKey, StoredUniverse> by lazy {
            universes.allEntries().associateBy { UniverseKey(it.catalogue, it.id) }
        }

        fun site(ref: SiteFandomRef): UniverseSite? {
            val s = bySource[ref.source] ?: return null
            val name = s.names[ref.key] ?: ref.key
            return UniverseSite(
                ref.source,
                ref.key,
                name,
                s.uses[ref.key] ?: 0,
                s.store.source.valueUrl(s.facet, ref.key, name),
            )
        }

        fun sitesOf(id: Long): List<UniverseSite> =
            dictionary
                .sitesOf(id)
                .mapNotNull(::site)
                .sortedWith(compareByDescending<UniverseSite> { it.works }.thenBy { it.name })

        fun name(id: Long): String = names[FandomRepository.value(id)] ?: "#$id"
    }

    private class Site(
        val store: SourceStore,
        val facet: String,
        val names: Map<String, String>,
        val uses: Map<String, Int>,
    )

    /** Every universe, of the most works first. */
    fun all(): List<UniverseInfo> {
        val r = Reading()
        val described = r.universes.shown.descriptions(r.dictionary.values, r.dictionary)
        return r.dictionary.fandoms.keys
            .map { info(r, it, described) }
            .sortedWith(compareByDescending<UniverseInfo> { it.works }.thenBy { it.name.lowercase() })
    }

    /** The universe [value]; null of no such universe. */
    fun one(value: String): UniverseInfo? {
        val r = Reading()
        val id = FandomRepository.idOf(value)?.takeIf { it in r.dictionary.fandoms } ?: return null
        return info(r, id, r.universes.shown.descriptions(listOf(value), r.dictionary))
    }

    private fun info(r: Reading, id: Long, described: Map<String, String>): UniverseInfo {
        val row = r.dictionary.fandoms.getValue(id)
        val value = FandomRepository.value(id)
        val sites = r.sitesOf(id)
        val entries = r.dictionary.entriesOf(id).mapNotNull { r.entries[it] }
        return UniverseInfo(
            value = value,
            name = r.name(id),
            sites = sites,
            propertyEntries = entries.map(::entryInfo),
            characters = row.characters,
            characterCount = if (row.characters) entries.sumOf { it.characters } else 0,
            works = sites.sumOf { it.works },
            parents = r.dictionary.parentsOf(id).map(FandomRepository::value),
            children = r.dictionary.childrenOf(id).map(FandomRepository::value),
            ownName = row.name,
            calculatedName = row.calculatedName,
            description = described[value],
        )
    }

    private fun entryInfo(u: StoredUniverse) =
        UniverseEntryInfo(
            catalogue = u.catalogue,
            entry = u.id,
            value = u.value,
            name = u.name,
            catalogueName = u.catalogueName,
            url = u.url,
            characters = u.characters,
            total = u.entries,
            refreshedAt = u.refreshedAt.atOffset(ZoneOffset.UTC),
            localName = u.localName,
            description = u.description,
        )

    /**
     * The universes alike [value], the likest first, at most [limit]: of the meaning of the names of their sites'
     * fandoms — a name as the search encodes short texts, kept by its source ([FandomVectors]), a universe alike by the
     * best cosine of a name of each — alike the text [query] instead when one is given; a universe bound to an entry
     * this one is bound to, alike wholly. Each with what the user said it is to this one. Null of no such universe.
     */
    fun similar(value: String, query: String?, limit: Int): List<SimilarUniverse>? {
        val r = Reading()
        val id = FandomRepository.idOf(value)?.takeIf { it in r.dictionary.fandoms } ?: return null
        val vectors = vectorsOf(r)
        val asked = query?.trim()?.takeIf { it.isNotEmpty() }
        val target =
            if (asked == null) r.dictionary.sitesOf(id).mapNotNull { vectors[it] }
            else
                r.sites
                    .firstOrNull()
                    ?.let { phrases.of(it.store, listOf(asked))[asked] }
                    ?.let(TagVectors::unit)
                    ?.let(::listOf)
                    .orEmpty()
        val own = r.dictionary.entriesOf(id).toSet()
        val relation = relationTo(r.universes.fandoms, r.dictionary, id)
        return r.dictionary.fandoms.keys
            .filter { it != id }
            .map { other ->
                val shared = asked == null && r.dictionary.entriesOf(other).any { it in own }
                val likeness =
                    if (shared) 1.0
                    else TagVectors.likeness(target, r.dictionary.sitesOf(other).mapNotNull { vectors[it] })
                other to likeness
            }
            .sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
            .take(limit.coerceIn(1, MAX_SIMILAR))
            .map { (other, likeness) ->
                val sites = r.sitesOf(other)
                SimilarUniverse(
                    value = FandomRepository.value(other),
                    name = r.name(other),
                    sites = sites,
                    works = sites.sumOf { it.works },
                    similarity = likeness.toFloat(),
                    relation = relation(other),
                )
            }
    }

    /** The vector of every site's fandom's name its source keeps, of unit length; none of a name not encoded yet. */
    private fun vectorsOf(r: Reading): Map<SiteFandomRef, FloatArray> {
        val out = HashMap<SiteFandomRef, FloatArray>()
        r.sites.forEach { s ->
            val written = s.names.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
            val kept = phrases.cached(s.store, written.values)
            written.forEach { (key, name) ->
                kept[name]?.let(TagVectors::unit)?.let { out[SiteFandomRef(s.store.id, key)] = it }
            }
        }
        return out
    }

    /** What the user said every other universe is to [id]. */
    private fun relationTo(fandoms: FandomRepository, d: FandomDictionary, id: Long): (Long) -> UniverseRelation {
        val distinct = fandoms.relations.distinct()
        val children = d.childrenOf(id)
        val parents = d.parentsOf(id)
        val descendants = d.descendants(id)
        val ancestors = d.ancestors(id)
        return { other ->
            when {
                other in children -> UniverseRelation.CHILD
                other in parents -> UniverseRelation.PARENT
                other in descendants -> UniverseRelation.DESCENDANT
                other in ancestors -> UniverseRelation.ANCESTOR
                FandomRepository.pair(id, other) in distinct -> UniverseRelation.DIFFERENT
                else -> UniverseRelation.NONE
            }
        }
    }

    private companion object {
        /** The most universes offered as alike at once. */
        const val MAX_SIMILAR = 200
    }
}
