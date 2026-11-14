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
import java.time.Instant
import java.time.ZoneOffset
import kotlin.concurrent.withLock
import org.springframework.stereotype.Component

/**
 * THE UNIVERSES AS THE SCREEN OF THE UNIVERSES SHOWS THEM: each with its sites' fandoms and their works, the entries of
 * the catalogue it is bound to, its characters and its place among the others ([all], [one]); and the universes alike
 * one ([similar]). Every part read once for the asking ([Reading]).
 */
@Component
class UniverseViews(
    private val stores: Stores,
    private val phrases: PhraseVectors,
    private val fandomVectors: SiteFandomVectors,
) {

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

        /**
         * What the sites said their fandoms [refs] are of, of those their pages were read of: a blank one the page
         * saying nothing.
         */
        fun descriptions(refs: Collection<SiteFandomRef>): Map<SiteFandomRef, String> =
            refs
                .groupBy { it.source }
                .flatMap { (source, of) ->
                    val s = bySource[source] ?: return@flatMap emptyList()
                    s.store.items.facetDescriptions(s.facet, of.map { it.key }).map { (key, text) ->
                        SiteFandomRef(source, key) to text
                    }
                }
                .toMap()

        /**
         * The sites' fandoms of [refs] not read of yet asked of the browser, once
         * ([Source.describing][io.github.vlsergey.recommend4me.source.Source.describing]): the request written, not
         * waited for.
         */
        fun askOf(refs: Collection<SiteFandomRef>, described: Map<SiteFandomRef, String>) {
            refs
                .filter { it !in described }
                .groupBy { it.source }
                .forEach { (source, of) ->
                    val s = bySource[source] ?: return@forEach
                    val wanted = s.store.source.describing(s.facet, of.map { it.key })
                    if (wanted.isNotEmpty()) s.store.writing.withLock { s.store.wanted.want(wanted, Instant.now()) }
                }
        }

        fun site(ref: SiteFandomRef, described: Map<SiteFandomRef, String>): UniverseSite? {
            val s = bySource[ref.source] ?: return null
            val name = s.names[ref.key] ?: ref.key
            return UniverseSite(
                ref.source,
                ref.key,
                name,
                s.uses[ref.key] ?: 0,
                s.store.source.valueUrl(s.facet, ref.key, name),
                described[ref]?.takeIf { it.isNotBlank() },
            )
        }

        fun sitesOf(id: Long, described: Map<SiteFandomRef, String> = emptyMap()): List<UniverseSite> =
            dictionary
                .sitesOf(id)
                .mapNotNull { site(it, described) }
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

    /**
     * The universe [value], with what its sites say each fandom of it is of; a fandom not read of yet asked of the
     * browser. Null of no such universe.
     */
    fun one(value: String): UniverseInfo? {
        val r = Reading()
        val id = FandomRepository.idOf(value)?.takeIf { it in r.dictionary.fandoms } ?: return null
        val refs = r.dictionary.sitesOf(id)
        val said = r.descriptions(refs)
        r.askOf(refs, said)
        return info(r, id, r.universes.shown.descriptions(listOf(value), r.dictionary), said)
    }

    private fun info(
        r: Reading,
        id: Long,
        described: Map<String, String>,
        said: Map<SiteFandomRef, String> = emptyMap(),
    ): UniverseInfo {
        val row = r.dictionary.fandoms.getValue(id)
        val value = FandomRepository.value(id)
        val sites = r.sitesOf(id, said)
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
     * fandoms and of what their sites say they are of, a synonym of the name ([SiteFandomVectors]), a universe alike by
     * the best cosine of a name or a description of each, the cosine as the user's word on the universes taught it
     * ([FandomLikeness]) — alike the text [query] instead when one is given; a universe bound to an entry this one is
     * bound to, alike wholly. Only those the [filter] lets, by what the user said each is to this one; each with that
     * word, and what its sites say its fandoms are of. Null of no such universe.
     */
    fun similar(value: String, query: String?, filter: SimilarFilter, limit: Int): List<SimilarUniverse>? {
        val r = Reading()
        val id = FandomRepository.idOf(value)?.takeIf { it in r.dictionary.fandoms } ?: return null
        // The likeness of the names as the user's word on the universes taught it, the encoder's own of no word
        val learnt = stores.withUniverses().first().fandomLikeness.find()
        val ready = { v: FloatArray -> learnt?.of(v) ?: v }
        val vectors =
            fandomVectors.of(r.sites.map { Triple(it.store, it.facet, it.names) }).mapValues { (_, v) ->
                v.all.map(ready)
            }
        val asked = query?.trim()?.takeIf { it.isNotEmpty() }
        val target =
            if (asked == null) r.dictionary.sitesOf(id).flatMap { vectors[it].orEmpty() }
            else
                r.sites
                    .firstOrNull()
                    ?.let { phrases.of(it.store, listOf(asked))[asked] }
                    ?.let(TagVectors::unit)
                    ?.let(ready)
                    ?.let(::listOf)
                    .orEmpty()
        val own = r.dictionary.entriesOf(id).toSet()
        val relation = relationTo(r.universes.fandoms, r.dictionary, id)
        val alike =
            r.dictionary.fandoms.keys
                .filter { it != id && filter.lets(relation(it)) }
                .map { other ->
                    val shared = asked == null && r.dictionary.entriesOf(other).any { it in own }
                    val likeness =
                        if (shared) 1.0
                        else TagVectors.likeness(target, r.dictionary.sitesOf(other).flatMap { vectors[it].orEmpty() })
                    other to likeness
                }
                .sortedWith(compareByDescending<Pair<Long, Double>> { it.second }.thenBy { it.first })
                .take(limit.coerceAtLeast(1))
        val said = r.descriptions(alike.flatMap { r.dictionary.sitesOf(it.first) })
        return alike.map { (other, likeness) ->
            val sites = r.sitesOf(other, said)
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
}
