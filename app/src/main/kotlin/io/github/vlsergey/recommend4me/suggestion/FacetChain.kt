package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.CastReading
import io.github.vlsergey.recommend4me.cast.SaidLines
import io.github.vlsergey.recommend4me.database.DatabaseRefused
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
 *
 * EVERY FACET READ, THEN WORKED OUT WITH THE DATABASE REFUSED (user, 2026-11-12): the works of the pass are read as it
 * begins ([WorksRead]); of every facet its model kept, the dictionary's word on its values ([Vocabulary]) and the
 * works' lines are read before it — what they are read of is the chances of the facets before it — and its chances are
 * made of what was read alone ([DatabaseRefused]). A work is scored by the model kept and its own features
 * ([FacetSuggester.scoring]): no other work of the source read.
 */
@Component
internal class FacetChain(
    private val plugins: Plugins,
    models: ObjectProvider<FacetModel>,
    private val vocabularies: FacetVocabularies,
    private val passes: WorkPasses,
    private val casts: CastReading,
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
        else passes.worksRead(store, encoder, ids, given, card = true).use { chain(store, encoder, it, upTo, laps) }
    }

    /**
     * The chain over the works of [read]: what the facets read alike read once, for all of them; of every facet what it
     * is made of read, then its chances made with the database refused.
     */
    fun chain(store: SourceStore, encoder: TextEncoder, read: WorksRead, upTo: Int, laps: Laps = Laps()): MadeChances {
        val drafts = read.drafts
        laps.lap("drafts", drafts.size)
        val made = seeded(store, read.only, drafts, read.every)
        laps.lap("models of their own", made.size)
        facetsOf(store).take(upTo).forEach { facet ->
            val suggester = plugins.suggester(store.id, facet.key) ?: return@forEach
            val own = laps.within(facet.key)
            val scored = scored(store, facet, suggester, encoder, read, made, own) ?: return@forEach
            DatabaseRefused.during(computation(store, facet)) {
                scored.scores.use { ShownChances.keep(made, facet, scored.data, it, laps = own) }
                own.lap("kept", scored.data.ids.size)
                read.given(store, facet.key, made)
            }
            own.lap("context")
            laps.restart()
        }
        return made
    }

    /**
     * The chances of a facet's works as the model scores them: the task they are of, its scores, the vocabulary read.
     */
    class Scored(val data: FacetData, val scores: SuggestionScores, val vocabulary: Vocabulary?)

    /**
     * THE FACET'S CHANCES OF THE WORKS OF [read] BY ITS MODEL KEPT ([FacetSuggester.scoring]): the model, the values
     * each work may have and the site's examples ([FacetVocabularies.vocabularyOf], light — no name of any value, no
     * group: the model knows them) and the works' lines read; the task of the works' own features made of them and
     * scored with the database refused. Null when the facet was never fitted, the model is not of its suggester or of
     * the works' inputs, or the works have no value to choose from.
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
        val kept = store.suggestions.scoring(facet.key)?.takeIf { it.suggester == suggester.id } ?: return null
        laps.lap("model kept read", kept.bytes.size / KB)
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
        val lines = linesOf(store, facet, vocabulary, read)
        val siteGives = siteGives(store, facet, read)
        laps.lap("vocabulary")
        return DatabaseRefused.during(computation(store, facet)) {
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
                    cast = lines,
                    siteGives = siteGives,
                    laps = laps,
                )
            if (data.values.isEmpty()) null
            else
                suggester
                    .scoring(kept.bytes, data.task)
                    ?.let { Scored(data, it, vocabulary) }
                    .also { laps.lap("scored", data.values.size) }
        }
    }

    /**
     * The drafts of the layer of the works [ids] (every work when null) as their chances: 1, marked so, till the model
     * gives its own; and the chances of the facets worked out by models of their own — what each reads read, its
     * chances made with the database refused.
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
        val own = ownModels.filter { store.schema.facet(it.facet) != null }.map { it to it.read(store, ids, every) }
        own.forEach { (model, read) ->
            DatabaseRefused.during("the chances of ${store.id}/${model.facet}") {
                read.chances().forEach { (id, chances) ->
                    made.getOrPut(id) { HashMap() }[model.facet] = FewChances(chances.values.toTypedArray())
                }
            }
        }
        return made
    }

    /**
     * What the model of the facet learns from — of the works [read] ([WorksRead.only] alone, when given), judged among
     * the values the model kept chooses from ([valueKeys]) — with the values the model gives of the [context] facets,
     * [made] before it in the chain: the task a fitting is made of, the names of the values read with it.
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
            cast = linesOf(store, facet, vocabulary, read),
            siteGives = siteGives(store, facet, read),
        )

    /** The works' lines of characters and pairings read for the [facet]; null of a facet that reads none. */
    private fun linesOf(store: SourceStore, facet: FacetDef, vocabulary: Vocabulary?, read: WorksRead): SaidLines? =
        casts.asked(store, facet, vocabulary)?.let { it.said(read.casts(it)) }

    /**
     * Whether the site gives values of the [facet], told of every work as the model was fitted: of the works read when
     * they are every work, else of the source.
     */
    private fun siteGives(store: SourceStore, facet: FacetDef, read: WorksRead): Boolean =
        if (read.only == null) read.site.values.any { facet.key in it }
        else store.items.valueUses(facet.key).isNotEmpty()

    companion object {
        /** Bytes in a kilobyte: the size of the model kept, as the laps tell it. */
        private const val KB = 1024

        /** The name of the computation of the [facet]'s chances, as a refusal of the database tells it. */
        fun computation(store: SourceStore, facet: FacetDef) = "the chances of ${store.id}/${facet.key}"
    }
}
