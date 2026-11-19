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
     * The vocabulary of the [facet] of characters of the works [ids]; [light]: what scoring the works by the scoring
     * kept needs alone — the characters each may have, those it may not, their names looked for.
     */
    fun of(
        type: TypeStore,
        facet: String,
        ids: Collection<String>,
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
        return valueSideOf(dictionary).let {
            Vocabulary(
                it.values,
                hintNames = it.hintNames,
                hintAmong = it.hintAmong,
                allowed = mayOf,
                barred = barred,
                names = named,
                groupings = it.groupings,
            )
        }
    }

    /**
     * The value side of the dictionary of the characters of a pass over every work ([ValueSide]): every character of
     * every universe of the dictionary — and the original ones — with its names, its rivals and its sex.
     */
    fun valueSide(type: TypeStore, characters: CharactersRead): Vocabulary =
        valueSideOf(read(type, null, emptyMap(), characters))

    /**
     * The value side of the characters [values] alone, of the universes the operation read already ([CharactersRead]):
     * what the values a model was not made of are scored by ([KeptValues]) — their names and their sexes.
     */
    fun sideOf(characters: CharactersRead, values: Set<String>): Vocabulary {
        val found = characters.ofValues(values)
        val names = found.mapValues { it.value.names }
        val sexes = HashMap<String, CharacterSex>(UniverseFacet.ORIGINAL_SEXES)
        found.forEach { (v, c) -> c.sex?.let { sexes[v] = it } }
        val shown =
            UniverseFacet.ORIGINAL_CHARACTERS.filterKeys { it in values } +
                names.mapValues { it.value.joinToString(" / ") }
        return valueSideOf(Characters(emptyMap(), shown, names, sexes))
    }

    /** The values of the [dictionary] read, the names they go by, the rivals of each name, their sexes. */
    private fun valueSideOf(dictionary: Characters): Vocabulary =
        Vocabulary(
            dictionary.values,
            allowed = null,
            hintNames = dictionary.names,
            hintAmong = rivals(dictionary.ofUniverse),
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
        // The entries of every universe whose characters are read: of the works' universes — of their ids alone —, or
        // of every one
        val read =
            if (ids == null) type.universes.dictionary().withCharacters()
            else {
                val own = ids.flatMap { universes[it].orEmpty() }.toSet()
                val dictionary = type.universes.dictionaryOf(own)
                own.associateWith(dictionary::characterEntries).filterValues { it.isNotEmpty() }
            }
        val ofUniverse = HashMap<String, Set<String>>()
        val values = HashMap<String, String>(UniverseFacet.ORIGINAL_CHARACTERS)
        val names = HashMap<String, List<String>>()
        val sexes = HashMap<String, CharacterSex>(UniverseFacet.ORIGINAL_SEXES)
        // Their entries read at once: of every work every entry of the dictionary
        if (ids == null) characters.every() else characters.read(read.values.flatten().distinct())
        read.forEach { (u, entries) ->
            val own = entries.flatMap { e -> characters.of(e).map { e to it } }
            ofUniverse[u] = own.map { (e, c) -> UniverseFacet.valueOf(e.catalogue, c.id) }.toSet()
            own.forEach { (e, c) ->
                val value = UniverseFacet.valueOf(e.catalogue, c.id)
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
