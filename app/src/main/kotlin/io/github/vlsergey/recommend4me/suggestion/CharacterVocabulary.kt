package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.universe.CharacterSex
import io.github.vlsergey.recommend4me.universe.CharactersRead
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import java.util.concurrent.ConcurrentHashMap

/**
 * THE DICTIONARY OF THE MAIN AND THE SECONDARY CHARACTERS: every character of every universe — by all its names: the
 * fans write the one they like — and the original characters; a work is given those of its own universes. Of the works
 * read, the characters of their universes alone: no other is chosen from ([FacetData.load]).
 */
internal object CharacterVocabulary {
    /**
     * The characters of the universes of the dictionary read: each universe's characters ([ofUniverse]), every
     * character's name shown ([values]), the names it goes by ([names]) and its sex ([sexes]) — the original characters
     * among them.
     */
    private class Characters(
        val ofUniverse: Map<String, Set<String>>,
        val values: Map<String, String>,
        val names: Map<String, List<String>>,
        val sexes: Map<String, CharacterSex>,
    )

    /**
     * The vocabulary of the [facet] of characters, of the works [ids] (every work when null); [light]: what scoring the
     * works by the scoring kept needs alone — the characters each may have, those it may not, their names looked for.
     */
    fun of(
        type: TypeStore,
        facet: String,
        ids: Collection<String>?,
        made: WorkChances,
        characters: CharactersRead,
        answers: Answers,
        light: Boolean,
    ): Vocabulary {
        val universes = WorkUniverses.universesOf(answers, made)
        val dictionary = read(type, ids, universes, characters)
        val out = WorkUniverses.leftOut(answers, facet, made)
        val named = namedBy(universes, dictionary, out)
        val mayOf = mayHave(universes, dictionary.ofUniverse)
        val barred = { id: String ->
            out(id).filterTo(HashSet()) { it in mayOf(id) && it !in UniverseFacet.ORIGINAL_CHARACTERS }
        }
        if (light) return Vocabulary(emptyMap(), allowed = mayOf, names = named, barred = barred)
        val among = rivals(dictionary.ofUniverse)
        return Vocabulary(
            dictionary.values,
            hintNames = dictionary.names,
            hintAmong = among,
            allowed = mayOf,
            barred = barred,
            names = named,
            // A work's tags tell the sex of its main characters
            groupings = { list ->
                listOf(
                    ValueGrouping(
                        "sex",
                        CharacterSex.entries.map { it.name },
                        list.map { c -> dictionary.sexes[c]?.let { intArrayOf(it.ordinal) } ?: IntArray(0) },
                    )
                )
            },
        )
    }

    /**
     * The characters of the universes of the works [ids] (every universe of the dictionary when null), their entries
     * read at once: the universes by their keys alone, their characters read of those wanted. Their sexes of the same
     * reading: a universe's characters read once.
     */
    private fun read(
        type: TypeStore,
        ids: Collection<String>?,
        universes: Map<String, List<String>>,
        characters: CharactersRead,
    ): Characters {
        val read =
            type.universes.keys().let { all ->
                if (ids == null) all
                else ids.flatMap { universes[it].orEmpty() }.toSet().let { own -> all.filter { it.value in own } }
            }
        val ofUniverse = HashMap<String, Set<String>>()
        val values = HashMap<String, String>(UniverseFacet.ORIGINAL_CHARACTERS)
        val names = HashMap<String, List<String>>()
        val sexes = HashMap<String, CharacterSex>(UniverseFacet.ORIGINAL_SEXES)
        // Their entries read at once: of every work every universe of the dictionary
        if (ids == null) characters.every() else characters.read(read)
        read.forEach { u ->
            val own = characters.of(u)
            ofUniverse[u.value] = own.map { UniverseFacet.valueOf(u.catalogue, it.id) }.toSet()
            own.forEach { c ->
                val value = UniverseFacet.valueOf(u.catalogue, c.id)
                values[value] = c.names.joinToString(" / ")
                names[value] = c.names
                c.sex?.let { sexes[value] = it }
            }
        }
        return Characters(ofUniverse, values, names, sexes)
    }

    /**
     * The characters of a work's universes, looked for in its words: a work of none is not counted. One map of names
     * for every work of the same universes, made once for them all, the work's characters left out ([out]) told apart —
     * not a map of a universe's thousands of characters made and compared a work — the index reading the names once for
     * every work of the universes.
     */
    private fun namedBy(
        universes: Map<String, List<String>>,
        dictionary: Characters,
        out: (String) -> Set<String>,
    ): (String) -> Named? {
        val ofUniverses = HashMap<Set<String>, Map<String, List<String>>>()
        val nameSets = HashMap<Pair<Set<String>, Set<String>>, Named>()
        return { id: String ->
            val us = universes[id].orEmpty().toSet()
            val own =
                ofUniverses.getOrPut(us) {
                    us.flatMap { dictionary.ofUniverse[it].orEmpty() }
                        .toSet()
                        .associateWith { dictionary.names[it].orEmpty() }
                }
            val left = out(id).filterTo(HashSet()) { it in own }
            if (left.size == own.size) null else nameSets.getOrPut(us to left) { Named(own, left) }
        }
    }

    /**
     * What a work may have: one set for every work of the same universes — of a large universe thousands of characters,
     * not a copy a work — its own characters of the other facet barred apart.
     */
    private fun mayHave(
        universes: Map<String, List<String>>,
        ofUniverse: Map<String, Set<String>>,
    ): (String) -> Set<String> {
        val unions = ConcurrentHashMap<Set<String>, Set<String>>()
        return { id ->
            universes[id].orEmpty().toSet().let { us ->
                unions.computeIfAbsent(us) {
                    us.flatMap { ofUniverse[it].orEmpty() }.toSet() + UniverseFacet.ORIGINAL_CHARACTERS.keys
                }
            }
        }
    }

    /**
     * The characters a name of a character is judged among: a name of a character names it and none of the other
     * characters of its universes — judged among them all, one set for every character of the same universes.
     */
    private fun rivals(ofUniverse: Map<String, Set<String>>): (String) -> Set<String> {
        val universesOf = HashMap<String, MutableSet<String>>()
        ofUniverse.forEach { (u, members) -> members.forEach { universesOf.getOrPut(it) { HashSet() } += u } }
        val among = HashMap<Set<String>, Set<String>>()
        universesOf.values.forEach { us -> among.getOrPut(us) { us.flatMap { ofUniverse[it].orEmpty() }.toSet() } }
        return { c -> universesOf[c]?.let(among::getValue).orEmpty() }
    }
}
