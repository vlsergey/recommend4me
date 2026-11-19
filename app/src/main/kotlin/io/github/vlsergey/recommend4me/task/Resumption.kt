package io.github.vlsergey.recommend4me.task

import io.github.vlsergey.recommend4me.api.model.TaskKind
import java.util.concurrent.ConcurrentHashMap
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * THE WORKS THE LAST RUN LEFT UNDONE, PLANNED AGAIN AT THE START — IN ONE PLACE (owner, 2026-11-19: "every owner of a
 * work doing it in its own listener of the start — that is awful: it must be in one place"): the owner of a kind of
 * work tells, once, as it is made, how a work of it the last run left is planned again ([resumes]); the queue plans
 * every one the last run left at its start ([resumeLeft]), of every reason it was planned for. A technical part of the
 * restart, not of what leads to what: the document of the flow (docs/events.md) does not show it.
 */
class Resumption internal constructor(private val records: TaskRecords) {
    private val owners = ConcurrentHashMap<TaskKind, (LeftTask, String) -> Unit>()

    /** How a work of the kind [what] the last run left is planned again, for each of its reasons: told by its owner. */
    fun resumes(what: TaskKind, plan: (left: LeftTask, reason: String) -> Unit) {
        owners[what] = plan
    }

    /** Every work the last run left undone planned again by its owner; of a kind no owner tells of, none. */
    fun resumeLeft() = owners.forEach { (what, plan) ->
        records.left(what).forEach { l -> l.reasons.keys.forEach { plan(l, it) } }
    }
}

/** The start of the application: the works the last run left planned again, before the rows nobody took are dropped. */
@Component
internal class LeftTasks(private val tasks: Tasks) {
    @Order(Ordered.LOWEST_PRECEDENCE - 1)
    @EventListener(ApplicationReadyEvent::class)
    fun resume() = tasks.resumption.resumeLeft()
}
