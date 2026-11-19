package io.github.vlsergey.recommend4me.task

import io.github.vlsergey.recommend4me.api.model.TaskPriority

/**
 * THE PRIORITY A TASK PLANNED INHERITS (owner, 2026-11-19: "the tasks created may inherit this priority, through
 * ScopedValues, unless told otherwise"): a task planned or run by a task running — or by a listener of what it told
 * once its turn was given back — is of that task's priority; one planned by a request of the browser, of the user's
 * change ([TaskPriority.USER]: ahead of the background, behind what the user waits for); any other of the background.
 */
internal object InheritedPriority {
    private val running: ScopedValue<TaskPriority> = ScopedValue.newInstance()

    /** The priority of a task planned now: the [named] one, else the inherited. */
    fun of(named: TaskPriority?): TaskPriority =
        named
            ?: when {
                running.isBound -> running.get()
                Requests.serving() -> TaskPriority.USER
                else -> TaskPriority.BACKGROUND
            }

    /** [body] run as of a task of the [priority]: what it plans inherits it. */
    fun <T> under(priority: TaskPriority, body: () -> T): T =
        ScopedValue.where(running, priority).call(ScopedValue.CallableOp<T, RuntimeException> { body() })
}
