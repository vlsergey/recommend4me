package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
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
    private val stores: Stores,
    private val workload: Workload,
    private val models: PairModels,
    private val answers: PairAnswers,
) {
    /** Whether the models of the type are to be fitted again, or being fitted: of the page of models. */
    fun isFitting(type: TypeStore) = workload.due(work(type))

    /** What the last run of the application left undone of it, planned again. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() =
        workload.resume(WHAT) { left, reason -> left.work.type?.let(stores::type)?.let { later(it, 0, reason) } }

    /** The models fitted again a few seconds after the last answer. */
    fun afterAnswer(type: TypeStore) = later(type, QUIET_SECONDS, "answer")

    /** The models fitted again now, as the user asked. */
    fun now(type: TypeStore) = later(type, 0, "asked")

    /** The models fitted again [quiet] seconds from now, unless asked again meanwhile — then from that asking. */
    private fun later(type: TypeStore, quiet: Long, reason: String) =
        workload.plan(work(type), Instant.now().plusSeconds(quiet), reason, asked = true) { fitAnew(type) }

    private fun work(type: TypeStore) = Work(WHAT, type = type.id)

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

    private companion object {
        /** The quiet after the last answer before the models are fitted again. */
        const val QUIET_SECONDS = 3L

        /** The work of fitting the models of the pairs. */
        const val WHAT = "duplicatesModel"
    }
}
