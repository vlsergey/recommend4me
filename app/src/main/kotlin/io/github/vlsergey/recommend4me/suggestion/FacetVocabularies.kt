package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.agerating.AgeRatingVocabulary
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.SiteTagRef
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.universe.CharactersRead
import org.springframework.stereotype.Component

/**
 * THE DICTIONARIES OF THE FACETS: of a facet of the universes — the kinds, the universes, the main and the secondary
 * characters, the pairings — the values every work may have, their names and groups, the works' own word on them
 * ([UniverseVocabularies]); of a facet learnt from a site's facet (the tags), the site's examples. Of what the
 * operation read: the dictionary's characters ([CharactersRead]), the user's answers ([Answers]).
 */
@Component
internal class FacetVocabularies(private val stores: Stores, private val universes: UniverseVocabularies) {
    /**
     * The vocabulary of the facet of the works [ids]: of the universes, or of a facet learnt from the sites' facet;
     * null of any other. What depends on the works of them alone — the values each may have, the site's examples.
     * [light]: what scoring the works by the scoring kept needs alone — no name of any value, no group: the scoring
     * knows them; a pass over every work reads them of the value side ([valueSide]). [site]: the site's values of the
     * works read already: not read again. [characters]: the dictionary's characters as the operation reads them, every
     * universe once; [answers] the user's answers on the works, as the operation read them.
     */
    fun vocabularyOf(
        store: SourceStore,
        facet: FacetDef,
        ids: Collection<String>,
        made: WorkChances,
        characters: CharactersRead,
        answers: Answers,
        light: Boolean = false,
        site: Map<String, Map<String, List<String>>>? = null,
    ): Vocabulary? =
        universes.of(store, facet, ids, made, characters, answers, light, site)
            ?: AgeRatingVocabulary.of(store, facet, ids, site)
            ?: examplesFrom(store, facet)?.let { worksExamples(store, it, ids, site) }

    /**
     * THE VALUE SIDE OF THE FACET'S DICTIONARY of a pass over every work ([ValueSide]): the values known with the text
     * of their names, their groups, the names they go by and the values each name is judged among — nothing of a work.
     * [pairings]: of a facet of pairings, every pair of characters any work may have, of the walk over every work. Null
     * of a facet of no dictionary.
     */
    fun valueSide(store: SourceStore, facet: FacetDef, characters: CharactersRead, pairings: Set<String>): Vocabulary? =
        universes.valueSide(store, facet, characters, pairings)
            ?: examplesFrom(store, facet)?.let { everyExample(store, it) }

    /**
     * The value side of the [values] alone, of what the operation read already: what the values a model was not made of
     * are scored by ([KeptValues]) — their names and groups, no dictionary read whole (owner, 2026-11-19: "an attempt
     * to load ALL the values of any dictionary while loading the card of one work must fail"). Null of a facet of no
     * dictionary.
     */
    fun sideOf(store: SourceStore, facet: FacetDef, characters: CharactersRead, values: Set<String>): Vocabulary? =
        universes.sideOf(store, facet, characters, values)
            ?: examplesFrom(store, facet)?.let { Vocabulary(store.names(facet.key, values), allowed = null) }

    /** The sites' facet the [facet] is learnt from ([FacetDef.examplesFrom]); null of a facet learnt from none. */
    private fun examplesFrom(store: SourceStore, facet: FacetDef): FacetDef? =
        store.schema.facets.firstOrNull { it.key == facet.examplesFrom }

    /**
     * The examples of the works [ids] — of their values read already when [site] is given — each work's values of the
     * sites' facet [from] as the normalized tags they are bound to; no value named.
     */
    private fun worksExamples(
        store: SourceStore,
        from: FacetDef,
        ids: Collection<String>,
        site: Map<String, Map<String, List<String>>>?,
    ): Vocabulary {
        val own = HashMap<String, List<String>>()
        val values = site ?: store.items.facetsOf(ids)
        val bound = store.tags.bindingsOf(store.id, ids.flatMap { values[it]?.get(from.key).orEmpty() })
        ids.forEach { id ->
            own[id] = values[id]?.get(from.key).orEmpty().mapNotNull { bound[it]?.let(TagRepository::key) }.distinct()
        }
        return Vocabulary(emptyMap(), allowed = null, examples = { id -> own[id].orEmpty() })
    }

    /**
     * Of a facet learnt from the sites' facet [from], what a fitting learns from beside the works: every normalized tag
     * of the type with its name.
     */
    private fun everyExample(store: SourceStore, from: FacetDef): Vocabulary =
        Vocabulary(tagsOf(store, from, store.tags.bindings()), allowed = null)

    /**
     * Every normalized tag a site's tag of the type is bound to, of every source of the type, with its name: a tag is
     * read by its name as the user and the sites name it ([TagRepository]). [bindings]: every site's tag bound, read.
     */
    private fun tagsOf(store: SourceStore, from: FacetDef, bindings: Map<SiteTagRef, Long>): Map<String, String> {
        val values = HashMap<String, String>()
        stores.typeOf(store.id).sources.forEach { s ->
            val theirs =
                s.schema.facets.firstOrNull { it.standard != null && it.standard === from.standard } ?: return@forEach
            val names = s.names(theirs.key)
            s.items.valueUses(theirs.key).keys.forEach { key ->
                bindings[SiteTagRef(s.id, key)]?.let {
                    values.putIfAbsent(TagRepository.key(it), (names[key] ?: key).trim())
                }
            }
        }
        // Every tag's name read whole: of tens of thousands, not a thousand at a time
        store.tags.stats.shown(null).forEach { (key, name) -> if (key in values) values[key] = name }
        return values
    }
}
