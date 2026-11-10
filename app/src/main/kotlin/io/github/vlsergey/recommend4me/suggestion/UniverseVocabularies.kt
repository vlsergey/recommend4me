package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.universe.CharactersRead
import io.github.vlsergey.recommend4me.universe.FandomDictionary
import io.github.vlsergey.recommend4me.universe.FandomRepository
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.springframework.stereotype.Component

/**
 * THE DICTIONARIES OF THE FACETS OF THE UNIVERSES — the kinds, the universes, the main and the secondary characters,
 * the pairings: the values every work may have, their names and groups, the works' own word on them. The universes' own
 * here; the characters' of [CharacterVocabulary], the pairings' of [PairingVocabulary].
 */
@Component
internal class UniverseVocabularies(private val stores: Stores) {
    /**
     * The vocabulary of a facet of the universes; of the works [ids], when given, what depends on the works read of
     * them alone — their universes, the characters of their other facet, their pairs — not of every work: of a work of
     * a large universe the chances of thousands of characters each. The values the model gave of the facets before it
     * are [made] in the chain; the dictionary's characters of [characters], every universe read once for the operation.
     */
    fun of(
        store: SourceStore,
        facet: FacetDef,
        ids: Collection<String>?,
        made: WorkChances,
        characters: CharactersRead,
        answers: Answers,
        light: Boolean = false,
        site: Map<String, Map<String, List<String>>>? = null,
    ): Vocabulary? {
        val type = stores.typeOf(store.id)
        if (!type.type.universes) return null
        return when (facet.key) {
            // The kinds, the universes, the characters and the pairings are named by the dictionary, whatever the
            // source ([UniverseNames])
            UniverseFacets.KIND -> Vocabulary(UniverseFacet.KINDS, null)
            // Every universe of the dictionary, linked to a work or not yet — to a work not known to have none
            UniverseFacets.UNIVERSE -> universes(store, type, ids, characters, answers, light, site)
            // The main characters and the secondary ones alike, each without what the other has
            UniverseFacets.CHARACTERS,
            UniverseFacets.SECONDARY_CHARACTERS ->
                CharacterVocabulary.of(type, facet.key, ids, made, characters, answers, light)
            UniverseFacets.PAIRINGS -> PairingVocabulary.of(store, type, made, characters, answers, light)
            else -> null
        }
    }

    /** The vocabulary of the universes: every universe of the dictionary, but none of a work of no universe. */
    private fun universes(
        store: SourceStore,
        type: TypeStore,
        ids: Collection<String>?,
        characters: CharactersRead,
        answers: Answers,
        light: Boolean,
        site: Map<String, Map<String, List<String>>>?,
    ): Vocabulary {
        val kinds = kindsOf(store, ids, answers, site)
        // Realism, an alternative history and an original work: our world, ours changed, or a world of its own
        fun original(id: String) =
            kinds[id].orEmpty().let { it.isNotEmpty() && it.all { k -> k in UniverseFacets.UNIVERSELESS } }
        // NO NAME OF IT LOOKED FOR IN THE WORK'S WORDS (user, 2026-10-16): the universe is told by the
        // work's texts, its tags and its other values; the names below are what the fitting learns from.
        // Of the works read, the universes' keys alone: not every universe's characters read to count them
        val dictionary = type.universes.dictionary()
        if (light) {
            val keys = dictionary.values
            return Vocabulary(emptyMap(), allowed = { id -> if (original(id)) emptySet() else keys })
        }
        val all = type.universes.shown.of(null, dictionary)
        return Vocabulary(
            all,
            allowed = { id -> if (original(id)) emptySet() else all.keys },
            hintNames = hintNames(type, dictionary, all, characters),
            hintAmong = { all.keys },
        )
    }

    /**
     * The kinds of the works [ids] (every work when null), the site's — of their values read already when they are
     * ([site]) — with the user's answers over them.
     */
    private fun kindsOf(
        store: SourceStore,
        ids: Collection<String>?,
        answers: Answers,
        site: Map<String, Map<String, List<String>>>?,
    ): Map<String, List<String>> {
        val kinds = HashMap<String, List<String>>()
        if (site != null) site.forEach { (id, facets) -> facets[UniverseFacets.KIND]?.let { kinds[id] = it } }
        else if (ids == null) store.items.forEachFacet(UniverseFacets.KIND) { id, keys -> kinds[id] = keys }
        else store.items.facetsOf(ids).forEach { (id, facets) -> facets[UniverseFacets.KIND]?.let { kinds[id] = it } }
        answers.all
            .filter { it.facet == UniverseFacets.KIND }
            .groupBy { it.itemId }
            .forEach { (id, own) ->
                kinds[id] =
                    Corrected.facets(mapOf(UniverseFacets.KIND to kinds[id].orEmpty()), own)[UniverseFacets.KIND]
                        .orEmpty()
            }
        return kinds
    }

    /**
     * The texts naming each universe of [all] (value to the name shown): a universe is named by its own name, by the
     * names of its sites' fandoms and of its entries, and — of one whose characters are read — by its characters
     * ("Гарри", "Хогвартс"); a character's names are texts that name every universe it is of.
     */
    private fun hintNames(
        type: TypeStore,
        dictionary: FandomDictionary,
        all: Map<String, String>,
        characters: CharactersRead,
    ): Map<String, List<String>> {
        val hintNames = HashMap<String, MutableList<String>>()
        all.forEach { (value, name) -> hintNames.getOrPut(value) { ArrayList() } += name }
        type.sources.forEach { s ->
            val facet = UniverseFacet.fandomFacet(s.schema) ?: return@forEach
            val names = s.items.facetNames(facet)
            names.forEach { (key, name) ->
                dictionary.ofSite(s.id, key)?.let {
                    hintNames.getOrPut(FandomRepository.value(it)) { ArrayList() } += name
                }
            }
        }
        val entries = dictionary.fandoms.keys.flatMap(dictionary::entriesOf).distinct()
        val entryNames = type.universes.names.entryNames(entries.map { it.value })
        dictionary.fandoms.keys.forEach { id ->
            val value = FandomRepository.value(id)
            dictionary.entriesOf(id).forEach { e ->
                entryNames[e.value]?.let { hintNames.getOrPut(value) { ArrayList() } += it }
            }
        }
        dictionary.withCharacters().forEach { (value, keys) ->
            keys.forEach { e -> characters.of(e).forEach { c -> hintNames.getOrPut(value) { ArrayList() } += c.names } }
        }
        return hintNames.mapValues { it.value.distinct() }
    }
}
