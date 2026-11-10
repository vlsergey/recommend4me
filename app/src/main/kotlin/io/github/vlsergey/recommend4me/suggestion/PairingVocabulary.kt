package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.universe.CharacterSex
import io.github.vlsergey.recommend4me.universe.CharactersRead
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets

/**
 * THE DICTIONARY OF THE PAIRINGS: every pair of a work's characters — the confirmed ones and those the model gives it —
 * named by its two characters, grouped by their sexes and by the characters themselves.
 */
internal object PairingVocabulary {
    /**
     * The vocabulary of the pairings of the works the user's [answers] and the model's characters [made] tell of;
     * [light]: what scoring the works by the scoring kept needs alone — the pairs each may have, their names looked
     * for.
     */
    fun of(
        store: SourceStore,
        type: TypeStore,
        made: WorkChances,
        characters: CharactersRead,
        answers: Answers,
        light: Boolean,
    ): Vocabulary {
        val linkedCharacters = charactersOf(answers, made)
        val pairs = pairsOf(answers, linkedCharacters)
        // The names of the characters of the works' universes, looked for in their words: two named in one
        // paragraph. The original characters have no names to count; one map for every work of the same
        // characters
        val named = pairedNames(store, type, answers, made, characters, linkedCharacters)
        if (light) return Vocabulary(emptyMap(), allowed = { id -> pairs[id].orEmpty() }, names = named, paired = true)
        val values = namesOf(store, pairs)
        // A pairing of two characters of the dictionary is named "A / B"; it names no other pairing of either
        // of them
        val ofTwoKnown = values.filterKeys { p ->
            UniverseFacet.members(p)?.toList()?.none { it in UniverseFacet.ORIGINAL_CHARACTERS } == true
        }
        return Vocabulary(
            values,
            hintNames = ofTwoKnown.mapValues { listOf(it.value) },
            hintAmong = rivals(values.keys),
            allowed = { id -> pairs[id].orEmpty() },
            names = named,
            paired = true,
            // Their names alone are what the model reads; shown with the sign of their characters' sexes
            // ([UniverseNames])
            groupings = groupings(UniverseFacet.sexes(type.universes, characters)),
        )
    }

    /** Every work's characters: the confirmed ones and those the model gives it. */
    private fun charactersOf(answers: Answers, made: WorkChances): Map<String, List<String>> {
        val linkedCharacters = WorkUniverses.linked(answers, UniverseFacets.CHARACTERS).toMutableMap()
        made.forEach { (id, chances) ->
            val given = ModelValues.given(chances[UniverseFacets.CHARACTERS].orEmpty())
            if (given.isNotEmpty()) linkedCharacters[id] = linkedCharacters[id].orEmpty() + given
        }
        return linkedCharacters
    }

    /**
     * Every pair of each work's [characters] but those the user rejected: the original characters pair with anyone — a
     * work need not list them among its main ones.
     */
    private fun pairsOf(answers: Answers, characters: Map<String, List<String>>): Map<String, Set<String>> {
        val rejected =
            answers.all.filter { it.facet == UniverseFacets.CHARACTERS && !it.added }.groupBy({ it.itemId }, { it.key })
        return characters.mapValues { (id, own) ->
            val kept =
                (own + UniverseFacet.ORIGINAL_CHARACTERS.keys - rejected[id].orEmpty().toSet()).distinct().sorted()
            kept.flatMapIndexed { k, a -> kept.drop(k + 1).map { b -> UniverseFacet.pairing(a, b) } }.toSet()
        }
    }

    /** Every pairing of the works' [pairs] with its name, "A / B" of its two characters' names. */
    private fun namesOf(store: SourceStore, pairs: Map<String, Set<String>>): Map<String, String> {
        val names =
            UniverseFacet.ORIGINAL_CHARACTERS +
                store.names(
                    UniverseFacets.CHARACTERS,
                    pairs.values.flatten().flatMap { p -> UniverseFacet.members(p)?.toList().orEmpty() },
                )
        return pairs.values.flatten().toSet().associateWith { p ->
            UniverseFacet.members(p)!!.let { (a, b) -> "${names[a] ?: a} / ${names[b] ?: b}" }
        }
    }

