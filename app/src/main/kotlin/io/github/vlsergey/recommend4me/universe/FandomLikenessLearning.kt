package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(FandomLikenessLearning::class.java)

/**
 * THE LIKENESS OF THE FANDOMS' NAMES LEARNT OF THE USER'S WORD ON THE UNIVERSES ([FandomLikeness]), in the queue of the
 * computations after the word goes quiet, and on start; kept in the model database of the first content type of
 * universes, whose dictionary is every one's. How right it is told of the universes held out of it: of [FOLDS] parts of
 * them, each learnt without and its pairs put in order — beside the order of the encoder's own likeness.
 */
@Service
class FandomLikenessLearning(
    private val stores: Stores,
    private val names: FandomNameVectors,
    private val workload: Workload,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fandom-likeness").apply { isDaemon = true }
    }

    @Volatile private var pending: ScheduledFuture<*>? = null

    /** The likeness learnt again of the dictionary as it is, and kept; nothing of no universe with two names. */
    fun learn() {
        val type = stores.withUniverses().firstOrNull() ?: return
        val started = System.currentTimeMillis()
        val dictionary = type.universes.dictionary()
        val vectors = names.all()
        val byUniverse =
            dictionary.fandoms.keys
                .associateWith { id -> distinctNames(dictionary.sitesOf(id).mapNotNull { vectors[it] }) }
                .filterValues { it.isNotEmpty() }
        val related = byUniverse.keys.associateWith { dictionary.parentsOf(it) + dictionary.childrenOf(it) }
        val different = HashMap<Long, MutableSet<Long>>()
        type.universes.fandoms.relations.distinct().forEach { (a, b) ->
            different.getOrPut(a) { HashSet() } += b
            different.getOrPut(b) { HashSet() } += a
        }
        fun pairsOf(anchors: Set<Long>) =
            FandomLikeness.pairs(byUniverse.filterKeys { it in anchors }, related, different, byUniverse)
        val all = pairsOf(byUniverse.keys)
        val likeness = FandomLikeness.fit(all) ?: return
        // Of every part of the universes held out, the order of its pairs by the likeness learnt without them
        val heldOut =
            (0 until FOLDS).map { fold ->
                val out = byUniverse.keys.filter { it % FOLDS == fold.toLong() }.toSet()
                pairsOf(out) to FandomLikeness.fit(pairsOf(byUniverse.keys - out))
            }
        val right = heldOut.sumOf { (pairs, learnt) -> FandomLikeness.ordered(pairs, learnt) * pairs.size }
        val asEncoded = heldOut.sumOf { (pairs, _) -> FandomLikeness.ordered(pairs, null) * pairs.size }
        val count = heldOut.sumOf { it.first.size }.coerceAtLeast(1)
        val fit = FandomLikenessFit(all.size, right / count, asEncoded / count)
        type.fandomLikeness.keep(likeness, fit, Instant.now())
        log.info(
            "The likeness of the fandoms' names learnt of {} pairs of {} universes with names, {} said different: " +
                "held out, {} of the pairs in the user's order, of the encoder's own likeness {}; in {} ms",
            all.size,
            byUniverse.size,
            different.size,
            "%.3f".format(fit.heldOut),
            "%.3f".format(fit.asEncoded),
            System.currentTimeMillis() - started,
        )
    }

    // --- In the background ---

    @EventListener(ApplicationReadyEvent::class) fun start() = schedule(START_DELAY_SECONDS, "start")

    @EventListener
    fun related(@Suppress("UNUSED_PARAMETER") event: UniversesRelated) = schedule(QUIET_SECONDS, "universes")

    private fun schedule(delaySeconds: Long, reason: String) {
        if (stores.withUniverses().isEmpty()) return
        val work = Work(LEARNING)
        workload.plan(work, Instant.now().plusSeconds(delaySeconds), reason)
        val next =
            scheduler.schedule(
                {
                    // The boundary of the scheduler's thread: whatever the learning throws is logged here, or the
                    // future it was put in would keep it unseen
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        workload.run(work) { learn() }
                    } catch (e: Exception) {
                        log.warn("The likeness of the fandoms' names was not learnt: {}", e.message, e)
                    }
                },
                delaySeconds,
                TimeUnit.SECONDS,
            )
        pending?.cancel(false)
        pending = next
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        const val LEARNING = "fandomLikeness"

        private const val START_DELAY_SECONDS = 90L

        /** The quiet after the user's last word on the universes before the likeness is learnt again. */
        private const val QUIET_SECONDS = 30L

        /** The parts of the universes held out in turn, to tell how right the likeness learnt is. */
        private const val FOLDS = 3

        /** Two names' vectors as near as this are one name written alike: it teaches nothing of the likeness. */
        private const val SAME_NAME = 0.9999

        /** The [vectors] of a universe's names, the ones written alike once. */
        private fun distinctNames(vectors: List<FloatArray>): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            vectors.forEach { v ->
                if (out.none { o -> v.indices.sumOf { (v[it] * o[it]).toDouble() } >= SAME_NAME }) out += v
            }
            return out
        }
    }
}
