package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.setvector.WorkSetVectorFillEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.textvector.WorkTextVectorFillEvent
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.worklist.ListIndex
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.NextWorks
import io.github.vlsergey.recommend4me.worklist.StageSweeps
import io.github.vlsergey.recommend4me.worklist.TakenWork
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkFieldFillEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(WorkPredictions::class.java)

/**
 * THE EXPECTED GRADE AND INTEREST OF THE WORKS — THE STAGE [WorkStage.PREDICTED] ([StageSweeps]; owner, 2026-11-19:
 * "its work (writing the computed fields) leads to planning the task 'set the grade and the interest'";
 * docs/computations.md, 9 and 10): of the works whose data, answers, grades, items, fields, vectors, pictures or sets
 * changed past its mark — the fields done of them first —, of every work by a new model of the grades ([begin]); a task
 * of the marked and rated works, and one of the others after every other task, a [PORTION] of them a turn ([turn]) —
 * the highest expected first, from where the last turn left —: each scored by the models kept of the fields the index
 * of the list keeps ([EveryWork.ofKeys]), their predictions written where they changed ([Scoring.score]), every one
 * marked done. Of no model of the grades, none scored.
 *
 * THE AVERAGE WORK of a new model counted along its pass ([AverageCount]) — every work's features and vectors as they
 * are scored — kept at the end of the pass of the other works, the last, for the next training
 * ([AverageWorkRepository]); where it stands kept in the model database ([ScoreSweepRepository]), never in the memory.
 * A work done again in the pass, changed meanwhile, is counted again.
 */
