package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.database.DatabaseRefused
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.universe.CharactersRead
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.springframework.stereotype.Component

/** The values of a facet an item lacks and more likely has than not, as the interface shows them. */
class ItemFacetSuggestions(val facet: FacetDef, val suggested: List<Chance>, val names: Map<String, String>)

/**
 * A value a work may be given: the model's chance of it when it has one, how often the work's texts name it when
 * counted; [named] — offered for that count, not among the likeliest ([WorkCandidates.candidates]).
 */
class FacetCandidate(val key: String, val chance: Double?, val mentions: Int?, val named: Boolean = false)

/** The values the user may yet give a work of one facet, the likeliest first, with their names. */
class ItemFacetCandidates(val facet: FacetDef, val candidates: List<FacetCandidate>, val names: Map<String, String>)

/** What the passages naming a value are found by ([WorkCandidates.quoting]). */
sealed interface Quoting {
    /** The names looked for in the index, and those of them that name the value. */
    class InIndex(val names: Map<String, List<String>>, val of: List<String>) : Quoting

    /** The names of the characters looked for in the index, and the two of a pairing named in one paragraph. */
    class Together(val names: Map<String, List<String>>, val a: String, val b: String) : Quoting

    /** The work's words are not counted: it names nothing. */
    data object None : Quoting
}

/**
 * ONE WORK'S VALUES TO GIVE: of every facet the values the model suggests it ([ofItem]), every value it may be given
 * with its chance and how often its texts name it ([candidates]), and what the passages naming a value are found by
 * ([quoting]) — made now of this work, its facets before in the chain too.
 */
