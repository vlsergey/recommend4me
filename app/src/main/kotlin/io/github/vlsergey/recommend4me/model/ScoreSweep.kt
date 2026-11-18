package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(ScoreSweep::class.java)

/**
 * THE SWEEP OF THE SCORES OVER EVERY WORK, A WORK OF ITS OWN APART FROM THE TRAINING (user, 2026-11-17: the training of
 * the model and the updating of the scores, two): of the books' 1.4 million works one training and scoring held the
 * queue for an hour and a half. A training reads the graded works alone and begins a sweep ([begin]); the sweep reads
 * every work a portion at a time ([EveryWork.portions]), scores it by the model kept and writes what changed, and
 * counts the average work along — every vector of an item as the scoring reads it, every work's features — kept at its
 * end for the next training ([AverageWorkRepository]).
 *
 * IN TURNS OF THE QUEUE, never asked: a turn ([step]) goes on for [STEP] at most, then plans the next one where it
 * stopped — a grade, a training, the index of the list, the facets' passes go between. Where it stands — the model, the
 * next portion, the sums of the average work so far — is kept in the model database ([ScoreSweepRepository]), never in
 * the memory: the sweep goes on after a restart of the application.
 *
 * ONE MODEL A SWEEP: a turn that finds another model kept than the sweep scored by — a training finished between —
 * begins it again from the first work, and a portion is not scored once the model kept is another. At the end of a
 * sweep the predictions of the items gone are removed ([Scoring.forgetGone]).
 */
@Component
class ScoreSweep(
    private val stores: Stores,
    private val plugins: Plugins,
    private val everyWork: EveryWork,
    private val scoring: Scoring,
    private val kept: KeptModels,
    private val likenesses: Likenesses,
    private val workload: Workload,
) {

    /**
     * A sweep of the [type] begun by its current model from the first work, the one under way given up, and planned for
     * the [reason]; none without a model.
     */
    fun begin(type: TypeStore, reason: String) {
        val model = type.models.trainedAt() ?: return
        type.sweep.save(fresh(model, AverageWork.basis(plugins, type)))
        plan(type.id, reason)
    }

    /** A sweep the last run of the application left unfinished goes on: of its state kept, whatever the queue kept. */
    @EventListener(ApplicationReadyEvent::class)
    fun resume() {
        workload.left(work("").what)
        stores.types.filter { it.sweep.load() != null }.forEach { plan(it.id, "start") }
    }

    /** Whether a sweep of the [typeId] goes on: some works are not scored by the current model yet. */
    fun sweeping(typeId: String): Boolean = stores.contentType(typeId).sweep.load() != null

    /** The sweep of the [typeId] run to its end now, on the caller's thread, turn after turn: for the tests. */
    fun sweepNow(typeId: String) {
        while (sweeping(typeId)) workload.run(work(typeId), mapOf("asked" to 1)) { step(typeId) }
    }

    /**
     * ONE TURN OF THE SWEEP of the [typeId]: portions of [portion] works scored from where it stopped till [budget]
     * passed, then where it stands kept and the next turn planned — or, of the last portion, the sweep ended. Nothing
     * of no sweep under way; of no model, the sweep forgotten.
     */
    internal fun step(typeId: String, budget: Duration = STEP, portion: Int = EveryWork.PORTION) {
        val type = stores.contentType(typeId)
        val state = type.sweep.load() ?: return
        val model = kept.load(type)
        if (model == null) type.sweep.forget() else go(type, state, model, budget, portion)
    }

    /** The turn of [step] of the sweep [was] by the [model] kept. */
    private fun go(type: TypeStore, was: SweepState, model: TrainedModel, budget: Duration, portion: Int) {
        val started = System.currentTimeMillis()
        val trainedAt = Instant.ofEpochMilli(model.meta.trainedAtMillis)
        val basis = AverageWork.basis(plugins, type)
        // Another model, or the vectors made otherwise, since the sweep began: from the first work again
        val state = if (was.model == trainedAt && was.basis == basis) was else fresh(trainedAt, basis)
        val count = AverageCount(plugins.textEncoder(), plugins.imageEncoder(), type.embeddings.ids())
        state.counted?.let(count::resume)
        val interest = kept.loadInterest(type)
        val likeness = likenesses.asMade(type, ratingsOf(type).map { it.key })
        var scored = state.scored
        var changed = state.changed
        var portions = 0
        val at =
            everyWork.portions(type, likeness, state.at, count, portion) { read ->
                val done = scoring.score(read.catalogue, model, interest)
                count.addAll(read)
                scored += done.items
                changed += done.changed
                portions++
                // No portion scored by a model another than the one kept
                System.currentTimeMillis() - started < budget.toMillis() && type.models.trainedAt() == trainedAt
            }
        log.info(
            "{}: {} portions of the sweep of the scores in {} ms, {} items scored since it began, to go on {}",
            type.id,
            portions,
            System.currentTimeMillis() - started,
            scored,
            at ?: "nowhere: every work scored",
        )
        val now = SweepState(trainedAt, basis, at, state.began, scored, changed, count.pack())
        if (at == null) finish(type, now, count) else keep(type, now)
    }

    /** The sweep goes on in its next turn from where it stands [now]. */
    private fun keep(type: TypeStore, now: SweepState) {
        type.sweep.save(now)
        plan(type.id, "sweep")
    }

    /**
     * Every work scored: the predictions of the items gone removed, the average work [count]ed kept for the next
     * training, the sweep [ended] forgotten.
     */
    private fun finish(type: TypeStore, ended: SweepState, count: AverageCount) {
        val gone = scoring.forgetGone(type)
        type.averageWork.save(count.made(), ended.basis)
        type.sweep.forget()
        log.info(
            "{}: every work scored in {} s since the sweep began: {} predictions made, {} of them changed, " +
                "{} of items gone removed",
            type.id,
            Duration.between(ended.began, Instant.now()).toSeconds(),
            ended.scored,
            ended.changed,
            gone,
        )
    }

    /** The next turn of the sweep of the [typeId] planned, at once — in the turn of the background. */
    private fun plan(typeId: String, reason: String) = workload.plan(work(typeId), null, reason) { step(typeId) }

    private companion object {
        /**
         * How long a turn of the sweep goes on before it gives the queue to the works waiting: the fairness of the
         * queue, well under the two minutes the background waits before it goes as asked. A turn ends after the portion
         * under way: of fanfics.me, half a minute a portion of ten thousand works.
         */
        val STEP: Duration = Duration.ofMinutes(1)

        fun work(typeId: String) = Work("score", type = typeId)

        /** A sweep by the model trained at [model] from the first work, of nothing counted yet. */
        fun fresh(model: Instant, basis: String) = SweepState(model, basis, null, Instant.now(), 0, 0, null)
    }
}