@Component
class WorkPredictions(
    private val stores: Stores,
    private val plugins: Plugins,
    private val everyWork: EveryWork,
    private val scoring: Scoring,
    private val kept: KeptModels,
    private val likenesses: Likenesses,
    private val tasks: Tasks,
    private val works: Works,
    private val lists: ListIndexes,
    private val sweeps: StageSweeps,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start, from where it
    // was to go on
    init {
        tasks.resumption.resumes(KIND) { left, reason ->
            val type = left.work.type ?: return@resumes
            val scope = left.work.works ?: return@resumes
            plan(type, scope, reason, left.cursor ?: TaskCursor.FIRST)
        }
    }

    /**
     * A new model of the grades of the [type]: every work's expectation made anew — done before now, it is to be done
     * again —, the marked and rated and the others from the first, for the [reason]; the average work counted along
     * anew.
     */
    fun begin(type: TypeStore, reason: String) {
        sweeps.reset(STAGE, type.id, type.sources.map { it.id })
        type.sweep.save(SweepState(trainedAt(type), AverageWork.basis(plugins, type), Instant.now(), 0, 0, null))
        WorkScope.entries.forEach { plan(type.id, it, reason, TaskCursor.FIRST) }
    }

    /** The documents of works written anew, of what the expectations are made of changed: their expectations made. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) {
        if (event.anew || STAGE.follows(event.data))
            WorkScope.entries.forEach {
                plan(event.type, it, if (event.anew) "listFormat" else "items", TaskCursor.FIRST)
            }
    }

    /** The fields the expectations wait for done: the expectations of the works they got past made. */
    @EventListener
    fun fieldsFilled(event: WorkFieldFillEvent) = plan(event.type, event.scope, "fields", TaskCursor.FIRST)

    /** The vectors of the texts the expectations read done: of the works whose vectors changed, made. */
    @EventListener
    fun vectorsFilled(event: WorkTextVectorFillEvent) = plan(event.type, event.scope, "texts", TaskCursor.FIRST)

    /** The sets the expectations read done: of the works whose sets changed, made. */
    @EventListener
    fun setsFilled(event: WorkSetVectorFillEvent) = plan(event.type, event.scope, "setVectors", TaskCursor.FIRST)

    /** The expectations of the works of the [scope] of the [typeId] planned, for the [reason], from the [cursor] on. */
    fun plan(typeId: String, scope: WorkScope, reason: String, cursor: TaskCursor) =
        tasks.plan(
            Task(KIND, type = typeId, works = scope),
            null,
            reason,
            priority = NextWorks.priority(scope),
            cursor = cursor,
        ) {
            turn(typeId, scope, tasks.running()?.cursor ?: TaskCursor.FIRST)?.let { tasks.again("sweep", cursor = it) }
        }

    /**
     * Every expectation of the [typeId] to be made made now, on the caller's thread, turn after turn: for the tests.
     */
    fun predictNow(typeId: String) =
        WorkScope.entries.forEach { scope ->
            var cursor: TaskCursor? = TaskCursor.FIRST
            while (cursor != null) cursor = turn(typeId, scope, cursor)
        }

    /**
     * ONE TURN: the next [portion] works of the [scope] of the [typeId] whose expectation is to be made, from the
     * [cursor] on, scored; where the next turn goes on from — none when none is left.
     */
    internal fun turn(typeId: String, scope: WorkScope, cursor: TaskCursor, portion: Int = PORTION): TaskCursor? {
        val type = stores.contentType(typeId)
        val index = lists.of(typeId)
        // Of no model of the grades, no expectation to make
        val model = kept.load(type) ?: return null
        val items = sweeps.next(STAGE, typeId, type.sources.map { it.id }, scope, cursor, portion)
        if (items.isNotEmpty()) scored(type, index, model, scope, items)
        val next = items.lastOrNull()?.cursor()?.takeIf { items.size == portion }
        if (next == null) {
            sweeps.complete(STAGE, typeId, scope)
            if (scope == WorkScope.OTHER) finish(type)
        }
        return next
    }

    /**
     * The [items] of the [scope] of the [type] scored by the [model], their predictions written where they changed, the
     * average work of the pass under way counted along; told.
     */
    private fun scored(
        type: TypeStore,
        index: ListIndex,
        model: TrainedModel,
        scope: WorkScope,
        items: List<TakenWork>,
    ) {
        val started = System.currentTimeMillis()
        val basis = AverageWork.basis(plugins, type)
        val state =
            type.sweep.load()?.takeIf { it.model.toEpochMilli() == model.meta.trainedAtMillis && it.basis == basis }
        val count = state?.let { counted(type, it) }
        val interest = kept.loadInterest(type)
        val likeness = likenesses.asMade(type, ratingsOf(type).map { it.key })
        val clusters = works.of(type)
        val done = items.mapTo(HashSet()) { it.key }
        var scored = 0
        var changed = 0
        val named = items.map { clusters.representative(it.key) }.distinct()
        everyWork.ofKeys(type, likeness, named, count, kept = true) { read ->
            val made = scoring.score(read.catalogue, model, interest)
            count?.addAll(read)
            scored += made.items
            changed += made.changed
            read.catalogue.items.forEach { done += clusters.members(it.key) }
        }
        sweeps.done(STAGE, type.id, scope, done)
        index.commit()
        if (state != null && count != null)
            type.sweep.save(
                SweepState(
                    state.model,
                    basis,
                    state.began,
                    state.scored + scored,
                    state.changed + changed,
                    count.pack(),
                )
            )
        log.info(
            "{}: the expectations of {} {} works made in {} ms, {} of {} predictions changed",
            type.id,
            items.size,
            scope.value,
            System.currentTimeMillis() - started,
            changed,
            scored,
        )
    }

    /** The sums of the average work counted so far by the pass [state] of the [type]. */
    private fun counted(type: TypeStore, state: SweepState) =
        AverageCount(plugins.textEncoder(), plugins.imageEncoder(), type.embeddings.ids()).also { c ->
            state.counted?.let(c::resume)
        }

    /**
     * The other works all expected, the last: of a pass by a new model, the average work counted kept for the next
     * training, the pass forgotten. Nothing of no pass under way.
     */
    private fun finish(type: TypeStore) {
        val state = type.sweep.load() ?: return
        if (state.counted != null) type.averageWork.save(counted(type, state).made(), state.basis)
        type.sweep.forget()
        log.info(
            "{}: every work expected in {} s since the pass began: {} predictions made, {} of them changed",
            type.id,
            Duration.between(state.began, Instant.now()).toSeconds(),
            state.scored,
            state.changed,
        )
    }

    companion object {
        /** The task of the queue making the works' expectations. */
        val KIND = TaskKind.PREDICT_WORKS

        /** The works a turn takes (owner, 2026-11-19: "each of the tasks does at most N operations, say, 1000"). */
        const val PORTION = 1000

        /** The stage of the tasks over the works it is. */
        private val STAGE = WorkStage.PREDICTED

        /** When the model of the grades of the [type] kept was trained; the beginning of time of none. */
        private fun trainedAt(type: TypeStore): Instant = type.models.trainedAt() ?: Instant.EPOCH
    }
}
