package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.universe.CharactersRead

/**
 * WHAT A PASS OVER THE FACETS OF EVERY SOURCE OF A CONTENT TYPE READS ONCE, and hands to every source and facet of it
 * (owner, 2026-11-21: "remove the repeats"): the dictionary's [characters], and the value side of every facet whose
 * dictionary is the type's — the universes with the names every site gives them, the characters, the tags of every site
 * — alike for every source, read of the first that asks for it ([valueSide]). Of the pass alone: let go with it.
 */
internal class TypePassRead(private val vocabularies: FacetVocabularies, val characters: CharactersRead) {
    /** The value sides of the type's dictionaries read so far, by the facet's key; of the pass's thread alone. */
    private val sides = HashMap<String, Vocabulary?>()

    /**
     * The value side of the [facet] of the source [store] ([FacetVocabularies.valueSide]): of a dictionary of the type,
     * the one read for the source before it; of the pairings, made of the works' [pairings] of the source.
     */
    fun valueSide(store: SourceStore, facet: FacetDef, pairings: Set<String>): Vocabulary? {
        if (vocabularies.ofWorks(facet)) return vocabularies.valueSide(store, facet, characters, pairings)
        if (facet.key !in sides) sides[facet.key] = vocabularies.valueSide(store, facet, characters, pairings)
        return sides[facet.key]
    }

    companion object {
        /** What a pass over the facets of the [type]'s sources reads once, as it begins. */
        fun of(type: TypeStore, vocabularies: FacetVocabularies) =
            TypePassRead(vocabularies, CharactersRead(type.universes))
    }
}

/**
 * WHAT A PASS OVER THE FACETS OF A SOURCE READS ONCE, before its first facet, and hands to every one of them: what the
 * pass over the [type] read once, and what the user [said] of the source's works.
 */
internal class SourcePassRead(val type: TypePassRead, val said: UserWord) {
    val characters: CharactersRead
        get() = type.characters
}
