package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(PairFitting::class.java)

/**
 * THE FITTING OF THE MODELS OF THE PAIRS on the user's answers, of the pairs kept as they are: a few seconds after the
 * last answer ([afterAnswer]), or at once when the user asks ([now]) — in the queue of the computations, ahead of the
 * background's works: the user asked for it, and the pairs are shown in its order as soon as it is kept. Every search
 * fits them besides ([Duplicates]).
 */
@Component
class PairFitting(
    private val workload: Workload,
    private val models: PairModels,
    private val answers: PairAnswers,
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pair-models").apply { isDaemon = true }
    }

    /** The types whose models are to be fitted again, put off by every answer till they are quiet, or being fitted. */
    private val fitting = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** Whether the models of the type are to be fitted again, or being fitted: of the page of models. */
    fun isFitting(type: TypeStore) = fitting.containsKey(type.id)

    /** The models fitted again a few seconds after the last answer. */
    fun afterAnswer(type: TypeStore) = later(type, QUIET_SECONDS, "answer")

    /** The models fitted again now, as the user asked. */
    fun now(type: TypeStore) = later(type, 0, "asked")

    /** The models fitted again [quiet] seconds from now, unless asked again meanwhile — then from that asking. */
    private fun later(type: TypeStore, quiet: Long, reason: String) {
        val work = Work("duplicatesModel", type = type.id)
        workload.plan(work, Instant.now().plusSeconds(quiet), reason)
        fitting.compute(type.id) { _, before ->
            before?.cancel(false)
            worker.schedule(
                {
                    // The boundary of the worker's thread: whatever the fitting throws is logged, the type let go of
                    @Suppress("TooGenericExceptionCaught")
                    try {
                        workload.run(work, asked = true) { fitAnew(type) }
                    } catch (e: Exception) {
                        log.warn("{}: the model of the duplicates was not fitted: {}", type.id, e.message, e)
                    } finally {
                        fitting.remove(type.id)
                    }
                },
                quiet,
                TimeUnit.SECONDS,
            )
        }
    }

    /**
     * The models fitted anew on the pairs kept as they are, and kept, with when and how long: none without a plugin.
     */
    private fun fitAnew(type: TypeStore) {
        val model = models.model(type.id) ?: return
        val started = System.currentTimeMillis()
        val said = answers.said(type)
        val pairs = type.duplicates.all()
        val fitted = PairModels.fit(model, pairs, said)
        type.duplicates.keepModels(
            model.id,
            fitted.same?.pack(),
            fitted.series?.pack(),
            Instant.now(),
            System.currentTimeMillis() - started,
        )
        log.info(
            "{}: the models of the pairs fitted on {} pairs, {} said one work, {} one series, {} related otherwise, " +
                "{} different, in {} ms",
            type.id,
            pairs.size,
            said.values.count { it == DuplicateAnswer.SAME },
            said.values.count { it == DuplicateAnswer.SERIES },
            said.values.count { it.related && it != DuplicateAnswer.SERIES },
            said.values.count { it == DuplicateAnswer.DIFFERENT },
            System.currentTimeMillis() - started,
        )
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    private companion object {
        /** The quiet after the last answer before the models are fitted again. */
        const val QUIET_SECONDS = 3L
    }
}
