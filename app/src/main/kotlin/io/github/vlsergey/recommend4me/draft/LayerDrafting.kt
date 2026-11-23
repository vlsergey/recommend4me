package io.github.vlsergey.recommend4me.draft

import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.CatalogueLookup
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets

/**
 * THE DRAFTS OF THE LAYER OF A FEW WORKS, MADE WHERE THEY ARE READ — never kept (2026-11-23: the index of the list
 * holds what they made): of every work of a source that asks the catalogue for statements
 * ([Source.catalogueProperties][io.github.vlsergey.recommend4me.source.Source.catalogueProperties]), what the source
 * drafts of it from its values on the site through the dictionary
 * ([Source.draft][io.github.vlsergey.recommend4me.source.Source.draft]) — and the universe of every character drafted
 * that is in one universe of the dictionary alone; and of every work of a source with fandoms not said by its site to
 * be of no universe, the universe every fandom of it is bound to and every universe above it. The dictionary is read by
 * the keys the works have and the statements the source asks ([DraftDictionary]), never whole.
 */
internal object LayerDrafting {

    /** Of the works whose site's values are [site] (item to facet to keys), by their ids, their drafts of the layer. */
    fun of(store: SourceStore, site: Map<String, Map<String, List<String>>>): Map<String, List<LayerDraft>> {
        val dictionary = store.draftDictionary
        val fandom = UniverseFacet.fandomFacet(store.schema)
        // A source of no fandoms that asks the catalogue nothing drafts nothing
        val drafts = fandom != null || store.source.catalogueProperties.isNotEmpty()
        if (dictionary == null || site.isEmpty() || !drafts) return emptyMap()
        // The names of the values as written, of every facet of the works at once
        val keys = HashMap<String, HashSet<String>>()
        site.values.forEach { of -> of.forEach { (facet, values) -> keys.getOrPut(facet) { HashSet() } += values } }
        val names = keys.mapValues { (facet, values) -> store.items.facetNames(facet, values) }
        val values = site.mapValues { (_, of) ->
            of.mapValues { (facet, list) -> list.map { FacetValue(it, names[facet]?.get(it)) } }
        }
        val universes = fandom?.let { dictionary.universesOfSites(store.id, keys[it].orEmpty()) }.orEmpty()
        val catalogue = ofCatalogue(store, dictionary, values)
        val made = values.mapValues { (id, own) ->
            (catalogue[id].orEmpty() + ofFandoms(fandom, own, universes)).distinct()
        }
        val characters =
            dictionary.universesOfCharacters(
                made.values.flatten().filter { it.facet == UniverseFacets.CHARACTERS }.map { it.key }.distinct()
            )
        return made.mapValues { (_, drafts) -> withUniverses(drafts, characters) }.filterValues { it.isNotEmpty() }
    }

    /**
     * What the source drafts of every work of [values] through the statements of the catalogue: the source asked till
     * it asks nothing new, what it asked read of the dictionary between — a source may ask of what it was answered.
     */
    private fun ofCatalogue(
        store: SourceStore,
        dictionary: DraftDictionary,
        values: Map<String, Map<String, List<FacetValue>>>,
    ): Map<String, List<LayerDraft>> {
        val answered = HashMap<Asked, Set<String>>()
        while (true) {
            val lookup = AskedLookup(answered)
            val drafted = values.mapValues { (_, own) -> store.source.draft(own, lookup) }
            if (lookup.fresh.isEmpty()) return drafted
            val read = dictionary.answers(lookup.fresh)
            lookup.fresh.forEach { answered[it] = read[it].orEmpty() }
        }
    }

    /** The dictionary as the source asks it: the statements [answered] already answered, the rest kept asked. */
    private class AskedLookup(private val answered: Map<Asked, Set<String>>) : CatalogueLookup {
        val fresh = HashSet<Asked>()

        override fun universes(catalogue: String, property: String, value: String) =
            ask(Asked(false, catalogue, property, value.lowercase()))

        override fun characters(catalogue: String, property: String, value: String) =
            ask(Asked(true, catalogue, property, value.lowercase()))

        private fun ask(asked: Asked): Set<String> = answered[asked] ?: emptySet<String>().also { fresh += asked }
    }

    /**
     * The universes of a work's fandoms on its site ([own] values of the [fandom] facet), by the [universes] of every
     * site's fandom: the universe every one is bound to and every universe above it, named by the fandom. None of a
     * work its site says is of no universe (realism, an alternative history, an original work: the fandom "originals").
     */
    private fun ofFandoms(
        fandom: String?,
        own: Map<String, List<FacetValue>>,
        universes: Map<String, List<String>>,
    ): List<LayerDraft> {
        val kinds = own[UniverseFacets.KIND].orEmpty().map { it.key }
        val universeless = kinds.isNotEmpty() && kinds.all { it in UniverseFacets.UNIVERSELESS }
        val fandoms = if (fandom == null || universeless) emptyList() else own[fandom].orEmpty()
        return fandoms.flatMap { f ->
            universes[f.key].orEmpty().map { LayerDraft(UniverseFacets.UNIVERSE, it, f.name ?: f.key) }
        }
    }

    /** The [drafts] with the universe of every character drafted that is in one universe alone ([characters]). */
    private fun withUniverses(drafts: List<LayerDraft>, characters: Map<String, Set<String>>): List<LayerDraft> {
        val universes =
            drafts
                .filter { it.facet == UniverseFacets.CHARACTERS }
                .mapNotNull { d ->
                    characters[d.key]?.singleOrNull()?.let { LayerDraft(UniverseFacets.UNIVERSE, it, d.from) }
                }
        return (drafts + universes).distinct()
    }
}
