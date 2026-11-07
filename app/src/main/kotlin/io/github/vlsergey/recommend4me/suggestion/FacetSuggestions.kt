package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import org.springframework.stereotype.Service

/** The model gave items of a source other values of a facet it works out: what reads them reads them again. */
data class FacetChancesChanged(val source: String, val ids: List<String>)

/**
 * The chances of works, made in memory when they are read ([FacetSuggestions.chances]): item to facet to value to
 * chance.
 */
typealias WorkChances = Map<String, Map<String, Map<String, Chance>>>

/**
 * WHAT THE MODEL SAYS OF THE VALUES OF FACETS, by the suggester plugin, for every facet a source marks
 * [suggest][FacetDef.suggest] or [infer][FacetDef.infer]: the chance of every value a work has, and of every value it
 * lacks that it more likely has than not.
 *
 * ONLY THE MODEL OF EACH FACET IS KEPT ([SuggestionRepository]): its fitted state and the values it chooses from. THE
 * CHANCES OF THE WORKS ARE NOT (user, 2026-10-15): whoever reads works — a page, a card, a filter over the catalogue,
 * the search index, the training of the grades — gets them made in memory of those works alone, from their vectors and
 * values, by the models kept ([chances]). Nothing waits for a refit, nothing is written of a work.
 *
 * THE FACETS ARE WORKED OUT IN A CHAIN, in the order of [facetsOf]: each reads as its context the values the site and
 * the user give of every other facet, and the values the model gives of the facets before it, made in the same pass — a
 * work's characters are those of the universes given before them, its pairings pairs of the characters given before
 * them ([FacetChain]). The models are fitted again in the background whenever anything they learn from changed
 * ([FacetRefits]).
 */
@Service
class FacetSuggestions
internal constructor(
    private val plugins: Plugins,
    private val settings: Settings,
    private val chain: FacetChain,
    private val ownModel: OneFacetModel,
    private val candidates: WorkCandidates,
    private val refits: FacetRefits,
) {
    /** The facets the model works on, in the order of the chain ([FacetChain.facetsOf]). */
    fun facetsOf(store: SourceStore): List<FacetDef> = chain.facetsOf(store)

    /** The models of every facet of the source fitted again, for a caller that waits for it ([FacetRefits.refresh]). */
    fun refresh(store: SourceStore) = refits.refresh(store)

    /** The facet's suggester fitted again from scratch, in the background ([FacetRefits.searchAnew]). */
    fun searchAnew(store: SourceStore, facetKey: String): Boolean = refits.searchAnew(store, facetKey)

    /**
     * The suggester [method] made the one the facet learns with, and the facet fitted with it from scratch
     * ([searchAnew]); false when there is no such suggester or facet.
     */
    fun chooseMethod(store: SourceStore, facetKey: String, method: String): Boolean {
        if (plugins.suggesters.none { it.id == method } || facetsOf(store).none { it.key == facetKey }) return false
        settings.set(Plugins.suggesterSetting(store.id, facetKey), method)
        return searchAnew(store, facetKey)
    }

    /** Everything the suggesters of the facet learn from, read now ([OneFacetModel.task]). */
    fun task(store: SourceStore, facetKey: String): SuggestionTask? = ownModel.task(store, facetKey)

    /** Whether the facet's suggester is being fitted anew ([searchAnew]). */
    fun searching(store: SourceStore, facetKey: String): Boolean = refits.searching(store, facetKey)

    /** The chances of the works [ids] made now down the chain ([FacetChain.chances]). */
    fun chances(
        store: SourceStore,
        ids: Collection<String>?,
        through: String? = null,
        laps: Laps = Laps(),
        every: EveryItemFacets? = null,
    ): WorkChances = chain.chances(store, ids, through, laps, every)

    /** The model's chance of one value for every work of [ids] ([OneFacetModel.chancesOfValue]). */
    fun chancesOfValue(store: SourceStore, ids: Collection<String>, facet: String, key: String): Map<String, Double> =
        ownModel.chancesOfValue(store, ids, facet, key)

    /** The model's weight of every value of the [facet] over the works as a whole ([OneFacetModel.weights]). */
    fun weights(store: SourceStore, facet: String): Map<String, Double>? = ownModel.weights(store, facet)

    /** [chances] of the [wanted] facets alone ([FacetChain.chancesOf]). */
    fun chancesOf(
        store: SourceStore,
        ids: Collection<String>?,
        wanted: Collection<String>,
        laps: Laps = Laps(),
        every: EveryItemFacets? = null,
    ): WorkChances = chain.chancesOf(store, ids, wanted, laps, every)

    /** The values to suggest for an item ([WorkCandidates.ofItem]). */
    fun ofItem(
        store: SourceStore,
        itemId: String,
        chances: Map<String, Map<String, Chance>>? = null,
    ): List<ItemFacetSuggestions> = candidates.ofItem(store, itemId, chances)
}
