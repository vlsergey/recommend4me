package io.github.vlsergey.recommend4me.pairing

import io.github.vlsergey.recommend4me.api.model.Pairing
import io.github.vlsergey.recommend4me.api.model.PairingCharacter
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * THE PAIRINGS KNOWN: those the user made of two characters of the dictionary, and every pairing a
 * work of any source of a type with universes was answered on or drafted from the site's tags — of
 * each, its characters, their universes, and the user's words on the works by it. The works are
 * marked by a pairing on its screen through the confirming of the marking ([Marking][io.github.vlsergey.recommend4me.marking.Marking]).
 */
@Service
class Pairings(private val stores: Stores) {

    /** The types with universes; the dictionary of universes and pairings is every type's, read through the first. */
    private fun types(): List<TypeStore> = stores.types.filter { it.type.universes }

    fun list(): List<Pairing> {
        val types = types()
        val dictionary = types.firstOrNull() ?: return emptyList()
        val made = dictionary.pairings.all()
        val yes = HashMap<String, Int>()
        val no = HashMap<String, Int>()
        val unknown = HashMap<String, Int>()
        val drafted = HashSet<String>()
        types.flatMap { it.sources }.forEach { s ->
            s.corrections.allFacets().filter { it.facet == UniverseFacets.PAIRINGS }.forEach { c -> (if (c.added) yes else no).merge(c.key, 1, Int::plus) }
            s.unknowns.ofFacet(UniverseFacets.PAIRINGS).forEach { unknown.merge(it.key, 1, Int::plus) }
            s.drafts.all().values.flatten().filter { it.facet == UniverseFacets.PAIRINGS }.forEach { drafted += it.key }
        }
        val keys = (made.keys + yes.keys + no.keys + unknown.keys + drafted).filter { UniverseFacet.members(it) != null }
        return described(dictionary, keys) { key -> Counts(key in made, yes[key] ?: 0, no[key] ?: 0, unknown[key] ?: 0) }
            .sortedBy { it.name.lowercase() }
    }

    /** Of a pairing: whether the user made it, and how many works the user said have it, have it not, are not clear of. */
    private data class Counts(val made: Boolean, val yes: Int, val no: Int, val unknown: Int)

    /** The pairings [keys] named, with their characters and universes, and their [counts]. */
    private fun described(dictionary: TypeStore, keys: List<String>, counts: (String) -> Counts): List<Pairing> {
        if (keys.isEmpty()) return emptyList()
        val members = keys.associateWith { UniverseFacet.members(it)!! }
        val characters = members.values.flatMap { it.toList() }.distinct()
        val named = dictionary.universes.characterNames(characters)
        val universesOf = dictionary.universes.characterUniverses()
        val universeNames = dictionary.universes.universeNames(characters.flatMap { universesOf[it].orEmpty() }.distinct())
        val names = dictionary.sources.firstOrNull()?.names(UniverseFacets.PAIRINGS, keys).orEmpty()
        return keys.map { key ->
            val (a, b) = members.getValue(key)
            val (made, yes, no, unknown) = counts(key)
            // A character the dictionary has not — a name the user typed, one of a universe removed since — by what is known of it
            val ofPair = listOf(a, b).map { c -> PairingCharacter(c, named[c]?.first ?: UniverseFacet.ORIGINAL_CHARACTERS[c] ?: c, UniverseFacet.originalCharacterKey(c)) }
            Pairing(
                key = key,
                name = names[key]?.takeIf { it != key } ?: ofPair.joinToString(" / ") { it.name },
                characters = ofPair,
                universes = listOf(a, b).flatMap { universesOf[it].orEmpty() }.distinct().mapNotNull { universeNames[it] },
                made = made, yes = yes, no = no, unknown = unknown,
            )
        }
    }

    /**
     * A pairing of two characters of the dictionary — an original character counts as one — made,
     * known before any work has it; the pairing as listed.
     */
    fun make(first: String, second: String): Pairing {
        val dictionary = types().firstOrNull() ?: throw Refusal.NO_UNIVERSES.refuse(HttpStatus.BAD_REQUEST, "No type has universes")
        if (first == second) throw Refusal.NO_VALUE.refuse(HttpStatus.BAD_REQUEST, "A pairing of $first with itself")
        val known = dictionary.universes.characterUniverses().keys
        listOf(first, second).firstOrNull { it !in known && it !in UniverseFacet.ORIGINAL_CHARACTERS }?.let {
            throw Refusal.NOT_IN_DICTIONARY.refuse(HttpStatus.BAD_REQUEST, "$it is no character of the dictionary", "value" to it)
        }
        val key = UniverseFacet.pairing(first, second)
        dictionary.pairings.add(key, Instant.now())
        return list().first { it.key == key }
    }
}
