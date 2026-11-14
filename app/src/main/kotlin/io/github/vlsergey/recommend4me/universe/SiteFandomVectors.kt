package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagVectors
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import org.springframework.stereotype.Component

/** A site's fandom as its vectors: of its name, and of what its site says it is of — none when it says nothing. */
class FandomVectorsOf(val name: FloatArray, val description: FloatArray?) {
    /** Both, the description a synonym of the name (user, 2026-11-14): a universe alike by either. */
    val all: List<FloatArray>
        get() = listOfNotNull(name, description)
}

/**
 * THE VECTORS OF THE SITES' FANDOMS: of a fandom's name and of what its site says it is of — ficbook's "Captain America
 * / Marvel, 1941-...", fanfics.me's «по американскому сериалу "Glee"» — each as the search encodes short texts, kept by
 * its source ([FandomVectors] makes them), of unit length: what the likeness of two universes is made of
 * ([UniverseViews.similar], [FandomLikeness]). The description stands beside the name as a synonym, not added to it:
 * the original title it names finds the name of another site whole, and what is only "an American series" is not mixed
 * into the name of every fandom of one.
 */
@Component
class SiteFandomVectors(private val stores: Stores, private val phrases: PhraseVectors) {

    /**
     * Of every site's fandom of the [sites] — of every source, the facet of its fandoms and their names by key — the
     * vectors kept; none of a fandom whose name is not encoded yet.
     */
    fun of(sites: List<Triple<SourceStore, String, Map<String, String>>>): Map<SiteFandomRef, FandomVectorsOf> {
        val out = HashMap<SiteFandomRef, FandomVectorsOf>()
        sites.forEach { (s, facet, names) ->
            val written = names.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
            val said =
                s.items
                    .facetDescriptions(facet, written.keys)
                    .mapValues { it.value.trim() }
                    .filterValues {
                        it.isNotEmpty()
                    }
            val kept = phrases.cached(s, written.values + said.values)
            written.forEach { (key, name) ->
                kept[name]?.let(TagVectors::unit)?.let { vector ->
                    val description = said[key]?.let(kept::get)?.let(TagVectors::unit)
                    out[SiteFandomRef(s.id, key)] = FandomVectorsOf(vector, description)
                }
            }
        }
        return out
    }

    /** Of every site's fandom of every source of universes, the vectors kept. */
    fun all(): Map<SiteFandomRef, FandomVectorsOf> =
        of(
            stores
                .withUniverses()
                .flatMap { it.sources }
                .mapNotNull { s ->
                    UniverseFacet.fandomFacet(s.schema)?.let { Triple(s, it, s.items.facetNames(it)) }
                }
        )
}
