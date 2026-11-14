package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
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
    private val fandoms: FandomRowsReading,
    private val workload: Workload,
    private val cards: ObjectProvider<CardSource>,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "fandom-likeness").apply { isDaemon = true }
    }

    @Volatile private var pending: ScheduledFuture<*>? = null

    /** The kernels of the likeness and the card they were compiled for: compiled once a card. */
    @Volatile private var compiled: FandomLikeness.OnCard? = null

    /** The card with the kernels of the likeness; none without a card, or of kernels that would not compile. */
    private fun card(): FandomLikeness.OnCard? {
        val cublas = cards.getIfAvailable()?.cublas() ?: return null
        return compiled?.takeIf { it.cublas === cublas }
            ?: FandomLikenessOnCard.kernels(cublas)?.let { FandomLikeness.OnCard(cublas, it) }?.also { compiled = it }
    }

    /** The likeness learnt again of the dictionary as it is, and kept; nothing of no universe with two names. */
    fun learn() {
        val type = stores.withUniverses().firstOrNull() ?: return
        val started = System.currentTimeMillis()
        val dictionary = type.universes.dictionary()
        // The names' and the descriptions' vectors read as they are kept, into one block off the heap
        val read = fandoms.read()
        val rows = read.rows
        val card = card()
        val word = Word(type, dictionary, read)
        val byUniverse = word.byUniverse
        val loaded = System.currentTimeMillis()
        val all = word.pairsOf(byUniverse.keys)
        val paired = System.currentTimeMillis()
        val readable = rows.rows > 0 && rows.cols % 2 == 0 && rows.cols <= FandomLikenessOnCard.MAX_DIM
        val likeness = (if (readable) FandomLikeness.fit(all, rows, card) else null) ?: return
        val fitted = System.currentTimeMillis()
        // Of every part of the universes held out, the order of its pairs by the likeness learnt without them
        val heldOut =
            (0 until FOLDS).map { fold ->
                val out = byUniverse.keys.filter { it % FOLDS == fold.toLong() }.toSet()
                word.pairsOf(out) to FandomLikeness.fit(word.pairsOf(byUniverse.keys - out), rows, card)
            }
        val learntHeldOut = System.currentTimeMillis()
        fun share(of: (NamePairs, FandomLikeness?) -> Double) =
            heldOut.sumOf { (pairs, learnt) -> of(pairs, learnt) * pairs.size } /
                heldOut.sumOf { it.first.size }.coerceAtLeast(1)
        val right = share { pairs, learnt -> FandomLikeness.ordered(pairs, rows, learnt, card) }
        val asEncoded = share { pairs, _ -> FandomLikeness.ordered(pairs, rows, null, card) }
        val measured = System.currentTimeMillis()
        val fit = FandomLikenessFit(all.size, right, asEncoded)
        type.fandomLikeness.keep(likeness, fit, Instant.now())
        val found = FandomRetrieval.of(dictionary.fandoms.keys.associateWith { dictionary.sitesOf(it) }, read, card)
        log.info(
            "The likeness of the fandoms' names learnt {} of {} pairs of {} universes with names, {} said " +
                "different: held out, {} of the pairs in the user's order, of the encoder's own likeness {}. " +
                "The universes found of their sites' fandoms by the encoder's likeness, {} of {} fandoms described: " +
                "{}. In {} ms: the names read {}, the pairs {}, learnt of all {}, of the parts held out {}, " +
                "the order of the held out {}, the ways compared {}",
            if (card != null) "on the card" else "on the processor",
            all.size,
            byUniverse.size,
            word.different.size,
            "%.3f".format(fit.heldOut),
            "%.3f".format(fit.asEncoded),
            read.ofFandom.values.count { it.description != FandomRowsOf.NONE },
            read.ofFandom.size,
            found.entries.joinToString("; ") { (way, f) ->
                "${way.name.lowercase()} " +
                    (f?.let { "first %.3f, 1/rank %.3f".format(it.first, it.reciprocal) } ?: "none asked")
            },
            System.currentTimeMillis() - started,
            loaded - started,
            paired - loaded,
            fitted - paired,
            learntHeldOut - fitted,
            measured - learntHeldOut,
            System.currentTimeMillis() - measured,
        )
    }

    /**
     * The user's word on the universes of the [type]'s [dictionary], of the names' rows [read]: every universe's names
     * — a name's description a synonym of it, one more name of the universe —, the universes above and under each, and
     * those said different.
     */
    private class Word(type: TypeStore, dictionary: FandomDictionary, read: FandomRows) {
        val byUniverse =
            dictionary.fandoms.keys
                .associateWith { id -> read.of(dictionary.sitesOf(id)) }
                .filterValues { it.isNotEmpty() }
        private val related = byUniverse.keys.associateWith { dictionary.parentsOf(it) + dictionary.childrenOf(it) }
        val different = HashMap<Long, MutableSet<Long>>()

        init {
            type.universes.fandoms.relations.distinct().forEach { (a, b) ->
                different.getOrPut(a) { HashSet() } += b
                different.getOrPut(b) { HashSet() } += a
            }
        }

        /** The pairs of the universes [anchors]. */
        fun pairsOf(anchors: Set<Long>) =
            NamePairs.of(byUniverse.filterKeys { it in anchors }, related, different, byUniverse)
    }

    // --- In the background ---

    @EventListener(ApplicationReadyEvent::class) fun start() = schedule(START_DELAY_SECONDS, "start")

    @EventListener
    fun related(@Suppress("UNUSED_PARAMETER") event: UniversesRelated) = schedule(QUIET_SECONDS, "universes")

    /** New names or descriptions of the sites' fandoms encoded: more to learn of. */
    @EventListener
    fun encoded(@Suppress("UNUSED_PARAMETER") event: FandomVectorsMade) = schedule(QUIET_SECONDS, "fandomVectors")

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
    }
}
