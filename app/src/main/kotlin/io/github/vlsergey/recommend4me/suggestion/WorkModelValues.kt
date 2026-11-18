package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.source.SourceStore
import org.springframework.stereotype.Component

/**
 * The models of a source's facets were fitted anew ([FacetRefits]): the values they give every work are to be filled.
 */
data class FacetModelsFitted(val source: String)

/**
 * THE VALUES THE MODELS GIVE THE WORKS, made now and kept nowhere but in the index of the list (user, 2026-11-18: "the
 * values of the works are not written to the database at all; all the values — the given and the computed together — we
 * write to Lucene alone"): of every facet the source works out
 * ([io.github.vlsergey.recommend4me.source.FacetDef.infer]) the values a work is shown with beyond the site's own
 * ([ModelValues.of]) — the drafts and the facets' models of their own among them —, the chain of the models kept made
 * of the works, a portion at a time. What the index of the list is made of — of the works a change names as their
 * documents are made, of every work once the models are fitted anew, last of the queue.
 */
@Component
class WorkModelValues internal constructor(private val chain: FacetChain) {
    /**
     * Of the works [ids] of the [store], by work, of every facet it works out, the values the models give it beyond the
     * site's own; [known]: their site's values and the user's answers, read already. None of a source that works out
     * none.
     */
    fun of(
        store: SourceStore,
        ids: Collection<String>,
        known: EveryItemFacets? = null,
    ): Map<String, Map<String, List<String>>> {
        val facets = store.schema.facets.filter { it.infer }
        if (facets.isEmpty() || ids.isEmpty()) return emptyMap()
        val out = HashMap<String, Map<String, List<String>>>()
        ids.distinct().chunked(TaskPortions.MOST).forEach { portion ->
            val read = known ?: EveryItemFacets(store.items.facetsOf(portion), store.corrections.facetsOf(portion))
            val made = chain.chances(store, portion, known = read)
            portion.forEach { id ->
                val byFacet = facets.mapNotNull { def ->
                    val chances = made[id]?.get(def.key) ?: return@mapNotNull null
                    val own = read.site[id]?.get(def.key).orEmpty()
                    val given =
                        ModelValues.of(store.schema, mapOf(def.key to own), mapOf(def.key to chances))[def.key]
                            .orEmpty()
                            .filter { it !in own }
                    given.takeIf { it.isNotEmpty() }?.let { def.key to it }
                }
                if (byFacet.isNotEmpty()) out[id] = byFacet.toMap()
            }
        }
        return out
    }
}
