package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.SaidLines
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * Everything of one facet of a source a suggester is handed, read in one pass per table: every item with its texts in
 * every view and its values, every value with the vector of its name, the values of the item's other facets as its
 * context.
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
    /** The fingerprint of the whole facet: of every item's; [NO_BASIS] of the task of some works read alone. */
    val basis: Long,
) {
    companion object {
        /**
         * How much a drafted value the user has not confirmed weighs in what the model learns, against 1 of a value the
         * site or the user gives ([SuggestionTask.draftWeight]): a draft is the dictionary matched to the site's tags —
         * likely, but no one's word.
         */
        const val DRAFT_WEIGHT = 0.5

        /** The basis of the task of one item: none — it is never the basis the weights were fitted on. */
        const val NO_BASIS = 0L

        /**
         * [known]: values of the facet the application knows besides the items' — the universes and characters of the
         * dictionary — with the text their names are encoded from. [examples]: the values the site gives an item as
         * examples of the facet, the item's own as far as the model learns ([FacetDef.examplesFrom]); a value it lacks
         * weighs [lackWeight].
         *
         * [read]: the works' own, read once for every facet of a pass. Of the works [WorksRead.only]: THE TASK OF THOSE
         * WORKS ALONE — their rows, judged by the model kept, which reads a task by the keys of its values, its context
         * and its blocks: their inputs, values, context are read of them alone, not of every work; the values chosen
         * from are those the model kept chooses from ([valueKeys]) and their own. No [basis]: what the weights were
         * fitted on is told by the background refresh, which reads every work.
         *
         * [modelled]: the values the model gives of the facets before this one in the chain, made in the same pass, and
         * the drafts of the layer; of the others the site's and the user's alone.
         *
         * [kept]: THE TASK OF THE SCORING KEPT ([FittedSuggester.model]) — its values those of the scoring, in its
         * order, without names, groups or texts of them: the scoring knows each; the works' own features alone read, of
         * every work or of the works [WorksRead.only]. No [basis].
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
            known: Map<String, String> = emptyMap(),
            modelled: WorkChances = emptyMap(),
            valueKeys: List<String>? = null,
            allowed: ((String) -> Set<String>)? = null,
            barred: ((String) -> Set<String>)? = null,
            groupings: ((List<String>) -> List<ValueGrouping>)? = null,
            examples: ((String) -> List<String>)? = null,
            lackWeight: Double = 1.0,
            hintNames: Map<String, List<String>> = emptyMap(),
            hintAmong: (String) -> Collection<String> = { emptyList() },
            kept: List<String>? = null,
            cast: SaidLines? = null,
            siteGives: Boolean = false,
            laps: Laps = Laps(),
        ): FacetData =
            FacetRead(
                    store,
                    facet,
                    encoder,
                    read,
                    FacetAsked(
                        known,
                        modelled,
                        valueKeys,
                        allowed,
                        barred,
                        groupings,
                        examples,
                        lackWeight,
                        hintNames,
                        hintAmong,
                        kept,
                        cast,
                        siteGives,
                    ),
                    laps,
                )
                .data()
    }
}

/**
 * What a facet's task is read with besides the works ([FacetData.load]): the dictionary's word on the values — those
 * known, those each work may have and may not, their groups, names and rivals — the site's examples, the model's values
 * of the facets before, and the values of the scoring kept.
 */
internal class FacetAsked(
    val known: Map<String, String>,
    val modelled: WorkChances,
    val valueKeys: List<String>?,
    val allowed: ((String) -> Set<String>)?,
    val barred: ((String) -> Set<String>)?,
    val groupings: ((List<String>) -> List<ValueGrouping>)?,
    val examples: ((String) -> List<String>)?,
    val lackWeight: Double,
    val hintNames: Map<String, List<String>>,
    val hintAmong: (String) -> Collection<String>,
    val kept: List<String>?,
    /** The site's lines of characters and pairings read for the facet; null of a facet that reads none. */
    val cast: SaidLines?,
    /** Whether the site gives values of the facet, told of every work, as the model was fitted. */
    val siteGives: Boolean,
)
