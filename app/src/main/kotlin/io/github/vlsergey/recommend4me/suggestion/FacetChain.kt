package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.LayerDraft
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/** [WorkChances] as they are made, a facet after another. */
internal typealias MadeChances = HashMap<String, HashMap<String, Map<String, Chance>>>

/**
 * THE CHAIN OF THE FACETS: the chances of works made in memory by the models kept, a facet after another, in the order
 * of [facetsOf] — each reading as its context the values the site and the user give of every other facet and the values
 * the model gives of the facets before it, made in the same pass; of every facet what a work is shown with
 * ([ShownChances.keep]). Nothing waits for a refit, nothing is written of a work (user, 2026-10-15).
 */
@Component
internal class FacetChain(
    private val plugins: Plugins,
    models: ObjectProvider<FacetModel>,
    private val vocabularies: FacetVocabularies,
    private val passes: WorkPasses,
) {
    /**
     * The facets worked out by models of their own ([FacetModel]): no suggester of them fitted, their chances theirs.
     */
    private val ownModels: List<FacetModel> by lazy { models.orderedStream().toList() }

    /**
     * The facets the model works on, in the order of the chain they are worked out in: those it gives values of first —
     * their values are the context of the others, the characters are of the universes given before them, the pairings
     * of the characters — in the order of the schema.
     */
    fun facetsOf(store: SourceStore): List<FacetDef> =
        store.schema.facets
            .filter { (it.suggest || it.infer) && ownModels.none { m -> m.facet == it.key } }
            .sortedBy { !it.infer }

    /**
     * THE CHANCES OF THE WORKS [ids] — of every work of the source when null — made now in memory by the models kept,
     * down the chain of the facets; of the facets up to [through] alone when given (none when the source works out no
     * such facet). Of every facet the values shown: had, rejected, given as examples, likely, and the likeliest others
     * offered; the drafts of the layer among them ([Chance.draftedFrom]). A facet never fitted has no chances, its
     * drafts alone. Nothing is written, nothing waited for: never in the queue of the computations — the marking of a
     * work waits for nothing (user, 2026-10-15).
     */
    fun chances(
        store: SourceStore,
        ids: Collection<String>?,
        through: String? = null,
        laps: Laps = Laps(),
        every: EveryItemFacets? = null,
    ): WorkChances {
        val facets = facetsOf(store)
        val upTo = if (through == null) facets.size else facets.indexOfFirst { it.key == through } + 1
        return chain(store, ids, upTo, laps, every)
    }

    /**
     * [chances] of the [wanted] facets alone — those on a card, those the model of the grades reads: the chain down to
     * the last of them. [laps]: the time of every step of it. [every]: of every work ([ids] null), their facets and
     * answers read already by the reading it serves: not read again.
     */
    fun chancesOf(
        store: SourceStore,
        ids: Collection<String>?,
        wanted: Collection<String>,
        laps: Laps = Laps(),
        every: EveryItemFacets? = null,
    ): WorkChances {
        val given = every?.takeIf { ids == null }
        return facetsOf(store).lastOrNull { it.key in wanted }?.let { chances(store, ids, it.key, laps, given) }
            ?: seeded(store, ids, every = given)
    }

    /** The chances of the works [ids] (every work when null) of the first [upTo] facets of the chain. */
    fun chain(
        store: SourceStore,
        ids: Collection<String>?,
        upTo: Int,
        laps: Laps = Laps(),
        every: EveryItemFacets? = null,
    ): MadeChances {
        // The facets read already are of every work alone
        val given = every?.takeIf { ids == null }
        val encoder = plugins.textEncoder()
        return if (encoder == null || ids?.isEmpty() == true) seeded(store, ids, every = given)
        else
            passes.worksRead(store, encoder, reading(store, ids, upTo), given, card = true).use {
                chain(store, encoder, it, upTo, laps)
            }
    }

    /** The chain over the works of [read]: what the facets read alike read once, for all of them. */
    fun chain(store: SourceStore, encoder: TextEncoder, read: WorksRead, upTo: Int, laps: Laps = Laps()): MadeChances {
        val drafts = read.drafts
        laps.lap("drafts", drafts.size)
        val made = seeded(store, read.only, drafts, read.every)
        laps.lap("models of their own", made.size)
        facetsOf(store).take(upTo).forEach { facet ->
            val suggester = plugins.suggester(store.id, facet.key) ?: return@forEach
            val own = laps.within(facet.key)
            val scored = scored(store, facet, suggester, encoder, read, made, own) ?: return@forEach
            scored.scores.use { ShownChances.keep(made, facet, scored.data, it, laps = own) }
            own.lap("kept", scored.data.ids.size)
            read.given(store, facet.key, made)
            own.lap("context")
            laps.restart()
        }
        return made
    }

    /**
     * The works to read for the chances of the works [ids] (every work when null) of the first [upTo] facets of the
     * chain: every facet's suggester says what it reads ([FacetSuggester.reads]), the works read for one facet the
     * context of the next.
     */
    fun reading(store: SourceStore, ids: Collection<String>?, upTo: Int): Collection<String>? =
        facetsOf(store).take(upTo).fold(ids) { asked, facet ->
            val suggester = plugins.suggester(store.id, facet.key)
            if (asked == null || suggester == null) asked else suggester.reads(asked)
        }

    /**
     * The chances of a facet's works as the model scores them: the task they are of, its scores, the vocabulary read.
     */
    class Scored(val data: FacetData, val scores: SuggestionScores, val vocabulary: Vocabulary?)

    /**
     * THE FACET'S CHANCES OF THE WORKS OF [read], by the model kept. By its scoring kept apart ([KeptScoring]) where
     * there is one: the works' own features read, the values those of the scoring — no dictionary, no name of any value
     * (user, 2026-10-16): a page of twenty works read every character's name and the model whole, and a few of them at
     * once ran the heap out. Else — of a suggester without one, of a model fitted before scorings were kept, till the
     * next refresh keeps it — on the whole task. Null when the facet was never fitted.
     */
    fun scored(
        store: SourceStore,
        facet: FacetDef,
        suggester: FacetSuggester,
        encoder: TextEncoder,
        read: WorksRead,
        made: WorkChances,
        laps: Laps = Laps(),
    ): Scored? {
        val kept = store.suggestions.scoring(facet.key)?.takeIf { it.suggester == suggester.id }
        val stored =
            if (kept != null) null else store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id }
        return when {
            kept != null -> byScoring(store, facet, suggester, encoder, read, made, laps, kept)
            stored != null -> onWholeTask(store, facet, suggester, encoder, read, made, laps, stored)
            else -> null
        }
    }

    /**
     * The facet's chances of the works of [read] by its scoring [kept]: the works' own features read, the values those
     * of the scoring. Null when the works have no value to choose from, or the suggester reads no such scoring.
     */
    private fun byScoring(
        store: SourceStore,
        facet: FacetDef,
        suggester: FacetSuggester,
        encoder: TextEncoder,
        read: WorksRead,
        made: WorkChances,
        laps: Laps,
        kept: KeptScoring,
    ): Scored? {
        laps.lap("scoring kept read", kept.bytes.size / KB)
        val vocabulary =
            vocabularies.vocabularyOf(
                store,
                facet,
                read.only,
                made,
                read.characters,
                read.answers,
                light = true,
                site = read.site,
            )
        laps.lap("vocabulary")
        val data =
            FacetData.load(
                store,
                facet,
                encoder,
                read,
                modelled = made,
                allowed = vocabulary?.allowed,
                barred = vocabulary?.barred,
                examples = vocabulary?.examples,
                kept = kept.values,
                laps = laps,
            )
        if (data.values.isEmpty()) return null
        return suggester
            .scoring(kept.bytes, data.task)
            ?.let { Scored(data, it, vocabulary) }
            .also { laps.lap("scored", data.values.size) }
    }

    /**
     * The facet's chances of the works of [read] by its suggester [stored] whole, on the whole task: of a suggester
     * without a scoring kept, of a model fitted before scorings were kept, till the next refresh keeps it. Null when
     * the works have no value to choose from, or the suggester cannot unpack it.
     */
    private fun onWholeTask(
        store: SourceStore,
        facet: FacetDef,
        suggester: FacetSuggester,
        encoder: TextEncoder,
        read: WorksRead,
        made: WorkChances,
        laps: Laps,
        stored: StoredSuggester,
    ): Scored? {
        val ids = read.only
        val vocabulary =
            vocabularies.vocabularyOf(store, facet, ids, made, read.characters, read.answers, site = read.site)
        val data = load(store, facet, encoder, vocabulary, made, stored.values, read)
        val fitted =
            data
                .takeIf { it.values.isNotEmpty() }
                ?.let {
                    laps.lap("whole task read, no scoring kept")
                    suggester.unpack(stored.content, it.task)
                }
        return fitted?.let { Scored(data, it.on(data.task), vocabulary).also { laps.lap("scored", data.values.size) } }
    }

    /**
     * The drafts of the layer of the works [ids] (every work when null) as their chances: 1, marked so, till the model
     * gives its own.
     */
    fun seeded(
        store: SourceStore,
        ids: Collection<String>?,
        drafts: Map<String, List<LayerDraft>> = if (ids == null) store.drafts.all() else store.drafts.ofItems(ids),
        every: EveryItemFacets? = null,
    ): MadeChances {
        val made = MadeChances()
        drafts.forEach { (id, list) ->
            list
                .groupBy { it.facet }
                .forEach { (facet, drafts) ->
                    made.getOrPut(id) { HashMap() }[facet] =
                        FewChances(
                            drafts
                                .associateBy { it.key }
                                .values
                                .map { d -> Chance(id, facet, d.key, had = false, chance = 1.0, draftedFrom = d.from) }
                                .toTypedArray()
                        )
                }
        }
        // The facets worked out by models of their own: their chances as theirs, before the chain
        ownModels
            .filter { store.schema.facet(it.facet) != null }
            .forEach { model ->
                model.chances(store, ids, every).forEach { (id, chances) ->
                    made.getOrPut(id) { HashMap() }[model.facet] = FewChances(chances.values.toTypedArray())
                }
            }
        return made
    }

    /**
     * What the model of the facet reads — of the works [read] ([WorksRead.only] alone, when given), judged among the
     * values the model kept chooses from ([valueKeys]) — with the values the model gives of the [context] facets,
     * [made] before it in the chain.
     */
    fun load(
        store: SourceStore,
        facet: FacetDef,
        encoder: TextEncoder,
        vocabulary: Vocabulary?,
        made: WorkChances,
        valueKeys: List<String>?,
        read: WorksRead,
    ) =
        FacetData.load(
            store,
            facet,
            encoder,
            read,
            vocabulary?.values.orEmpty(),
            modelled = made,
            valueKeys = valueKeys,
            allowed = vocabulary?.allowed,
            barred = vocabulary?.barred,
            groupings = vocabulary?.groupings,
            examples = vocabulary?.examples,
            lackWeight = vocabulary?.lackWeight ?: 1.0,
            hintNames = vocabulary?.hintNames.orEmpty(),
            hintAmong = vocabulary?.hintAmong ?: { emptyList() },
        )

    companion object {
        /** Bytes in a kilobyte: the size of the scoring kept, as the laps tell it. */
        private const val KB = 1024
    }
}
