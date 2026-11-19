package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.correction.FacetAnswered
import io.github.vlsergey.recommend4me.interest.InterestChanged
import io.github.vlsergey.recommend4me.manner.MannersChanged
import io.github.vlsergey.recommend4me.mark.MarksChanged
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.setvector.SetVectorsChanged
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagsChanged
import io.github.vlsergey.recommend4me.worklist.RatedValuesFilled
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(TrainingTriggers::class.java)

/**
 * WHAT MAKES THE MODELS LEARN AGAIN: the changes the application tells of — the user's grades, marks, tags and answers,
 * the background's chances and set vectors — each a training planned ([Recommendations.scheduleRetrain]); a type with
 * grades and no model readable, on start.
 */
@Component
class TrainingTriggers(
    private val recommendations: Recommendations,
    private val heldOuts: HeldOutModels,
    private val kept: KeptModels,
    private val stores: Stores,
    private val settings: Settings,
) {

    @EventListener
    fun gradesChanged(event: GradesChanged) {
        settings.set(Recommendations.gradesChangedAt(event.type), System.currentTimeMillis().toString())
        heldOuts.forget(event.source, event.itemId)
        // The user waits to see a grade learnt (user, 2026-11-07)
        recommendations.scheduleRetrain(event.type, "grade", asked = true)
    }

    @EventListener fun marksChanged(event: MarksChanged) = recommendations.scheduleRetrain(event.type, "mark")

    /** The user's mark of interest, as a grade: the user waits to see it learnt. */
    @EventListener
    fun interestChanged(event: InterestChanged) = recommendations.scheduleRetrain(event.type, "interest", asked = true)

    /** Tags merged, or put above and under others: the works' tags, features of the works, changed. */
    @EventListener fun tagsChanged(event: TagsChanged) = recommendations.scheduleRetrain(event.type, "tags")

    /** The user said what a work has or has not: its features, the user's word over the model's. */
    @EventListener
    fun answered(event: FacetAnswered) {
        heldOuts.forget(event.source, event.itemId)
        recommendations.scheduleRetrain(event.type, "answer")
    }

    /**
     * The values the models give the rated works filled into the index of the list after the models of the fields were
     * made (the second stage of four, docs/computations.md): the models of the grades made of them next, the third.
     */
    @EventListener
    fun ratedValuesFilled(event: RatedValuesFilled) =
        recommendations.scheduleRetrain(event.type, "chances", byUser = false)

    @EventListener
    fun setVectorsChanged(event: SetVectorsChanged) {
        log.info("{}: {} items have new set vectors: retraining", event.type, event.items)
        recommendations.scheduleRetrain(event.type, "setVectors", byUser = false)
    }

    /** The manners of the works' texts were made again: inputs of the works. */
    @EventListener
    fun mannersChanged(event: MannersChanged) = recommendations.scheduleRetrain(event.type, "manner", byUser = false)

    /** A model that could not be read, or none, is trained on start where there are grades. */
    @EventListener(ApplicationReadyEvent::class)
    fun trainIfMissing() {
        stores.types.forEach { t ->
            if (kept.load(t) == null && t.sources.any { it.ratings.count() > 0 }) {
                recommendations.scheduleRetrain(t.id, "noModel", asked = true)
            }
        }
    }
}
