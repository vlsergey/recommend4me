package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(Recommendations::class.java)

/** The content type [id]; an error when there is none. */
internal fun Stores.contentType(id: String): TypeStore = type(id) ?: error("No such content type: $id")

/**
 * Trains the model of every content type on the user's grades and keeps a score of 0..10 for every item. Training is
 * cheap enough — a few thousand ratings of a few thousand numbers — to be redone from scratch after the grades change
 * and after every sync rather than updated incrementally ([TrainingTriggers]).
 *
 * NOTHING IS KEPT IN MEMORY between operations: the model is read from its database when it is needed ([KeptModels]),
 * the catalogue loaded at the start of a training and dropped at its end.
 */
@Service
class Recommendations(
    private val stores: Stores,
    private val plugins: Plugins,
    private val reader: CatalogueReader,
    private val works: Works,
    private val settings: Settings,
    private val workload: Workload,
    private val kept: KeptModels,
    private val fitting: ModelFitting,
    private val scoring: Scoring,
) {
    private val trainingNow = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap<String, PendingTraining>()

    /** How long the last training of every type took, ms: how long a stream of background changes is let to settle. */
    private val lastTraining = ConcurrentHashMap<String, Long>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "model-training").apply { isDaemon = true }
    }

    /**
     * Training is running now. One planned after a change, or waiting its turn in the queue of the computations, is
     * not: the queue tells it ([Workload]), and the model shows no spinner meanwhile.
     */
    fun training(type: String): Boolean = type in trainingNow

    /**
     * Retrains after a change. A change of the user's ([byUser]: a grade, a mark, a tag, an answer) is learnt shortly
     * after the last of a quick series of them. A change the background work makes — the suggester's chances of every
     * facet of every source, the reranker's likeness chunk after chunk, the pictures — comes in long streams: learnt
     * after a minute of quiet, or twice the last training, never later than [MAX_WAIT_MS] after the first of the
     * stream; a training the user waits for is never put off by it. The [reason] — a key the interface words — is told
     * in the queue of the works ([Workload.plan]) till the training begins.
     */
    fun scheduleRetrain(type: String, reason: String, byUser: Boolean = true, quietMs: Long = USER_QUIET_MS) {
        val now = System.currentTimeMillis()
        pending.compute(type) { _, old ->
            val waiting = old?.takeIf { !it.future.isDone }
            if (!byUser && waiting?.byUser == true) {
                workload.plan(trainingWork(type), Instant.ofEpochMilli(waiting.due), reason)
                return@compute waiting
            }
            waiting?.future?.cancel(false)
            val first = waiting?.firstAt ?: now
            val due =
                if (byUser) now + quietMs
                else minOf(now + maxOf(MACHINE_QUIET_MS, 2 * (lastTraining[type] ?: 0L)), first + MAX_WAIT_MS)
            workload.plan(trainingWork(type), Instant.ofEpochMilli(due), reason)
            val future =
                scheduler.schedule(
                    {
                        // The boundary of the scheduler's thread: whatever a training throws is logged here, or the
                        // future it was put in would keep it unseen
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            // A training a change of the user's asked for waits ahead of the background's works
                            retrainAndScore(type, asked = byUser)
                        } catch (e: Exception) {
                            log.error("{}: background training failed", type, e)
                        }
                    },
                    (due - now).coerceAtLeast(0),
                    TimeUnit.MILLISECONDS,
                )
            PendingTraining(future, first, due, byUser)
        }
    }

    /**
     * The training the user asked for — by the button, or by choosing a scorer — planned to begin at once, after the
     * works before it; the request that asked does not wait for it ([Requests]).
     */
    fun trainSoon(type: String) = scheduleRetrain(type, "asked", quietMs = 0)

    /** A training scheduled: since the first change it waits on, when it is due, and whether the user waits for it. */
    private class PendingTraining(val future: ScheduledFuture<*>, val firstAt: Long, val due: Long, val byUser: Boolean)

    fun retrainAndScore(typeId: String, asked: Boolean = false): ModelSummary =
        workload.run(trainingWork(typeId), asked = asked) {
            val type = stores.contentType(typeId)
            trainingNow += typeId
            try {
                val started = System.currentTimeMillis()
                val catalogue = reader.read(type)
                val loaded = System.currentTimeMillis()
                val model = fitting.grades(catalogue)
                // Of no use without the scores it multiplies
                val interest = if (model != null) fitting.interest(catalogue) else null
                val trained = System.currentTimeMillis()
                if (model != null) scoring.scoreAll(catalogue, model, interest)
                lastTraining[typeId] = System.currentTimeMillis() - started
                log.info(
                    "{}: retrained and every item scored in {} ms: read {}, trained {}, scored {}",
                    typeId,
                    System.currentTimeMillis() - started,
                    loaded - started,
                    trained - loaded,
                    System.currentTimeMillis() - trained,
                )
            } finally {
                trainingNow -= typeId
            }
            summary(typeId)
        }

    /** Re-scores every item of the type with its current model. */
    fun scoreAll(typeId: String) =
        workload.run(Work("score", type = typeId)) {
            val type = stores.contentType(typeId)
            val model = kept.load(type) ?: return@run
            scoring.scoreAll(reader.read(type, withLikeness = false), model, kept.loadInterest(type))
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

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
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

        private fun trainingWork(typeId: String) = Work("train", type = typeId)
    }
}
