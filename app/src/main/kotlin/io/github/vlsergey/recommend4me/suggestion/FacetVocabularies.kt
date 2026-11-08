package io.github.vlsergey.recommend4me.suggestion

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
     * The vocabulary of the facet: of the universes, or of a facet learnt from the sites' facet; null of any other.
     * [light]: what scoring the works by the scoring kept needs alone — the values each may have, the site's examples —
     * no name of any value, no group: the scoring knows them. [site]: the site's values of the works ([ids], every work
     * when null) read already: not read again. [characters]: the dictionary's characters as the operation reads them,
     * every universe once; [answers] the user's answers on the works, as the operation read them.
     */
    fun vocabularyOf(
        store: SourceStore,
        facet: FacetDef,
        ids: Collection<String>?,
        made: WorkChances,
        characters: CharactersRead,
        answers: Answers,
        light: Boolean = false,
        site: Map<String, Map<String, List<String>>>? = null,
    ): Vocabulary? =
        universes.of(store, facet, ids, made, characters, answers, light, site)
            ?: examplesVocabulary(store, facet, ids, answers, light, site)

    /**
     * The values of a facet of the layer learnt from a facet of the sites ([FacetDef.examplesFrom]): every value of
     * that facet of every source of the type, one by its name — "Магия" of one site and of another one tag — and every
     * item's own values of it as its examples; null for any other facet.
     *
     * Of the works [ids] alone, when given, or [light] — read by the model kept, which knows its values — their
     * examples alone: the values of every source, their names and the user's answers are what a fitting learns from (of
     * every work of a source, read for nothing, five seconds). [site]: the site's values of those works read already;
     * [answers]: the user's answers, as the operation read them.
     */
    private fun examplesVocabulary(
        store: SourceStore,
        facet: FacetDef,
        ids: Collection<String>?,
        answers: Answers,
        light: Boolean = false,
        site: Map<String, Map<String, List<String>>>? = null,
    ): Vocabulary? {
        val from = store.schema.facets.firstOrNull { it.key == facet.examplesFrom } ?: return null
        return if (ids != null || light) worksExamples(store, from, ids, site)
        else everyExample(store, facet, from, answers, site)
    }

    /**
     * The examples of the works [ids] alone — every work when null, of their values read already when [site] is given —
     * each work's values of the sites' facet [from] as the normalized tags they are bound to; no value named.
     */
    private fun worksExamples(
        store: SourceStore,
        from: FacetDef,
        ids: Collection<String>?,
        site: Map<String, Map<String, List<String>>>?,
    ): Vocabulary {
        val bound = store.tags.bindingsOf(store.id)
        val own = HashMap<String, List<String>>()
        fun put(id: String, keys: List<String>) {
            own[id] = keys.mapNotNull { bound[it]?.let(TagRepository::key) }.distinct()
        }
        if (site != null) (ids ?: site.keys).forEach { id -> put(id, site[id]?.get(from.key).orEmpty()) }
        else if (ids != null) store.items.facetsOf(ids).forEach { (id, facets) -> put(id, facets[from.key].orEmpty()) }
        else store.items.forEachFacet(from.key, ::put)
        return Vocabulary(emptyMap(), allowed = null, examples = { id -> own[id].orEmpty() })
    }

    /**
     * Of every work, what a fitting learns from: every normalized tag of the type with its name, every item's own
     * values as its examples, and how much a value the site lacks weighs.
     *
     * A value the site lacks says little of the work: authors leave out most tags that fit. How little, the user's
     * answers tell — of the values the site did not give a work, the share the user rejected rather than confirmed, by
     * the rule of succession: as much as a lacking value weighs as a value the work has not. [answers] and [site] (when
     * given) of every work, as the operation read them: not read again.
     */
    private fun everyExample(
        store: SourceStore,
        facet: FacetDef,
        from: FacetDef,
        answers: Answers,
        site: Map<String, Map<String, List<String>>>?,
    ): Vocabulary {
        val bindings = store.tags.bindings()
        val values = tagsOf(store, from, bindings)
        val bound = HashMap<String, Long>()
        bindings.forEach { (ref, tag) -> if (ref.source == store.id) bound[ref.tag] = tag }
        val own = HashMap<String, List<String>>()
        fun put(id: String, keys: List<String>) {
            own[id] = keys.mapNotNull { bound[it]?.let(TagRepository::key) }.distinct()
        }
        if (site != null) site.forEach { (id, facets) -> facets[from.key]?.let { put(id, it) } }
        else store.items.forEachFacet(from.key, ::put)
        val answered = answers.all.filter { it.facet == facet.key && it.key !in own[it.itemId].orEmpty() }
        val denied = answered.count { !it.added }
        return Vocabulary(
            values,
            allowed = null,
            examples = { id -> own[id].orEmpty() },
            lackWeight = (denied + 1.0) / (answered.size + 2.0),
        )
    }

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
