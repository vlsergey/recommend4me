package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.task.TaskCursor

/**
 * Every text of the [typeId] to be encoded encoded now, on the caller's thread, turn after turn: what the tests read.
 */
fun TextVectors.encodeNow(typeId: String) =
    WorkScope.entries.forEach { scope ->
        var cursor: TaskCursor? = TaskCursor.FIRST
        while (cursor != null) cursor = turn(typeId, scope, cursor)
    }
