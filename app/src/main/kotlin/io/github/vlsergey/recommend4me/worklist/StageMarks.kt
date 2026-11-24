package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.api.model.WorkScope

/**
 * THE MARKS OF THE STAGES AND THEIR SWEEPS UNDER WAY, as the commits of an index of the list keep them ([StageTimes]):
 * of every stage, scope and source the date up to which the stage did every change it reads ([mark]); of a sweep under
 * way, when it began ([began]) and of every source the horizon it does the changes up to ([horizon]).
 */
internal object StageMarks {

    /** Up to when the documents of the [source] in the [index] hold every change of the databases ([ListCursors]). */
    fun written(index: ListIndex, source: String): Long = ListCursors(index).held(source)

    /**
     * Up to when the [stage] did every change of the [source] ([StageSweeps]); nought of none kept — a stage or a
     * source new, none of its works done yet — and of a stage to be done of every work.
     */
    fun mark(index: ListIndex, stage: WorkStage, scope: WorkScope, source: String): Long =
        index.stages.since(markKey(stage, scope, source)) ?: 0L

    /** When the sweep of the [stage] of the [scope] under way began; none of none. */
    fun began(index: ListIndex, stage: WorkStage, scope: WorkScope): Long? = index.stages.since(sweepKey(stage, scope))

    /** Up to when the sweep of the [stage] of the [scope] under way does the changes of the [source]; none of none. */
    fun horizon(index: ListIndex, stage: WorkStage, scope: WorkScope, source: String): Long? =
        index.stages.since(horizonKey(stage, scope, source))

    /** Of the sweep of the [stage] of the [scope] under way, the horizon of every source of it. */
    fun horizons(index: ListIndex, stage: WorkStage, scope: WorkScope): Map<String, Long> {
        val prefix = horizonKey(stage, scope, "")
        return index.stages
            .keys { it.startsWith(prefix) }
            .mapNotNull { key -> index.stages.since(key)?.let { key.removePrefix(prefix) to it } }
            .toMap()
    }

    /** Of the sweep of the [stage] of the [scope] begun, the [horizon] of the [source] kept with it. */
    fun begin(index: ListIndex, stage: WorkStage, scope: WorkScope, source: String, horizon: Long) =
        index.stages.changed(horizonKey(stage, scope, source), horizon)

    /** The sweep of the [stage] of the [scope] under way forgotten: its beginning and its horizons. */
    fun forgetSweep(index: ListIndex, stage: WorkStage, scope: WorkScope) {
        val prefix = horizonKey(stage, scope, "")
        index.stages.forget { it == sweepKey(stage, scope) || it.startsWith(prefix) }
    }

    fun markKey(stage: WorkStage, scope: WorkScope, source: String) = "mark:${stage.name}:${scope.value}:$source"

    fun sweepKey(stage: WorkStage, scope: WorkScope) = "sweep:${stage.name}:${scope.value}"

    private fun horizonKey(stage: WorkStage, scope: WorkScope, source: String) =
        "horizon:${stage.name}:${scope.value}:$source"
}
