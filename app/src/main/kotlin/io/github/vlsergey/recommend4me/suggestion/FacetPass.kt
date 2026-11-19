package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.SaidLines
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.database.DatabaseRefused
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.universe.CharactersRead

/**
 * ONE FACET OF A PASS OVER EVERY WORK OF A SOURCE, THE [k]-TH OF ITS CHAIN, A PORTION AT A TIME (user, 2026-11-17: no
 * pass holds more than [TaskPortions.MOST] works): every walk over the works reads every portion anew of the database
 * ([forEach]) — its works ([WorksRead]), the values the model gave the facets before this one as the pass kept them
 * ([FacetChain.before]), the dictionary's word on the values each work may have ([FacetVocabularies.vocabularyOf]), the
 * works' lines — and lets it go before the next. The walks of a facet: one of the works' values alone, of what the
 * value side is made of ([counted], [side]); as many as its suggester's fitting needs ([FacetPortions]); one of its
 * chances made and kept ([FacetScoring]).
 */
internal class FacetPass(
    val store: SourceStore,
    val facet: FacetDef,
    private val k: Int,
    private val encoder: TextEncoder,
    private val characters: CharactersRead,
    private val reading: FacetReading,
    /** Asked before every portion: throws once the pass is to be given up. */
    private val checkpoint: () -> Unit,
) {
    /** Whether the site gives values of the facet, told of every work of the source as the model was fitted. */
    private val siteGives = reading.chain.siteGives(store, facet)

    /**
     * A portion of the pass as the facet reads it: its works [read], the chances before the facet [made] — those the
     * facet's own are put among —, the dictionary's word on the values each work may have [vocabulary], the works'
     * [lines] of characters and pairings.
     */
    class Portion(
        val read: WorksRead,
        val made: MadeChances,
        val vocabulary: Vocabulary?,
        val lines: SaidLines?,
    )

    /**
     * Every portion of the works in turn, in the order of their ids; [inputs]: their vectors read and put on the card,
     * freed once [action] is done with the portion — false: their values alone, but of a facet after the first in the
     * chain, whose context the facets before it make of the works' inputs ([FacetChain.before]). Of the works [only],
     * when given: the works the model learns of, not every one.
     */
    fun forEach(inputs: Boolean, only: Collection<String>? = null, action: (Portion) -> Unit) {
        val portion = { ids: List<String> -> read(ids, inputs, action) }
        val passes = reading.passes
        if (only == null) passes.forEachPortion(store, portion) else passes.forEachPortion(only, portion)
    }

    /** The portion of the works [ids], read and handed to [action]. */
    private fun read(ids: List<String>, inputs: Boolean, action: (Portion) -> Unit) {
        checkpoint()
        val withInputs = inputs || k > 0
        val passes = reading.passes
        passes.worksRead(store, encoder, ids, characters, card = withInputs, inputs = withInputs).use { read ->
            val made = reading.chain.before(store, encoder, read, k)
            val vocabulary =
                reading.vocabularies.vocabularyOf(
                    store,
                    facet,
                    read.ids,
                    made,
                    characters,
                    read.answers,
                    light = true,
                    site = read.site,
                )
            action(Portion(read, made, vocabulary, reading.chain.linesOf(store, facet, vocabulary, read)))
        }
    }

    /** THE WALK OF THE WORKS' VALUES ALONE: what every work tells of the value side of the facet ([ValueCount]). */
    fun counted(): ValueCount {
        val count = ValueCount(facet.key)
        forEach(inputs = false) { p ->
            val told =
                DatabaseRefused.during(FacetChain.computation(store, facet)) {
                    FacetData.count(store, facet, encoder, p.read, p.made, p.vocabulary, p.lines)
                }
            count.add(p.read, told, p.vocabulary, p.lines?.asked?.reader?.id)
        }
        return count
    }

    /**
     * The value side of the facet ([ValueSide]) of what the walk over every work [counted], of the dictionary's value
     * side and of every answer of the source ([answers]).
     */
    fun side(counted: ValueCount, answers: List<FacetCorrection>): ValueSide {
        val vocabulary = reading.vocabularies.valueSide(store, facet, characters, counted.pairings)
        return ValueSide.of(store, facet, encoder, counted, vocabulary, answers, store.hierarchies()[facet.key])
    }

    /** The task of the portion [p] of the value [side], made with the database refused. */
    fun data(p: Portion, side: ValueSide): FacetData =
        DatabaseRefused.during(FacetChain.computation(store, facet)) {
            FacetData.load(
                store,
                facet,
                encoder,
                p.read,
                p.made,
                p.vocabulary,
                side = side,
                cast = p.lines,
                siteGives = siteGives,
            )
        }
}

/** What a pass over a facet reads its portions with ([FacetPass]). */
internal class FacetReading(
    val passes: WorkPasses,
    val chain: FacetChain,
    val vocabularies: FacetVocabularies,
)
