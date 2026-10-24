package io.github.vlsergey.recommend4me.pairing

import io.github.vlsergey.recommend4me.api.model.Pairing
import io.github.vlsergey.recommend4me.api.model.PairingCharacter
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/**
 * THE PAIRINGS KNOWN, none kept of their own: every pairing the models of the sources of the types
 * with universes know, with the weight the model gives it over the works as a whole
 * ([FacetSuggestions.weights]), and every pairing a work was answered on or drafted from the site's
 * tags — of each, its characters, their universes, and the user's words on the works by it. A new
 * pairing is but its key: marked on, it is known. The works are marked by a pairing on its screen
 * through the confirming of the marking ([Marking][io.github.vlsergey.recommend4me.marking.Marking]).
 */
@Service
class Pairings(private val stores: Stores, private val suggestions: FacetSuggestions) {

    /** The types with universes; the dictionary of universes is every type's, read through the first. */
    private fun types(): List<TypeStore> = stores.types.filter { it.type.universes }

    /** What is said of the pairings by the user and the models of every source: what [list] and [one] read. */
    private class Known {
        val yes = HashMap<String, Int>()
        val no = HashMap<String, Int>()
        val unknown = HashMap<String, Int>()
        val drafted = HashSet<String>()

        /** The weight of a pairing: the largest any source's model gives it. */
        val weights = HashMap<String, Double>()
    }

    private fun known(types: List<TypeStore>): Known {
        val out = Known()
        types.flatMap { it.sources }.forEach { s ->
            s.corrections.allFacets().filter { it.facet == UniverseFacets.PAIRINGS }.forEach { c -> (if (c.added) out.yes else out.no).merge(c.key, 1, Int::plus) }
            s.unknowns.ofFacet(UniverseFacets.PAIRINGS).forEach { out.unknown.merge(it.key, 1, Int::plus) }
            s.drafts.ofFacets(listOf(UniverseFacets.PAIRINGS)).values.flatten().forEach { out.drafted += it.key }
            suggestions.weights(s, UniverseFacets.PAIRINGS)?.forEach { (key, w) -> out.weights.merge(key, w, ::maxOf) }
        }
        return out
    }

    fun list(): List<Pairing> {
        val types = types()
        val dictionary = types.firstOrNull() ?: return emptyList()
        val known = known(types)
        val keys = (known.weights.keys + known.yes.keys + known.no.keys + known.unknown.keys + known.drafted).filter { UniverseFacet.members(it) != null }
        return described(dictionary, keys, known).sortedBy { it.name.lowercase() }
    }

    /** One pairing as listed, known or not — a new one is its key alone. A key of no two characters is refused. */
    fun one(key: String): Pairing {
        val types = types()
        val dictionary = types.firstOrNull() ?: throw Refusal.NO_UNIVERSES.refuse(HttpStatus.BAD_REQUEST, "No type has universes")
        if (UniverseFacet.members(key) == null) throw Refusal.NO_VALUE.refuse(HttpStatus.BAD_REQUEST, "'$key' is no pairing of two characters")
        return described(dictionary, listOf(key), known(types)).single()
    }

    /** The pairings [keys] named, with their characters and universes, and what is [known] of them. */
    private fun described(dictionary: TypeStore, keys: List<String>, known: Known): List<Pairing> {
        if (keys.isEmpty()) return emptyList()
        val members = keys.associateWith { UniverseFacet.members(it)!! }
        val characters = members.values.flatMap { it.toList() }.distinct()
        val named = dictionary.universes.characterNames(characters)
        val universesOf = dictionary.universes.characterUniverses()
        val universeNames = dictionary.universes.universeNames(characters.flatMap { universesOf[it].orEmpty() }.distinct())
        val names = dictionary.sources.firstOrNull()?.names(UniverseFacets.PAIRINGS, keys).orEmpty()
        return keys.map { key ->
            val (a, b) = members.getValue(key)
            // A character the dictionary has not — a name the user typed, one of a universe removed since — by what is known of it
            val ofPair = listOf(a, b).map { c -> PairingCharacter(c, named[c]?.first ?: UniverseFacet.ORIGINAL_CHARACTERS[c] ?: c, UniverseFacet.originalCharacterKey(c)) }
            Pairing(
                key = key,
                name = names[key]?.takeIf { it != key } ?: ofPair.joinToString(" / ") { it.name },
                characters = ofPair,
                universes = listOf(a, b).flatMap { universesOf[it].orEmpty() }.distinct().mapNotNull { universeNames[it] },
                yes = known.yes[key] ?: 0,
                no = known.no[key] ?: 0,
                unknown = known.unknown[key] ?: 0,
                weight = known.weights[key],
            )
        }
    }
}
