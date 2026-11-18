package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.database.DatabaseRefused
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(FacetScoring::class.java)

/**
 * A facet's model as a pass scores its works by it: the [suggester]'s [model] kept, of the value [side], and how its
 * chances are made true to the user's answers ([calibration]; null: the suggester's own).
 */
internal class PassModel(
    val suggester: FacetSuggester,
    val model: ByteArray,
    val side: ValueSide,
    val calibration: Calibration?,
)

/**
 * THE CUTOFF OF A CALIBRATED FACET'S MODEL MADE ANEW OF EVERY WORK'S CHANCES, A PORTION AT A TIME
 * ([FacetSuggester.scoring]), the database refused: every portion's chances put among those before the facet
 * ([FacetChain.before]), the chances of the values open to the model counted ([OpenChances]) and its cutoff kept anew
 * for the next pass — a part of the model. The values the model gives the works are not written here: they are filled
 * into the index of the list after the models are fitted, last of the queue (user, 2026-11-18). A facet of no
 * calibration is not walked.
 */
internal class FacetScoring {
    /** What a walk made, as its log tells it. */
    private class Told {
        var works = 0
        var given = 0L
        var wide = 0
        var most = 0
        var mostOf: String? = null
    }

    /** Every work of the [pass] scored by the [model], its cutoff made anew; nothing of a model of no calibration. */
    fun walk(pass: FacetPass, model: PassModel?) {
        val facet = pass.facet
        val store = pass.store
        val calibration = model?.calibration ?: return
        val started = System.currentTimeMillis()
        val open = OpenChances()
        val told = Told()
        pass.forEach(inputs = true) { p ->
            val data = pass.data(p, model.side)
            DatabaseRefused.during(FacetChain.computation(store, facet)) {
                model.suggester.scoring(model.model, data.task)?.use {
                    ShownChances.keep(p.made, facet, data, it, calibration = calibration)
                }
            }
            count(open, data, p.made, facet.key)
            tell(told, p, facet.key)
        }
        keepCutoff(pass, open, calibration)
        log.info(
            "{}: chances of {} of {} works made for its cutoff in {} ms; {} values given in all, {} works given more " +
                "than {}, the most {} to {}",
            store.id,
            facet.key,
            told.works,
            System.currentTimeMillis() - started,
            told.given,
            told.wide,
            MANY_GIVEN,
            told.most,
            told.mostOf,
        )
    }

    /**
     * How many values the model gives the works of the portion [p] of the [facet]: a model that gives one work dozens
     * tells of itself.
     */
    private fun tell(told: Told, p: FacetPass.Portion, facet: String) {
        p.read.ids.forEach { id ->
            val given = ModelValues.given(p.made[id]?.get(facet).orEmpty()).size
            told.works++
            told.given += given
            if (given > MANY_GIVEN) told.wide++
            if (given > told.most) {
                told.most = given
                told.mostOf = id
            }
        }
    }

    /**
     * The chances of the values of the works of [data] open to the model — neither the site gives a work, nor a draft,
     * nor the user answered on: the model's to give or not — counted into [open].
     */
    private fun count(open: OpenChances, data: FacetData, made: MadeChances, facet: String) {
        data.ids.forEachIndexed { i, id ->
            // The values the user answered on are the user's, not the model's to give
            val said = (data.saidYes.getOrNull(i) ?: IntArray(0)) + (data.saidNo.getOrNull(i) ?: IntArray(0))
            val answered = said.mapTo(HashSet()) { data.values[it] }
            made[id]?.get(facet)?.values?.forEach {
                if (!it.had && !it.drafted && it.key !in answered) open.add(it.chance)
            }
        }
    }

    /**
     * The facet's cutoff worked out anew of the chances [open] of every work of the pass ([OpenChances.balanced]), kept
     * for the next pass when it moved.
     */
    private fun keepCutoff(pass: FacetPass, open: OpenChances, calibration: Calibration) {
        val threshold = open.balanced()
        if (threshold == calibration.threshold) return
        pass.store.suggestions.keepCalibration(pass.facet.key, calibration.withThreshold(threshold))
        log.info(
            "{}: the cutoff of {} of {} values open moved from {} to {}",
            pass.store.id,
            pass.facet.key,
            open.size,
            "%.3f".format(calibration.threshold),
            "%.3f".format(threshold),
        )
    }

    private companion object {
        /**
         * How many values given to one work the log of a pass counts the works above: a sign of a model gone wide, not
         * a rule.
         */
        const val MANY_GIVEN = 10
    }
}
