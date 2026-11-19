package io.github.vlsergey.recommend4me.task

import io.github.vlsergey.recommend4me.api.model.TaskPriority

/**
 * THE PRIORITY A TASK PLANNED INHERITS (owner, 2026-11-19: "the tasks created may inherit this priority, through
 * ScopedValues, unless told otherwise"): a task planned or run by a task running — or by a listener of what it told
 * once its turn was given back — is of that task's priority; any other of the background, a request's too — the pages a
 * site's reading sends are requests as the user's answers are: a task of the user's change names its priority.
 */
internal object InheritedPriority {
    private val running: ScopedValue<TaskPriority> = ScopedValue.newInstance()

    /** The priority of a task planned now: the [named] one, else the inherited. */
    fun of(named: TaskPriority?): TaskPriority =
        named ?: if (running.isBound) running.get() else TaskPriority.BACKGROUND

    /** [body] run as of a task of the [priority]: what it plans inherits it. */
    fun <T> under(priority: TaskPriority, body: () -> T): T =
        ScopedValue.where(running, priority).call(ScopedValue.CallableOp<T, RuntimeException> { body() })
}
