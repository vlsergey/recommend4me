package io.github.vlsergey.recommend4me.draft

import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.universe.LayerDraft

/**
 * THE TAGS A WORK IS DRAFTED OF ITS SITE'S TAGS: of a facet learnt from a site's facet
 * ([FacetDef.examplesFrom][io.github.vlsergey.recommend4me.source.FacetDef.examplesFrom]), every normalized tag a
 * site's tag of the work is bound to, drafted from it ([LayerDraft.from]: the site's tag by its name) — a value the
 * work is given, marked so, till the user says otherwise (user, 2026-11-19: what the site says is "a source of
 * information when filling the works' fields, if the user said nothing — but not for training"). Made with the drafts
 * of the layer ([WorkDrafts]), never kept: of ficbook's million works the bindings of their tags change with every
 * merge of two.
 */
internal object TagDrafts {
    /** Of the works whose site's values are [site], by their ids, the tags drafted of their site's tags. */
    fun of(store: SourceStore, site: Map<Long, Map<String, List<String>>>): Map<Long, List<LayerDraft>> {
        val drafted = store.schema.facets.mapNotNull { f -> f.examplesFrom?.let { from -> f.key to from } }
        if (drafted.isEmpty() || site.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableList<LayerDraft>>()
        drafted.forEach { (facet, from) ->
            val keys = HashSet<String>()
            site.values.forEach { keys += it[from].orEmpty() }
            val bound = store.tags.bindingsOf(store.id, keys)
            val names = store.items.facetNames(from, keys)
            site.forEach { (id, facets) ->
                facets[from].orEmpty().forEach { siteKey ->
                    bound[siteKey]?.let { tag ->
                        out.getOrPut(id) { ArrayList() } +=
                            LayerDraft(facet, TagRepository.key(tag), names[siteKey] ?: siteKey)
                    }
                }
            }
        }
        return out
    }
}

/**
 * THE DRAFTS OF THE WORKS AS THEY ARE READ, made of their site's values and the dictionaries by the keys they have,
 * never kept: the drafts of the layer ([LayerDrafting]) and the tags drafted of the site's tags ([TagDrafts]), one list
 * a work.
 */
internal object WorkDrafts {
    /** Of the works [ids] of the [store], whose site's values are [site], every draft. */
    fun of(
        store: SourceStore,
        ids: Collection<Long>,
        site: Map<Long, Map<String, List<String>>> = store.items.facetsOf(ids),
    ): Map<Long, List<LayerDraft>> {
        val wanted = ids as? Set<String> ?: ids.toHashSet()
        val own = site.filterKeys { it in wanted }
        return merged(LayerDrafting.of(store, own), TagDrafts.of(store, own))
    }

    private fun merged(
        layer: Map<Long, List<LayerDraft>>,
        tags: Map<Long, List<LayerDraft>>,
    ): Map<Long, List<LayerDraft>> =
        if (tags.isEmpty()) layer
        else HashMap(layer).apply { tags.forEach { (id, list) -> merge(id, list) { a, b -> (a + b).distinct() } } }
}
