package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.CastReading
import io.github.vlsergey.recommend4me.cast.SaidLines
import io.github.vlsergey.recommend4me.database.DatabaseRefused
import io.github.vlsergey.recommend4me.draft.WorkDrafts
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.LayerDraft
import org.springframework.stereotype.Component

/** [WorkChances] as they are made, a facet after another. */
internal typealias MadeChances = HashMap<String, HashMap<String, Map<String, Chance>>>

/**
 * THE CHAIN OF THE FACETS: the chances of works made in memory by the models kept, a facet after another, in the order
 * of [facetsOf] — each reading as its context the values the site and the user give of every other facet and the values
 * the model gives of the facets before it, made in the same reading; of every facet what a work is shown with
 * ([ShownChances.keep]). Nothing waits for a refit, no chance is written of a work (user, 2026-10-15); the values the
 * models give every work are kept in the index of the list alone ([WorkModelValues],
 * [io.github.vlsergey.recommend4me.worklist.ListFilling]), and a pass reads those of the facets before the one it works
 * out made now by their models, just fitted ([before]).
 *
 * EVERY FACET READ, THEN WORKED OUT WITH THE DATABASE REFUSED (user, 2026-11-12): the works are read as the reading
 * begins ([WorksRead]); of every facet its model kept, the dictionary's word on its values ([Vocabulary]) and the
 * works' lines are read before it — what they are read of is the chances of the facets before it — and its chances are
 * made of what was read alone ([DatabaseRefused]). A work is scored by the model kept and its own features
 * ([FacetSuggester.scoring]): no other work of the source read.
 */
