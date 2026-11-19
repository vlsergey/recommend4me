package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.api.model.TaskStep
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.work.Works
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(Recommendations::class.java)

/** The content type [id]; an error when there is none. */
internal fun Stores.contentType(id: String): TypeStore = type(id) ?: error("No such content type: $id")

/**
 * Trains the model of every content type on the user's grades; the score of 0..10 of every item is kept by the pass of
 * the expectations that follows every training ([WorkPredictions]), tasks of the queue of their own. Training is cheap
 * enough — a few thousand ratings of a few thousand numbers, the graded works alone read, the average work as the last
 * pass counted it — to be redone from scratch after the grades change and after every sync rather than updated
 * incrementally ([TrainingTriggers]).
 *
 * NOTHING IS KEPT IN MEMORY between operations: the model is read from its database when it is needed ([KeptModels]),
 * the graded works loaded at the start of a training and dropped at its end.
 */
@Service
class Recommendations(
    private val stores: Stores,
    private val plugins: Plugins,
    private val everyWork: EveryWork,
    private val works: Works,
    private val settings: Settings,
    private val tasks: Tasks,
    private val kept: KeptModels,
    private val fitting: ModelFitting,
    private val predictions: WorkPredictions,
    private val scoring: Scoring,
) {
    private val trainingNow = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, PendingTraining>()

    /** How long the last training of every type took, ms: how long a stream of background changes is let to settle. */
    private val lastTraining = ConcurrentHashMap<String, Long>()

    /**
     * Training is running now. One planned after a change, or waiting its turn in the queue of the computations, is
     * not: the queue tells it ([Tasks]), and the model shows no spinner meanwhile.
     */
    fun training(type: String): Boolean = type in trainingNow

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(TaskKind.FIT_GRADE_MODELS) { left, reason ->
            left.work.type?.let {
                scheduleRetrain(
                    it,
                    reason,
                    byUser = left.priority <= TaskPriority.USER,
                    asked = left.priority == TaskPriority.ASKED,
                )
            }
        }
    }

    /**
     * Retrains after a change. A change of the user's ([byUser]: a grade, a mark, a tag, an answer) is learnt shortly
     * after the last of a quick series of them. A change the background work makes — the suggester's chances of every
     * facet of every source, the reranker's likeness chunk after chunk, the pictures — comes in long streams: learnt
     * after a minute of quiet, or twice the last training, never later than [MAX_WAIT_MS] after the first of the
     * stream; a training of the user's change is never put off by it. The [reason] — a key the interface words — is
     * told in the queue of the works ([Tasks.plan]) till the training begins.
     *
     * [asked]: the user waits for this training — a grade, the mark of interest, the button (user, 2026-11-07) — and it
     * goes ahead of the background's works. A change of the user's the user does not wait to see learnt — an answer, a
     * tag, a mark on a picture — is learnt in its turn among the background's (user, 2026-11-15): the trainings after
     * every answer, one after another while the user marks, kept the background waiting for 14 minutes.
     */
    fun scheduleRetrain(
        type: String,
        reason: String,
        byUser: Boolean = true,
        asked: Boolean = false,
        quietMs: Long = USER_QUIET_MS,
    ) {
        val now = System.currentTimeMillis()
        val work = trainingWork(type)
        pending.compute(type) { _, old ->
            val waiting = old?.takeIf { tasks.due(work) }
            val due =
                when {
                    !byUser && waiting?.byUser == true -> waiting.due
                    byUser -> now + quietMs
                    else ->
                        minOf(
                            now + maxOf(MACHINE_QUIET_MS, 2 * (lastTraining[type] ?: 0L)),
                            (waiting?.firstAt ?: now) + MAX_WAIT_MS,
                        )
                }
            tasks.plan(work, Instant.ofEpochMilli(due), reason, priority = priorityOf(asked, byUser)) {
                retrain(type, asked)
            }
            if (!byUser && waiting?.byUser == true) waiting else PendingTraining(waiting?.firstAt ?: now, due, byUser)
        }
    }

    /**
     * The training the user asked for — by the button, or by choosing a scorer — planned to begin at once, after the
     * works before it; the request that asked does not wait for it ([Requests]).
     */
    fun trainSoon(type: String) = scheduleRetrain(type, "asked", asked = true, quietMs = 0)

    /**
     * The priority of a training the user waits for ([asked]), of a change of the user's not waited for ([byUser]),
     * else of the background.
     */
    private fun priorityOf(asked: Boolean, byUser: Boolean): TaskPriority? =
        when {
            asked -> TaskPriority.ASKED
            byUser -> TaskPriority.USER
            else -> null
        }

    /** A training scheduled: since the first change it waits on, when it is due, and whether it is of the user's. */
    private class PendingTraining(val firstAt: Long, val due: Long, val byUser: Boolean)

    /**
     * The models of the type trained now, in the queue — [asked]: ahead of the background — of the graded works alone
     * and the average work kept ([EveryWork.training]); every work scored by them after it, by the pass planned
     * ([WorkPredictions.begin]) and not waited for.
     */
    fun retrain(typeId: String, asked: Boolean = false): ModelSummary =
        tasks.run(trainingWork(typeId), priority = TaskPriority.ASKED.takeIf { asked }) {
            val type = stores.contentType(typeId)
            trainingNow += typeId
            try {
                val started = System.currentTimeMillis()
                // Its steps told to the queue, each begun at its share of a training as long trainings went
                val catalogue = everyWork.training(type)
                val loaded = System.currentTimeMillis()
                tasks.progress(GRADES_FROM, TaskStep.FIT_GRADES)
                val model = fitting.grades(catalogue)
                // Of no use without the scores it multiplies
                tasks.progress(INTEREST_FROM, TaskStep.FIT_INTEREST)
                val interest = if (model != null) fitting.interest(catalogue) else null
                lastTraining[typeId] = System.currentTimeMillis() - started
                log.info(
                    "{}: retrained in {} ms: read {}, trained {}",
                    typeId,
                    System.currentTimeMillis() - started,
                    loaded - started,
                    System.currentTimeMillis() - loaded,
                )
                // The rated works scored at once, of the works the training read; every work by the pass of the
                // expectations
                tasks.progress(SCORING_FROM, TaskStep.SCORE_RATED)
                if (model != null) scoring.score(catalogue, model, interest)
                predictions.begin(type, "trained")
            } finally {
                trainingNow -= typeId
            }
            summary(typeId)
        }

    /** Where every grade stands on 0..10 by the current model of the type; null without one. */
    fun gradePlaces(typeId: String): Map<Int, Double>? = kept.load(stores.contentType(typeId))?.scale?.grades()

    fun summary(typeId: String): ModelSummary {
        val type = stores.contentType(typeId)
        val model = kept.load(type)
        val changedAt = settings.get(gradesChangedAt(typeId))?.toLongOrNull() ?: 0L
        val clusters = works.of(type)
        // A grade of a work once, whichever of its items see it
        val counts =
            gradeCounts(
                type.sources
                    .flatMap { s ->
                        s.ratings
                            .all()
                            .filter { clusters.representative(ItemKey(s.id, it.itemId)) == ItemKey(s.id, it.itemId) }
                            .map { it.grade }
                    }
                    .toIntArray()
            )
        return ModelSummary(
            meta = model?.meta,
            training = training(typeId),
            stale = if (model == null) counts.sum() > 0 else changedAt > model.meta.trainedAtMillis,
            textEncoderReady = plugins.textEncoder() != null,
            imageEncoderReady = plugins.imageEncoder() != null,
            scorer = plugins.scorer(typeId)?.id,
            ratingCounts = counts,
        )
    }

    /** The model of interest as kept: what the page of the models shows of it; null while there is none. */
    fun interestMeta(typeId: String): InterestMeta? = kept.loadInterest(stores.contentType(typeId))?.meta

    /**
     * Makes [scorerId] the scorer of the type's model of interest and plans the training of the models; false for no
     * such scorer.
     */
    fun chooseInterestScorer(typeId: String, scorerId: String): Boolean {
        if (plugins.scorers.none { it.id == scorerId }) return false
        settings.set("scorer.$typeId.interest", scorerId)
        trainSoon(typeId)
        return true
    }

    /** Makes [scorerId] the scorer of the type and plans its training; false for no such scorer. */
    fun chooseScorer(typeId: String, scorerId: String): Boolean {
        if (plugins.scorers.none { it.id == scorerId }) return false
        settings.set("scorer.$typeId", scorerId)
        trainSoon(typeId)
        return true
    }

    companion object {
        /** The quiet after the user's last change before the model learns it. */
        private const val USER_QUIET_MS = 3_000L

        /** The least quiet after the background work's last change. */
        private const val MACHINE_QUIET_MS = 60_000L

        /** The longest a stream of background changes puts a training off. */
        private const val MAX_WAIT_MS = 10 * 60_000L

        /** The setting of when the user's grades of the type last changed: a model trained before is stale. */
        fun gradesChangedAt(typeId: String) = "model.$typeId.gradesChangedAt"

        /** The shares of a training its steps begin at: the reading of the graded works first, from nought. */
        private const val GRADES_FROM = 0.4
        private const val INTEREST_FROM = 0.7
        private const val SCORING_FROM = 0.85

        private fun trainingWork(typeId: String) = Task(TaskKind.FIT_GRADE_MODELS, type = typeId)
    }
}
