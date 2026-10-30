package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * The normalized tags of the works [ids] of the source — every work when null: the site's tags bound to
 * them, the user's word of the [corrections] on the layer's tags over them; no tag a model works out.
 */
fun SourceStore.normalizedTags(ids: Collection<String>?, corrections: List<FacetCorrection>): Map<String, Set<Long>> {
    val tags = HashMap<String, MutableSet<Long>>()
    val layer = tagFacets.firstOrNull() ?: return tags
    val siteFacet = schema.facet(layer)?.examplesFrom
    val bound = this.tags.bindingsOf(id)
    fun site(item: String, keys: List<String>) = keys.mapNotNull { bound[it] }.forEach { tags.getOrPut(item) { HashSet() } += it }
    if (siteFacet != null) {
        if (ids == null) items.forEachFacet(siteFacet, ::site)
        else items.facetsOf(ids).forEach { (item, own) -> site(item, own[siteFacet].orEmpty()) }
    }
    corrections.filter { it.facet == layer }.forEach { c ->
        val tag = TagRepository.idOf(c.key) ?: return@forEach
        if (c.added) tags.getOrPut(c.itemId) { HashSet() } += tag else tags[c.itemId]?.remove(tag)
    }
    return tags
}
