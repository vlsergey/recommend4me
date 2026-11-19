package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.SaidLines
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * Everything of one facet of some works a suggester is handed — a portion of a pass, or the works of a request — read
 * in one pass per table: every item with its texts in every view and its values, the values chosen from, the values of
 * the item's other facets as its context.
 */
internal class FacetData(
    val ids: List<String>,
    val values: List<String>,
    val task: SuggestionTask,
    /**
     * The values each item has of the facet itself — the site's, the user's answers over them: not the examples the
     * model learns from besides.
     */
    val held: List<IntArray>,
    /** Whether the user said each item's values of the facet are all named: it is given no other. */
    val complete: BooleanArray,
    /**
     * Whether the model judges each item: one of its texts encoded at least ([WorksRead.encoded]). An item with none —
     * captured just now, its texts not encoded yet — has no chance of the model till they are: of its inputs all
     * absent, a state no item it learnt from was in, the model's scores tell nothing — of the tags of a site, every tag
     * more likely than not.
     */
    val judged: BooleanArray,
    /**
     * Of each item, the values of the facet the user said it has ([saidYes]) and has not ([saidNo]), as the user said
     * them — not spread over the values' hierarchy —, of a work said all named every value it has not: what the chances
     * are calibrated by ([Calibration]).
     */
    val saidYes: List<IntArray> = emptyList(),
    val saidNo: List<IntArray> = emptyList(),
) {
    companion object {
        /**
         * THE TASK OF THE WORKS OF [read] — their inputs, values, context are read of them alone — judged among the
         * values of the [side] of a pass, of their names, groups and texts and of its context's columns, or of the
         * scoring kept ([kept]): its values those of the scoring, in its order, without names, groups or texts of them
         * — the scoring knows each; the model reads a task by the keys of its values, its context and its blocks.
         *
         * [modelled]: the values the model gives of the facets before this one in the chain — made in the same reading,
         * or kept of the same pass — and the drafts of the layer; of the others the site's and the user's alone.
         * [allowed], [barred], [examples]: the dictionary's word on the values each work may have, may not after all,
         * is given by the site as examples ([Vocabulary]).
         *
         * [siteGives]: whether the site gives values of the facet, told of every work as the model was fitted; [cast]:
         * the works' lines read for the facet. Both read before, as everything the task is made of besides the works:
         * the task of the chances is made with the database refused
         * ([DatabaseRefused][io.github.vlsergey.recommend4me.database.DatabaseRefused]).
         */
        fun load(
            store: SourceStore,
            facet: FacetDef,
            encoder: TextEncoder,
            read: WorksRead,
            modelled: WorkChances,
            vocabulary: Vocabulary?,
            side: ValueSide? = null,
            kept: KeptValues? = null,
            cast: SaidLines? = null,
            siteGives: Boolean = false,
            laps: Laps = Laps(),
        ): FacetData =
            FacetRead(
                    store,
                    facet,
                    encoder,
                    read,
                    FacetAsked(modelled, vocabulary, side, kept, cast, siteGives),
                    laps,
                )
                .data()

        /**
         * What the works of [read] tell of the value side of a pass over the [facet] ([PortionCount]): their values,
         * the keys of their context, the texts of their lines with vectors. Their vectors not read: [read] of their
         * values alone.
         */
        fun count(
            store: SourceStore,
            facet: FacetDef,
            encoder: TextEncoder,
            read: WorksRead,
            modelled: WorkChances,
            vocabulary: Vocabulary?,
            cast: SaidLines?,
        ): PortionCount =
            FacetRead(store, facet, encoder, read, FacetAsked(modelled, vocabulary, null, null, cast, false), Laps())
                .counted()
    }
}

/**
 * What a facet's task is read with besides the works ([FacetData.load]): the model's values of the facets before and
 * the drafts ([modelled]), the dictionary's word on the values ([vocabulary]: those each work may have and may not, the
 * site's examples), and the values chosen from — of a pass's value [side], or of the scoring kept ([kept]).
 */
internal class FacetAsked(
    val modelled: WorkChances,
    val vocabulary: Vocabulary?,
    val side: ValueSide?,
    val kept: KeptValues?,
    /** The site's lines of characters and pairings read for the facet; null of a facet that reads none. */
    val cast: SaidLines?,
    /** Whether the site gives values of the facet, told of every work, as the model was fitted. */
    val siteGives: Boolean,
) {
    val allowed: ((String) -> Set<String>?)?
        get() = vocabulary?.allowed

    val barred: ((String) -> Set<String>)?
        get() = vocabulary?.barred

    val examples: ((String) -> List<String>)?
        get() = vocabulary?.examples
}
