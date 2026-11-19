package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.worklist.ListIndex
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.NextWorks
import io.github.vlsergey.recommend4me.worklist.TakenWork
import io.github.vlsergey.recommend4me.worklist.WorkFieldFillEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(WorkPredictions::class.java)

/**
 * The expected grade and interest made of works of the [type], from where of each of the works ([from]): their
 * documents of the search written next ([WorkStage.TEXTS]).
 */
data class WorkPredictionFillEvent(val type: String, val from: Map<WorkScope, TaskCursor>)

/**
 * THE EXPECTED GRADE AND INTEREST OF THE WORKS — THE SECOND OF THE TASKS OVER THE WORKS (owner, 2026-11-19: "its work
 * (writing the computed fields) leads to planning the task 'set the grade and the interest'… and so on with the third
 * task"; docs/computations.md, 9 and 10): of the works whose fields were written ([WorkFieldFillEvent]) from the first
 * of them, of every work by a new model of the grades ([begin]); a task of the marked and rated works, and one of the
 * others after every other task, a [PORTION] of them a turn ([turn]) — the highest expected first, from where the last
 * turn left —: each scored by the models kept of the fields the index of the list keeps ([EveryWork.ofKeys]), their
 * predictions written where they changed ([Scoring.score]), every one marked done ([WorkStage.PREDICTED]); told
 * ([WorkPredictionFillEvent]): their documents of the search written next. Of no model of the grades, none scored: the
 * next task told alike.
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
    private val events: ApplicationEventPublisher,
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
        val index = lists.of(type.id)
        index.stages.changed(SINCE, System.currentTimeMillis())
        index.commit()
        type.sweep.save(SweepState(trainedAt(type), AverageWork.basis(plugins, type), Instant.now(), 0, 0, null))
        WorkScope.entries.forEach { plan(type.id, it, reason, TaskCursor.FIRST) }
    }

    /** The fields of works written: their expectations made from the first of them on. */
    @EventListener
    fun fieldsFilled(event: WorkFieldFillEvent) =
        event.from.forEach { (scope, from) -> plan(event.type, scope, "fields", from) }

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
        val model = kept.load(type)
        val items = model?.let {
            val since = maxOf(it.meta.trainedAtMillis, index.stages.since(SINCE) ?: 0L)
            NextWorks.next(index, scope, NextWorks.before(WorkStage.PREDICTED, since), cursor, portion)
        }
        when {
            // No expectation to make: the next task goes on from where this one was to
            items == null -> events.publishEvent(WorkPredictionFillEvent(typeId, mapOf(scope to cursor)))
            items.isNotEmpty() -> scored(type, index, model, scope, items)
        }
        val next = items?.lastOrNull()?.cursor()?.takeIf { items.size == portion }
        if (next == null && items != null && scope == WorkScope.OTHER) finish(type)
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
        // The highest the portion's works stand, before and as scored now: the next task goes on from it, whichever
        // the index of the list holds when it begins — told of the predictions of before, a work scored higher than
        // it stood was above the cursor, and its document of the search never written
        var highest = items.first().grade
        var changed = 0
        val named = items.map { clusters.representative(it.key) }.distinct()
        everyWork.ofKeys(type, likeness, named, count, kept = true) { read ->
            val made = scoring.score(read.catalogue, model, interest)
            count?.addAll(read)
            scored += made.items
            made.highest?.let { highest = maxOf(highest, it) }
            changed += made.changed
            read.catalogue.items.forEach { done += clusters.members(it.key) }
        }
        WorkStage.PREDICTED.done(index, done, started)
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
        events.publishEvent(WorkPredictionFillEvent(type.id, mapOf(scope to TaskCursor(highest))))
    }

    /** The sums of the average work counted so far by the pass [state] of the [type]. */
    private fun counted(type: TypeStore, state: SweepState) =
        AverageCount(plugins.textEncoder(), plugins.imageEncoder(), type.embeddings.ids()).also { c ->
            state.counted?.let(c::resume)
        }

    /**
     * The other works all expected, the last: of a pass by a new model, the average work counted kept for the next
     * training, the predictions of the items gone removed, the pass forgotten. Nothing of no pass under way.
     */
    private fun finish(type: TypeStore) {
        val state = type.sweep.load() ?: return
        val gone = scoring.forgetGone(type)
        if (state.counted != null) type.averageWork.save(counted(type, state).made(), state.basis)
        type.sweep.forget()
        log.info(
            "{}: every work expected in {} s since the pass began: {} predictions made, {} of them changed, " +
                "{} of items gone removed",
            type.id,
            Duration.between(state.began, Instant.now()).toSeconds(),
            state.scored,
            state.changed,
            gone,
        )
    }

    companion object {
        /** The task of the queue making the works' expectations. */
        val KIND = TaskKind.PREDICT_WORKS

        /** The works a turn takes (owner, 2026-11-19: "each of the tasks does at most N operations, say, 1000"). */
        const val PORTION = 1000

        /** What the index of the list tells of since when every expectation is to be made anew ([begin]). */
        const val SINCE = "predicted"

        /** When the model of the grades of the [type] kept was trained; the beginning of time of none. */
        private fun trainedAt(type: TypeStore): Instant = type.models.trainedAt() ?: Instant.EPOCH
    }
}
