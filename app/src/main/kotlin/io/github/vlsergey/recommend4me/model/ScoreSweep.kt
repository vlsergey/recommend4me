package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.worklist.ListFilling
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.ListPasses
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
 * THE FOURTH STAGE OF THE COMPUTATIONS (owner, 2026-11-19: "in low priority we start taking 1k unrated works at a time
 * and updating both the computed values and the prediction of the grade and interest for them"; "the order: by the
 * grade descending"; docs/computations.md): after the models of the grades are made ([begin]), the works not graded a
 * [PORTION] at a time, the highest predicted first, not done by the pass under way ([ListPasses]) — of each portion the
 * chain of the models of the fields run once ([EveryWork.ofKeys]): the works scored of it by the models kept, their
 * predictions written where they changed, and their values written into the index of the list where they changed
 * ([ListFilling.written]), every item read marked done by the pass. The average work is counted along — every vector of
 * an item as the scoring reads it, every work's features, the graded works' as the pass begins — kept at the end for
 * the next training ([AverageWorkRepository]).
 *
 * LAST OF THE QUEUE, IN TURNS: a turn ([step]) goes on for [STEP] at most, then plans the next one — every other work
 * goes between. Where it stands — the model, the pass, the sums of the average work so far — is kept in the model
 * database ([ScoreSweepRepository]), never in the memory: the pass goes on after a restart of the application.
 *
 * ONE MODEL A PASS: a turn that finds another model kept than the pass scored by — a training finished between — begins
 * a pass anew from the highest predicted work, and a portion is not scored once the model kept is another. At the end
 * of a pass the predictions of the items gone are removed ([Scoring.forgetGone]).
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
    private val works: Works,
    private val lists: ListIndexes,
    private val filling: ListFilling,
) {

    /**
     * A pass of the [type] begun by its current model from the highest predicted work, the one under way given up, and
     * planned for the [reason] — of no model of the grades too: the works' values are filled, none scored.
     */
    fun begin(type: TypeStore, reason: String) {
        type.sweep.save(fresh(trainedAt(type), AverageWork.basis(plugins, type)))
        plan(type.id, reason)
    }

    /** A pass the last run of the application left unfinished goes on: of its state kept, whatever the queue kept. */
    @EventListener(ApplicationReadyEvent::class)
    fun resume() {
        workload.left(work("").what)
        stores.types.filter { it.sweep.load() != null }.forEach { plan(it.id, "start") }
    }

    /** Whether a pass of the [typeId] goes on: some works are not scored by the current model yet. */
    fun sweeping(typeId: String): Boolean = stores.contentType(typeId).sweep.load() != null

    /** The pass of the [typeId] run to its end now, on the caller's thread, turn after turn: for the tests. */
    fun sweepNow(typeId: String) {
        while (sweeping(typeId)) workload.run(work(typeId), mapOf("asked" to 1)) { step(typeId) }
    }

    /**
     * ONE TURN OF THE PASS of the [typeId]: portions of [portion] works scored and filled till [budget] passed, then
     * where it stands kept and the next turn planned — or, of no work left, the pass ended. Nothing of no pass under
     * way.
     */
    internal fun step(typeId: String, budget: Duration = STEP, portion: Int = PORTION) {
        val type = stores.contentType(typeId)
        val state = type.sweep.load() ?: return
        go(type, state, kept.load(type), budget, portion)
    }

    /** The turn of [step] of the pass [was] by the [model] kept; of none, the values filled alone. */
    private fun go(type: TypeStore, was: SweepState, model: TrainedModel?, budget: Duration, portion: Int) {
        val started = System.currentTimeMillis()
        val trainedAt = model?.let { Instant.ofEpochMilli(it.meta.trainedAtMillis) } ?: Instant.EPOCH
        val basis = AverageWork.basis(plugins, type)
        // Another model, or the vectors made otherwise, since the pass began: a pass anew
        val state = if (was.model == trainedAt && was.basis == basis) was else fresh(trainedAt, basis)
        val turn = Turn(type, state, model)
        if (state.counted == null) turn.countGraded()
        // A portion a turn at least, more while the budget lasts; none scored by a model another than the one kept
        var left = true
        fun more() = turn.portions == 0 || System.currentTimeMillis() - started < budget.toMillis()
        while (left && more() && trainedAt(type) == trainedAt) left = turn.portion(portion)
        log.info(
            "{}: {} portions of the fourth stage in {} ms, {} items scored since its pass began{}",
            type.id,
            turn.portions,
            System.currentTimeMillis() - started,
            turn.scored,
            if (left) "" else ": every work scored",
        )
        val now = SweepState(trainedAt, basis, state.began, turn.scored, turn.changed, turn.count.pack())
        if (left) keep(type, now) else finish(type, now, turn.count)
    }

    /** One turn of the pass [state] of the [type] by the [model], what it read alike for every portion. */
    private inner class Turn(
        private val type: TypeStore,
        private val state: SweepState,
        private val model: TrainedModel?,
    ) {
        val count =
            AverageCount(plugins.textEncoder(), plugins.imageEncoder(), type.embeddings.ids()).also { c ->
                state.counted?.let(c::resume)
            }
        private val interest = kept.loadInterest(type)
        private val likeness = likenesses.asMade(type, ratingsOf(type).map { it.key })
        private val index = lists.of(type.id)
        private val clusters = works.of(type)
        var scored = state.scored
        var changed = state.changed
        var portions = 0

        /** The graded works — the pass leaves them out — counted into the average work as it begins: of every work. */
        fun countGraded() {
            val graded = ratingsOf(type).map { clusters.representative(it.key) }.distinct()
            everyWork.ofKeys(type, likeness, graded, count) { read ->
                count.addAll(read)
                // Their vectors read as a scoring reads them: told to the count as they are
                read.catalogue.vectorsOfMany(read.catalogue.items.map { it.key })
            }
        }

        /**
         * The next [n] works of the pass scored and filled, marked done: false when none was left — the pass is done.
         */
        fun portion(n: Int): Boolean {
            val keys = ListPasses.next(index, state.pass, n)
            if (keys.isEmpty()) return false
            val done = HashSet<ItemKey>(keys)
            everyWork.ofKeys(type, likeness, keys, count) { read ->
                val made = model?.let { scoring.score(read.catalogue, it, interest) }
                filling.written(type, read.given)
                count.addAll(read)
                scored += made?.items ?: 0
                changed += made?.changed ?: 0
                read.catalogue.items.forEach { done += clusters.members(it.key) }
            }
            ListPasses.passed(index, done, state.pass)
            index.commit()
            portions++
            return true
        }
    }

    /** The pass goes on in its next turn from where it stands [now]. */
    private fun keep(type: TypeStore, now: SweepState) {
        type.sweep.save(now)
        plan(type.id, "sweep")
    }

    /**
     * Every work scored: the predictions of the items gone removed, the average work [count]ed kept for the next
     * training, the pass [ended] forgotten.
     */
    private fun finish(type: TypeStore, ended: SweepState, count: AverageCount) {
        val gone = scoring.forgetGone(type)
        type.averageWork.save(count.made(), ended.basis)
        type.sweep.forget()
        log.info(
            "{}: every work scored in {} s since the pass began: {} predictions made, {} of them changed, " +
                "{} of items gone removed",
            type.id,
            Duration.between(ended.began, Instant.now()).toSeconds(),
            ended.scored,
            ended.changed,
            gone,
        )
    }

    /** The next turn of the pass of the [typeId] planned, at once — last of the queue. */
    private fun plan(typeId: String, reason: String) =
        workload.plan(work(typeId), null, reason, last = true) { step(typeId) }

    private companion object {
        /**
         * How long a turn of the pass goes on before it gives the queue to the works waiting: the fairness of the
         * queue, well under the two minutes the background waits before it goes as asked. A turn ends after the portion
         * under way.
         */
        val STEP: Duration = Duration.ofMinutes(1)

        /** The works a portion of the pass takes (owner, 2026-11-19: "taking 1k unrated works at a time"). */
        const val PORTION = 1000

        fun work(typeId: String) = Work("score", type = typeId)

        /** When the model of the grades of the [type] kept was trained; the beginning of time of none. */
        fun trainedAt(type: TypeStore): Instant = type.models.trainedAt() ?: Instant.EPOCH

        /** A pass by the model trained at [model], of nothing counted yet: its pass the moment it begins. */
        fun fresh(model: Instant, basis: String) = SweepState(model, basis, Instant.now(), 0, 0, null)
    }
}
