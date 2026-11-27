package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.manner.WorkMannerFillEvent
import io.github.vlsergey.recommend4me.setvector.WorkSetVectorFillEvent
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.textvector.WorkTextVectorFillEvent
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkFieldFillEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * WHAT THE EXPECTATIONS OF THE WORKS ARE PLANNED BY ([WorkPredictions]): the documents of the list written anew of what
 * they read, and every stage they read done — the fields, the vectors of the texts, the sets, the manners —, each of
 * its scope: the expectations of the works those got past made.
 */
@Component
class PredictionTriggers(private val predictions: WorkPredictions) {

    /** The documents of works written anew, of what the expectations are made of changed: their expectations made. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) {
        if (WorkStage.PREDICTED in event.stages)
            WorkScope.entries.forEach { predictions.plan(event.type, it, "items", TaskCursor.FIRST) }
    }

    /** The fields the expectations wait for done: the expectations of the works they got past made. */
    @EventListener
    fun fieldsFilled(event: WorkFieldFillEvent) = predictions.plan(event.type, event.scope, "fields", TaskCursor.FIRST)

    /** The vectors of the texts the expectations read done: of the works whose vectors changed, made. */
    @EventListener
    fun vectorsFilled(event: WorkTextVectorFillEvent) =
        predictions.plan(event.type, event.scope, "texts", TaskCursor.FIRST)

    /** The sets the expectations read done: of the works whose sets changed, made. */
    @EventListener
    fun setsFilled(event: WorkSetVectorFillEvent) =
        predictions.plan(event.type, event.scope, "setVectors", TaskCursor.FIRST)

    /** The manners the expectations wait for done: the expectations of the works they got past made. */
    @EventListener
    fun mannersFilled(event: WorkMannerFillEvent) =
        predictions.plan(event.type, event.scope, "manner", TaskCursor.FIRST)
}