@Component
class WorkCandidates
internal constructor(
    private val stores: Stores,
    private val plugins: Plugins,
    private val chain: FacetChain,
    private val vocabularies: FacetVocabularies,
    private val universes: UniverseVocabularies,
    private val passes: WorkPasses,
) {
    /**
     * The values to suggest for an item, every facet the source suggests values of, with the user's corrections on top:
     * a value the user has answered on is left out. Of its [chances] made now ([FacetChain.chances]) — passed by a
     * caller that made them already.
     */
    fun ofItem(
        store: SourceStore,
        itemId: String,
        chances: Map<String, Map<String, Chance>>? = null,
    ): List<ItemFacetSuggestions> {
        val facets = chain.facetsOf(store)
        if (facets.isEmpty() || store.items.find(itemId) == null) return emptyList()
        val corrections = store.corrections.facetsOf(itemId)
        val site = store.items.facets(itemId)
        val made = chances ?: chain.chances(store, listOf(itemId))[itemId].orEmpty()
        return facets
            .filter { it.suggest && !it.infer }
            .map { facet ->
                val answered = corrections.filter { it.facet == facet.key }.map { it.key }.toSet()
                val current = site[facet.key].orEmpty().toSet()
                // Of a facet of one value the work has — the site's or the user's — none other is suggested
                val held =
                    facet.single && (current.isNotEmpty() || corrections.any { it.facet == facet.key && it.added })
                val suggested =
                    if (held) emptyList()
                    else
                        made[facet.key]
                            .orEmpty()
                            .values
                            .filter {
                                !it.had && it.key !in answered && it.key !in current && it.chance > ModelValues.LIKELY
                            }
                            .sortedByDescending { it.chance }
                ItemFacetSuggestions(facet, suggested, store.names(facet.key, suggested.map { it.key }))
            }
    }

    /**
     * What the passages of the item that name the [value] of the [facet] are found by, as its mentions are counted: a
     * character by its names, a universe by its own and its characters' — looked for in the index ([Quoting.InIndex]);
     * a pairing by its two named in one paragraph, of the index too ([Quoting.Together]). Null for a facet whose values
     * are not counted.
     */
    fun quoting(store: SourceStore, itemId: String, facet: String, value: String): Quoting? {
        val facets = chain.facetsOf(store)
        val k = facets.indexOfFirst { it.key == facet }
        val ids = listOf(itemId)
        val vocabulary =
            if (k < 0) null
            else
                universes.of(
                    store,
                    facets[k],
                    ids,
                    chain.chain(store, ids, k),
                    passes.charactersRead(store),
                    Answers.of(store, ids),
                )
        val of = vocabulary?.names
        return if (vocabulary == null || of == null) null else quotingBy(vocabulary, of(itemId)?.whole, value)
    }

    /**
     * What the passages naming the [value] are found by, of the work's [names] looked for by the [vocabulary]: nothing
     * of a work whose words are not counted, or of a pairing not of two characters.
     */
    private fun quotingBy(vocabulary: Vocabulary, names: Map<String, List<String>>?, value: String): Quoting {
        val pair = if (vocabulary.paired) UniverseFacet.members(value) else null
        return when {
            names == null -> Quoting.None
            pair != null -> Quoting.Together(names, pair.first, pair.second)
            vocabulary.paired -> Quoting.None
            else -> Quoting.InIndex(names, names.keys.filter { value in vocabulary.valuesOf(it) })
        }
    }

    /**
     * Every value of the [facet] the item may be given and neither has nor was answered on, with its chance and how
     * often the item's texts name it: the likeliest first, then the most named. A facet of characters or pairings
     * offers every value the work may have that the model has no chance of — none answered on to learn from, or given
     * the work after the fitting: a pairing of two characters added since — all the same, without a chance, the most
     * named first. Null for an item or a facet the model does not work on. [limit]: the first so many alone.
     *
     * THE WORK'S TEXTS ARE A SOURCE OF THE CANDIDATES, NOT OF THE MODEL (user, 2026-11-06): no model reads how often a
     * work's texts name a character or a pair of them — the index is not read when the works are ranked. They are
     * counted here, of this one work, when its candidates are asked for: with [limit], [named] more besides the
     * likeliest — the most named of the rest, each named at least [NAMED_AT_LEAST] times, marked
     * [FacetCandidate.named].
     */
    fun candidates(
        store: SourceStore,
        itemId: String,
        facet: String,
        limit: Int? = null,
        named: Int? = null,
    ): ItemFacetCandidates? {
        val facets = chain.facetsOf(store)
        val k = facets.indexOfFirst { it.key == facet }
        if (k < 0 || store.items.find(itemId) == null) return null
        val def = facets[k]
        // NOTHING WAITED FOR, NOTHING KEPT: the chances of the facets before this one — its context —
        // and of this one made now in memory by the models kept (user, 2026-10-15)
        val ids = listOf(itemId)
        val made = chain.chain(store, ids, k)
        // Every value made, not those shown alone — of a work's universes, characters and pairings, those it may have
        // The dictionary's characters read once for every reading of them below
        val characters = passes.charactersRead(store)
        val ofModel = scoredOne(store, def, itemId, made, characters)
        val answers = Answers.of(store, ids)
        val vocabulary =
            ofModel?.vocabulary ?: vocabularies.vocabularyOf(store, def, ids, made, characters, answers, light = true)
        // Of the one work: what it may have but its barred, a set of its own
        val may = vocabulary?.allowed?.invoke(itemId)?.let { it - vocabulary.barred?.invoke(itemId).orEmpty() }
        val chances =
            ofModel?.let { everyChance(def, itemId, it, made) }?.filter { may == null || it.key in may || it.had }
                ?: made[itemId].orEmpty()[facet].orEmpty().values
        // All named by the user's word: the work has its own values and is offered no other, as on its card
        val complete = facet in store.corrections.completeOf(itemId)
        // How often the work's own texts name each value — a character, two in one paragraph — read of
        // the index now, of this work and of the characters of its universes alone; null of a facet not counted
        val counted =
            if (complete) null
            else vocabulary?.mentions(store, ids, stores.typeOf(store.id).id, plugins.search)?.invoke(itemId)
        val ordered = if (complete) emptyList() else offered(store, itemId, def, made, answers, chances, may, counted)
        val candidates = withMostNamed(ordered, limit, named, counted)
        return ItemFacetCandidates(def, candidates, store.names(facet, candidates.map { it.key }))
    }

    /**
     * The values of the facet [def] offered the work of its [chances] and of those it [may] have, with how often its
     * texts name each ([counted]): the likeliest first, then the most named; a tag above another before it, whatever
     * its chance.
     */
    private fun offered(
        store: SourceStore,
        itemId: String,
        def: FacetDef,
        made: MadeChances,
        answers: Answers,
        chances: Collection<Chance>,
        may: Set<String>?,
        counted: Map<String, Int>?,
    ): List<FacetCandidate> {
        val facet = def.key
        val hierarchy = store.hierarchies()[facet]
        val shut = shut(store, itemId, facet, made, answers, hierarchy)
        fun mentionsOf(key: String) = counted?.let { it[key] ?: 0 }
        // A value the model gives the work is the work's already
        val scored =
            chances
                .filter { !it.had && it.key !in shut && !(def.infer && it.chance > ModelValues.LIKELY) }
                .map { FacetCandidate(it.key, it.chance, mentionsOf(it.key)) }
        // Of the values the work may have, those the model has no chance of — a character of a universe
        // or a pairing of characters given the work after the model was fitted: offered all the same, without a chance
        val unscored =
            if (facet !in UniverseFacet.CHARACTER_FACETS + UniverseFacets.PAIRINGS) emptyList()
            else {
                val chanced = chances.map { it.key }.toHashSet()
                may.orEmpty().filter { it !in shut && it !in chanced }.map { FacetCandidate(it, null, mentionsOf(it)) }
            }
        return (scored + unscored)
            .sortedWith(compareByDescending<FacetCandidate> { it.chance ?: -1.0 }.thenByDescending { it.mentions ?: 0 })
            // A tag above another before it, whatever its chance
            .let { list -> hierarchy?.parentsFirst(list) { it.key } ?: list }
    }

    /**
     * The values of the [facet] never offered the work: those answered on; those it has as its card shows them, of the
     * same chances — a value is never both given and offered; a character of the work's other facet of characters; a
     * tag under one the user said the work has not — it has none of them ([TagHierarchy]); a tag the user ignores.
     */
    private fun shut(
        store: SourceStore,
        itemId: String,
        facet: String,
        made: MadeChances,
        answers: Answers,
        hierarchy: TagHierarchy?,
    ): Set<String> {
        val corrections = store.corrections.facetsOf(itemId)
        val answered = corrections.filter { it.facet == facet }.map { it.key }
        val current = ModelValues.of(store.schema, store.items.facets(itemId), made[itemId])[facet].orEmpty()
        val out =
            WorkUniverses.leftOut(answers, facet, made)(itemId) +
                corrections
                    .filter { it.facet == facet && !it.added }
                    .flatMap { hierarchy?.descendants(it.key).orEmpty() } +
                store.ignoredTags()[facet].orEmpty()
        return (answered + current + out).toHashSet()
    }

    /**
     * The first [limit] of the [ordered] candidates, and besides the likeliest the [named] most named by the work's
     * texts ([counted]): each once, none of the likeliest again, each named at least [NAMED_AT_LEAST] times.
     */
    private fun withMostNamed(
        ordered: List<FacetCandidate>,
        limit: Int?,
        named: Int?,
        counted: Map<String, Int>?,
    ): List<FacetCandidate> {
        val likeliest = if (limit == null) ordered else ordered.take(limit)
        if (limit == null || named == null || counted == null) return likeliest
        val taken = likeliest.mapTo(HashSet()) { it.key }
        return likeliest +
            ordered
                .filter { it.key !in taken && (it.mentions ?: 0) >= NAMED_AT_LEAST }
                .sortedWith(
                    compareByDescending<FacetCandidate> { it.mentions ?: 0 }.thenByDescending { it.chance ?: -1.0 }
                )
                .take(named)
                .map { FacetCandidate(it.key, it.chance, it.mentions, named = true) }
    }

    /** The [facet]'s chances of the item by the model kept ([FacetChain.scored]); null when it was never fitted. */
    private fun scoredOne(
        store: SourceStore,
        facet: FacetDef,
        itemId: String,
        made: MadeChances,
        characters: CharactersRead,
    ): FacetChain.Scored? {
        val suggester = plugins.suggester(store.id, facet.key)
        val encoder = plugins.textEncoder()
        if (suggester == null || encoder == null) return null
        return passes.worksRead(store, encoder, listOf(itemId), characters = characters).use { read ->
            chain.scored(store, facet, suggester, encoder, read, made)
        }
    }

    /**
     * The chance of EVERY value of the [facet] for the item, of its chances [scored] — of a facet whose values a work
     * may have are limited ([Vocabulary.allowed]), of those it may have — those shown put among [made]; null when the
     * item is not among them, or the model does not judge it ([FacetData.judged]).
     */
    private fun everyChance(
        facet: FacetDef,
        itemId: String,
        scored: FacetChain.Scored,
        made: MadeChances,
    ): List<Chance>? {
        val data = scored.data
        val i = data.ids.indexOf(itemId).takeIf { it >= 0 && data.judged[it] } ?: return null
        return DatabaseRefused.during("the chances of every value of ${facet.key} of $itemId") {
            scored.scores.use { scores ->
                ShownChances.keep(made, facet, data, scores)
                val chances = scores.of(intArrayOf(i)).single()
                val has = data.held[i].toHashSet()
                chances.indices.map { v -> Chance(itemId, facet.key, data.values[v], v in has, chances[v].toDouble()) }
            }
        }
    }

    companion object {
        /**
         * How many times a work's texts name a value at least for it to be offered for that count alone ([candidates]):
         * the user's word, 2026-11-06.
         */
        const val NAMED_AT_LEAST = 3
    }
}