@Component
internal class FacetChain(
    private val plugins: Plugins,
    private val vocabularies: FacetVocabularies,
    private val passes: WorkPasses,
    private val casts: CastReading,
) {
    /**
     * The facets the model works on, in the order of the chain they are worked out in: those it gives values of first —
     * their values are the context of the others, the characters are of the universes given before them, the pairings
     * of the characters — in the order of the schema.
     */
    fun facetsOf(store: SourceStore): List<FacetDef> =
        store.schema.facets.filter { it.suggest || it.infer }.sortedBy { !it.infer }

    /**
     * THE CHANCES OF THE WORKS [ids] made now in memory by the models kept, down the chain of the facets; of the facets
     * up to [through] alone when given (none when the source works out no such facet). Of every facet the values shown:
     * had, rejected, given as examples, likely, and the likeliest others offered; the drafts of the layer among them
     * ([Chance.draftedFrom]). A facet never fitted has no chances, its drafts alone. Nothing is written, nothing waited
     * for: never in the queue of the computations — the marking of a work waits for nothing (user, 2026-10-15).
     * [known]: the site's values and the user's answers of the works, read already by the reading this serves.
     */
    fun chances(
        store: SourceStore,
        ids: Collection<String>,
        through: String? = null,
        laps: Laps = Laps(),
        known: EveryItemFacets? = null,
    ): WorkChances {
        val facets = facetsOf(store)
        val upTo = if (through == null) facets.size else facets.indexOfFirst { it.key == through } + 1
        return chain(store, ids, upTo, laps, known)
    }

    /**
     * [chances] of the [wanted] facets alone — those on a card, those the model of the grades reads: the chain down to
     * the last of them. [laps]: the time of every step of it.
     */
    fun chancesOf(
        store: SourceStore,
        ids: Collection<String>,
        wanted: Collection<String>,
        laps: Laps = Laps(),
        known: EveryItemFacets? = null,
    ): WorkChances =
        facetsOf(store).lastOrNull { it.key in wanted }?.let { chances(store, ids, it.key, laps, known) }
            ?: seeded(store, ids, draftsOf(store, ids, known))

    /**
     * The chances of the works [ids] of the first [upTo] facets of the chain: a portion of [TaskPortions.MOST] works at
     * most read at a time.
     */
    fun chain(
        store: SourceStore,
        ids: Collection<String>,
        upTo: Int,
        laps: Laps = Laps(),
        known: EveryItemFacets? = null,
    ): MadeChances {
        val encoder = plugins.textEncoder()
        if (encoder == null || ids.isEmpty()) return seeded(store, ids, draftsOf(store, ids, known))
        val made = MadeChances()
        ids.distinct().chunked(TaskPortions.MOST).forEach { portion ->
            passes.worksRead(store, encoder, portion, card = true, known = known).use {
                made += chain(store, encoder, it, upTo, laps)
            }
        }
        return made
    }

    /**
     * The chain over the works of [read]: what the facets read alike read once, for all of them; of every facet what it
     * is made of read, then its chances made with the database refused.
     */
    fun chain(store: SourceStore, encoder: TextEncoder, read: WorksRead, upTo: Int, laps: Laps = Laps()): MadeChances {
        val drafts = read.drafts
        laps.lap("drafts", drafts.size)
        val made = seeded(store, read.ids, drafts)
        facetsOf(store).take(upTo).forEach { facet ->
            val suggester = plugins.suggester(store.id, facet.key) ?: return@forEach
            val own = laps.within(facet.key)
            val scored = scored(store, facet, suggester, encoder, read, made, own) ?: return@forEach
            DatabaseRefused.during(computation(store, facet)) {
                scored.scores.use {
                    ShownChances.keep(made, facet, scored.data, it, laps = own, calibration = scored.calibration)
                }
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
    class Scored(
        val data: FacetData,
        val scores: SuggestionScores,
        val vocabulary: Vocabulary?,
        /** How the facet's chances are made true to the user's answers ([Calibration]); null: the suggester's own. */
        val calibration: Calibration? = null,
    )

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
                read.ids,
                made,
                read.characters,
                read.answers,
                light = true,
                site = read.site,
            )
        val lines = linesOf(store, facet, vocabulary, read)
        val siteGives = siteGives(store, facet)
        // The model's values and those of the works it was not made of, their names read now
        val values = KeptValues.of(store, facet, kept.values, read, vocabulary, vocabularies, encoder)
        laps.lap("vocabulary")
        return DatabaseRefused.during(computation(store, facet)) {
            val data =
                FacetData.load(
                    store,
                    facet,
                    encoder,
                    read,
                    made,
                    vocabulary,
                    kept = values,
                    cast = lines,
                    siteGives = siteGives,
                    laps = laps,
                )
            if (data.values.isEmpty()) null
            else
                suggester
                    .scoring(kept.bytes, data.task)
                    ?.let { Scored(data, it, vocabulary, kept.calibration) }
                    .also { laps.lap("scored", data.values.size) }
        }
    }

    /** The drafts of the layer of the works [ids], of their site's values [known] already when given. */
    private fun draftsOf(store: SourceStore, ids: Collection<String>, known: EveryItemFacets?) =
        if (known == null) WorkDrafts.of(store, ids) else WorkDrafts.of(store, ids, known.site)

    /** The drafts of the layer of the works [ids] as their chances: 1, marked so, till the model gives its own. */
    fun seeded(
        store: SourceStore,
        ids: Collection<String>,
        drafts: Map<String, List<LayerDraft>> = WorkDrafts.of(store, ids),
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
        return made
    }

    /**
     * THE CHANCES OF A PORTION OF A PASS BEFORE ITS [k]-TH FACET: the drafts of the works of [read] ([seeded]), and of
     * every facet before it in the chain its chances made now by its model kept — the one just fitted by the pass
     * before it ([chain]), the works' inputs read for it: no value of a work kept anywhere but in the index of the list
     * (user, 2026-11-18). Of the first facet the seeded alone.
     */
    fun before(store: SourceStore, encoder: TextEncoder, read: WorksRead, k: Int): MadeChances =
        if (k == 0) seeded(store, read.ids, read.drafts) else chain(store, encoder, read, k)

    /** The works' lines of characters and pairings read for the [facet]; null of a facet that reads none. */
    fun linesOf(store: SourceStore, facet: FacetDef, vocabulary: Vocabulary?, read: WorksRead): SaidLines? =
        casts.asked(store, facet, vocabulary)?.let { it.said(read.casts(it)) }

    /** Whether the site gives values of the [facet], told of every work of the source as the model was fitted. */
    fun siteGives(store: SourceStore, facet: FacetDef): Boolean = store.items.anyOf(facet.key)

    companion object {
        /** Bytes in a kilobyte: the size of the model kept, as the laps tell it. */
        private const val KB = 1024

        /** The name of the computation of the [facet]'s chances, as a refusal of the database tells it. */
        fun computation(store: SourceStore, facet: FacetDef) = "the chances of ${store.id}/${facet.key}"
    }
}
