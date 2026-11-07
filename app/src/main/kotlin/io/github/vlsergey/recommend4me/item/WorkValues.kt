package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.suggestion.Chance
import io.github.vlsergey.recommend4me.tag.TagRepository

/**
 * What the sites and the model give a work ([ItemCards.ofWork]). [bySite]: of a facet of the layer, every value a site
 * gives, with the sites that give it; [lets]: of a facet told by a site's own (the age rating), the steps any site of
 * the work names.
 */
data class WorkValues(
    val site: Map<String, List<String>>,
    val chances: Map<String, Map<String, Chance>>?,
    val bySite: Map<String, Map<String, List<String>>> = emptyMap(),
    val lets: Map<String, Set<String>> = emptyMap(),
)

/**
 * WHAT THE SITES OF A WORK GIVE THE FACETS OF ITS LAYER, gathered item by item ([read]): [bySite] every value of a
 * facet with the sites that give it, [lets] the steps of a facet told by a site's own that any site names.
 */
internal class SitesGive {
    val bySite = HashMap<String, MutableMap<String, MutableList<String>>>()
    val lets = HashMap<String, MutableSet<String>>()

    /**
     * What the [items] of the site [source] (its store [s]) give the facets [layer]: told by the items' own source,
     * where their tags are bound. The item [key] is read by the values [site] given of it, the rest from the source.
     */
    fun read(
        s: SourceStore,
        source: String,
        items: List<ItemKey>,
        key: ItemKey,
        site: Map<String, List<String>>,
        layer: List<FacetDef>,
    ) {
        val defs = layer.mapNotNull { def -> s.schema.facets.firstOrNull { it.key == def.key } }
        val bound = if (defs.any { it.examplesFrom != null }) s.tags.bindingsOf(s.id) else emptyMap()
        val facets = s.items.facetsOf(items.map { it.id })
        items.forEach { m ->
            val theirs = if (m == key) site else facets[m.id].orEmpty()
            defs.forEach { def -> add(source, def, theirs, bound) }
        }
    }

    /**
     * What one item of the site [source] gives the facet [def] by its values [theirs]: its own values of the facet and
     * the tags of the layer its site's tags are [bound] to; the steps its site's own facet names.
     */
    private fun add(source: String, def: FacetDef, theirs: Map<String, List<String>>, bound: Map<String, Long>) {
        val given =
            theirs[def.key].orEmpty() +
                def.examplesFrom
                    ?.let { from -> theirs[from].orEmpty().mapNotNull { bound[it]?.let(TagRepository::key) } }
                    .orEmpty()
        given.forEach { value ->
            val sites = bySite.getOrPut(def.key) { LinkedHashMap() }.getOrPut(value) { ArrayList() }
            if (source !in sites) sites += source
        }
        def.originalFacet?.let { theirs[it] }?.let { lets.getOrPut(def.key) { HashSet() } += it }
    }
}
