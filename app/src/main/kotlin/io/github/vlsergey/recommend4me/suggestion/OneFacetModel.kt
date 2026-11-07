package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import org.springframework.stereotype.Component

/**
 * ONE FACET'S MODEL ASKED APART FROM A PASS OF THE CHAIN: everything its suggesters learn from ([task]), its chance of
 * one value for many works ([chancesOfValue]), its weight of every value over the works as a whole ([weights]).
 */
@Component
internal class OneFacetModel(
    private val plugins: Plugins,
    private val chain: FacetChain,
    private val vocabularies: FacetVocabularies,
    private val passes: WorkPasses,
) {
    /**
     * Everything the suggesters of the facet learn from, read now: what a comparison of them fits every one on
     * ([FacetComparisons]); null when the source has no such facet or nothing to read with.
     */
    fun task(store: SourceStore, facetKey: String): SuggestionTask? {
        val facets = chain.facetsOf(store)
        val k = facets.indexOfFirst { it.key == facetKey }
        val encoder = plugins.textEncoder()
        if (k < 0 || encoder == null) return null
        val read = passes.worksRead(store, encoder, null)
        val made = chain.chain(store, encoder, read, k)
        return chain
            .load(
                store,
                facets[k],
                encoder,
                vocabularies.vocabularyOf(
                    store,
                    facets[k],
                    null,
                    made,
                    read.characters,
                    read.answers,
                    site = read.site,
                ),
                made,
                null,
                read,
            )
            .task
    }

    /**
     * The model's chance of ONE value [key] of the [facet] for every work of [ids], whatever else it gives them and
     * whether or not the work may have it ([Vocabulary.allowed]) — the works of a pairing's universes ranked by it.
     * Made now as [FacetChain.chances] are, none kept, nothing waited for; a work the facet's model does not score, or
     * a value it does not know, has none.
     */
    fun chancesOfValue(store: SourceStore, ids: Collection<String>, facet: String, key: String): Map<String, Double> {
        val scored = if (ids.isEmpty()) null else scoredOf(store, ids, facet)
        val v = scored?.data?.values?.indexOf(key) ?: -1
        return if (scored == null || v < 0) emptyMap() else chancesOf(scored, ids, v)
    }

    /**
     * The [facet]'s chances of the works [ids] by the model kept, the facets before it in the chain made of them first;
     * null when the source has no such facet, it was never fitted, or there is nothing to read with.
     */
    private fun scoredOf(store: SourceStore, ids: Collection<String>, facet: String): FacetChain.Scored? {
        val facets = chain.facetsOf(store)
        val k = facets.indexOfFirst { it.key == facet }
        val suggester = plugins.suggester(store.id, facet)
        val encoder = plugins.textEncoder()
        if (k < 0 || suggester == null || encoder == null) return null
        val read = passes.worksRead(store, encoder, chain.reading(store, ids, k + 1))
        val made = chain.chain(store, encoder, read, k)
        return chain.scored(store, facets[k], suggester, encoder, read, made)
    }

    /** The scores of the value [v] of every work of [ids] among the works [scored], a chunk of them at a time. */
    private fun chancesOf(scored: FacetChain.Scored, ids: Collection<String>, v: Int): Map<String, Double> {
        val out = HashMap<String, Double>()
        val asked = ids.toHashSet()
        scored.data.ids.indices
            .filter { scored.data.ids[it] in asked }
            .chunked(CHUNK)
            .forEach { chunk ->
                val rows = scored.scores.of(chunk.toIntArray())
                chunk.forEachIndexed { j, i -> out[scored.data.ids[i]] = rows[j][v].toDouble() }
            }
        return out
    }

    /**
     * The model's weight of every value of the [facet] over the works as a whole ([FacetSuggester.weights]): of the
     * scoring kept alone, no work read — the values the model knows, the likelier the larger. Null when the facet was
     * never fitted, or its suggester tells none.
     */
    fun weights(store: SourceStore, facet: String): Map<String, Double>? {
        val kept = store.suggestions.scoring(facet) ?: return null
        val weights =
            plugins
                .suggester(store.id, facet)
                ?.takeIf { it.id == kept.suggester }
                ?.weights(kept.bytes)
                ?.takeIf { it.size == kept.values.size }
        return weights?.let { w -> kept.values.indices.associate { kept.values[it] to w[it] } }
    }

    companion object {
        /** Items scored at once: memory, not meaning. */
        private const val CHUNK = 2_000
    }
}
