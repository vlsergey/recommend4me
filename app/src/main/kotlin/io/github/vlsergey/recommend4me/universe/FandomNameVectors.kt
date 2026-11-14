package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagVectors
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import org.springframework.stereotype.Component

/**
 * THE VECTORS OF THE NAMES OF THE SITES' FANDOMS: a name as the search encodes short texts, kept by its source, of unit
 * length — what the likeness of two universes is made of ([UniverseViews.similar], [FandomLikeness]).
 */
@Component
class FandomNameVectors(private val stores: Stores, private val phrases: PhraseVectors) {

    /** Of every site's fandom of the [names] — of every source, its fandoms' names by key — the vector kept. */
    fun of(names: List<Pair<SourceStore, Map<String, String>>>): Map<SiteFandomRef, FloatArray> {
        val out = HashMap<SiteFandomRef, FloatArray>()
        names.forEach { (s, own) ->
            val written = own.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
            val kept = phrases.cached(s, written.values)
            written.forEach { (key, name) ->
                kept[name]?.let(TagVectors::unit)?.let { out[SiteFandomRef(s.id, key)] = it }
            }
        }
        return out
    }

    /** Of every site's fandom of every source of universes, the vector kept; none of a name not encoded yet. */
    fun all(): Map<SiteFandomRef, FloatArray> =
        of(
            stores
                .withUniverses()
                .flatMap { it.sources }
                .mapNotNull { s ->
                    UniverseFacet.fandomFacet(s.schema)?.let { s to s.items.facetNames(it) }
                }
        )
}
