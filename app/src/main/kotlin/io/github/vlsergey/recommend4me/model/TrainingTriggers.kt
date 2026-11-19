package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.correction.WorkFieldChangeEvent
import io.github.vlsergey.recommend4me.interest.WorkInterestChangeEvent
import io.github.vlsergey.recommend4me.manner.WorkMannerChangeEvent
import io.github.vlsergey.recommend4me.mark.SiteItemMarkChangeEvent
import io.github.vlsergey.recommend4me.picture.SiteItemPictureAnalysisEvent
import io.github.vlsergey.recommend4me.rating.WorkGradeChangeEvent
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.setvector.SiteItemSetVectorChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.tag.TagChangeEvent
import io.github.vlsergey.recommend4me.worklist.RatedWorkValueFillEvent
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
    fun gradesChanged(event: WorkGradeChangeEvent) {
        settings.set(Recommendations.gradesChangedAt(event.type), System.currentTimeMillis().toString())
        heldOuts.forget(event.source, event.itemId)
        // The user waits to see a grade learnt (user, 2026-11-07)
        recommendations.scheduleRetrain(event.type, "grade", asked = true)
    }

    @EventListener
    fun marksChanged(event: SiteItemMarkChangeEvent) = recommendations.scheduleRetrain(event.type, "mark")

    /** The user's mark of interest, as a grade: the user waits to see it learnt. */
    @EventListener
    fun interestChanged(event: WorkInterestChangeEvent) =
        recommendations.scheduleRetrain(event.type, "interest", asked = true)

    /** Tags merged, or put above and under others: the works' tags, features of the works, changed. */
    @EventListener fun tagsChanged(event: TagChangeEvent) = recommendations.scheduleRetrain(event.type, "tags")

    /** The user said what a work has or has not: its features, the user's word over the model's. */
    @EventListener
    fun answered(event: WorkFieldChangeEvent) {
        heldOuts.forget(event.source, event.itemId)
        recommendations.scheduleRetrain(event.type, "answer")
    }

    /**
     * The values the models give the rated works filled into the index of the list after the models of the fields were
     * made (the second stage of four, docs/computations.md): the models of the grades made of them next, the third.
     */
    @EventListener
    fun ratedValuesFilled(event: RatedWorkValueFillEvent) =
        recommendations.scheduleRetrain(event.type, "chances", byUser = false)

    /** The works' pictures analysed: features of the works, learnt as they come. */
    @EventListener
    fun picturesAnalysed(event: SiteItemPictureAnalysisEvent) =
        recommendations.scheduleRetrain(stores.typeOf(event.source).id, "pictures", byUser = false)

    @EventListener
    fun setVectorsChanged(event: SiteItemSetVectorChangeEvent) {
        log.info("{}: {} items have new set vectors: retraining", event.type, event.items)
        recommendations.scheduleRetrain(event.type, "setVectors", byUser = false)
    }

    /** The manners of the works' texts were made again: inputs of the works. */
    @EventListener
    fun mannersChanged(event: WorkMannerChangeEvent) =
        recommendations.scheduleRetrain(event.type, "manner", byUser = false)

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