    /** The pairings a text naming a pairing is judged among: its own among them — one of each of its characters'. */
    private fun rivals(pairings: Set<String>): (String) -> Set<String> {
        val ofMember = HashMap<String, MutableSet<String>>()
        pairings.forEach { p ->
            UniverseFacet.members(p)?.toList()?.forEach { ofMember.getOrPut(it) { HashSet() } += p }
        }
        return { p -> UniverseFacet.members(p)?.toList().orEmpty().flatMap { ofMember[it].orEmpty() }.toSet() }
    }

    /**
     * The groupings of the pairings, by their characters' [sexes]: the sexes of a pairing's two characters, whether
     * they are one sex, and the two characters themselves.
     */
    private fun groupings(sexes: Map<String, CharacterSex>): (List<String>) -> List<ValueGrouping> {
        // The sexes of a pairing's two characters, and whether they are one sex: two groupings
        val kinds = CharacterSex.entries.flatMap { x -> CharacterSex.entries.filter { it >= x }.map { x to it } }
        return { list ->
            val of = list.map { UniverseFacet.sexesOf(it, sexes) }
            fun one(group: Int?) = group?.let { intArrayOf(it) } ?: IntArray(0)
            // A pairing is in the groups of its two characters: what is learnt of a
            // character's pairings holds for a pairing of it never seen with a work
            val members = list.map { UniverseFacet.members(it)?.toList().orEmpty() }
            val characters = members.flatten().distinct().sorted()
            val at = characters.withIndex().associate { (i, c) -> c to i }
            listOf(
                ValueGrouping("sexes", kinds.map { (x, y) -> "$x+$y" }, of.map { one(it?.let(kinds::indexOf)) }),
                ValueGrouping(
                    "one sex",
                    listOf("same", "different"),
                    of.map { s ->
                        one(
                            s?.takeIf { (x, y) -> x != CharacterSex.OTHER && y != CharacterSex.OTHER }
                                ?.let { (x, y) -> if (x == y) 0 else 1 }
                        )
                    },
                ),
                ValueGrouping("characters", characters, members.map { m -> m.map { at.getValue(it) }.toIntArray() }),
            )
        }
    }

    /**
     * The names of every work's characters [characters] — of the universes of the works, every work — a map for every
     * work of two characters and more besides the original ones, one map for every work of the same: what two of them
     * named in one paragraph are looked for by.
     */
    private fun pairedNames(
        store: SourceStore,
        type: TypeStore,
        answers: Answers,
        made: WorkChances,
        read: CharactersRead,
        characters: Map<String, List<String>>,
    ): (String) -> Named? {
        val universes = WorkUniverses.universesOf(answers, made)
        val own = universes.values.flatten().toSet()
        val known = HashMap<String, List<String>>()
        val dictionary = type.universes.dictionary()
        val wanted = own.flatMap(dictionary::characterEntries).distinct()
        read.read(wanted)
        wanted.forEach { u -> read.of(u).forEach { known[UniverseFacet.valueOf(u.catalogue, it.id)] = it.names } }
        val missing =
            characters.values.flatten().toSet().filter { it !in known && it !in UniverseFacet.ORIGINAL_CHARACTERS }
        val shown = store.names(UniverseFacets.CHARACTERS, missing)
        val sets = HashMap<Set<String>, Named>()
        return { id ->
            val named = characters[id].orEmpty().toSet() - UniverseFacet.ORIGINAL_CHARACTERS.keys
            if (named.size < 2) null
            else
                sets.getOrPut(named) {
                    Named(named.associateWith { known[it] ?: listOfNotNull(shown[it]) }, emptySet())
                }
        }
    }
}
