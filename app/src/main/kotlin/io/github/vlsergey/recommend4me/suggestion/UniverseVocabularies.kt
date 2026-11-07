package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.universe.CharactersRead
import io.github.vlsergey.recommend4me.universe.StoredUniverse
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.universe.UniverseKey
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
        if (light) {
            val keys = type.universes.keys().map { it.value }.toSet()
            return Vocabulary(emptyMap(), allowed = { id -> if (original(id)) emptySet() else keys })
        }
        val universes = type.universes.all(characters)
        val all = universes.associate { it.value to it.name }
        return Vocabulary(
            all,
            allowed = { id -> if (original(id)) emptySet() else all.keys },
            hintNames = hintNames(universes, characters, all.keys),
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
     * The texts naming each universe of [all]: a universe is named by its characters ("Гарри", "Хогвартс") and by its
     * own name; every name of a universe and of each of its characters is a text that names it, and no other universe.
     */
    private fun hintNames(
        universes: List<StoredUniverse>,
        characters: CharactersRead,
        all: Set<String>,
    ): Map<String, List<String>> {
        val ofCharacter = HashMap<String, MutableSet<String>>()
        val names = HashMap<String, List<String>>()
        universes.forEach { u ->
            names[u.value] = u.names
            characters.of(UniverseKey(u.catalogue, u.id)).forEach { c ->
                val value = UniverseFacet.valueOf(u.catalogue, c.id)
                names[value] = c.names
                ofCharacter.getOrPut(value) { HashSet() } += u.value
            }
        }
        val hintNames = HashMap<String, MutableList<String>>()
        names.forEach { (value, own) ->
            (ofCharacter[value] ?: setOf(value).filter { it in all }).forEach { u ->
                hintNames.getOrPut(u) { ArrayList() } += own
            }
        }
        return hintNames.mapValues { it.value.distinct() }
    }
}
