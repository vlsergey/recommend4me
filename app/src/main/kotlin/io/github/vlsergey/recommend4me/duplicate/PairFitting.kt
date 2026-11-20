package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
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
    private val stores: Stores,
    private val tasks: Tasks,
    private val models: PairModels,
) {
    /** Whether the models of the type are to be fitted again, or being fitted: of the page of models. */
    fun isFitting(type: TypeStore) = tasks.due(work(type))

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(WHAT) { left, reason ->
            left.work.type?.let(stores::type)?.let { later(it, 0, reason) }
        }
    }

    /** The models fitted again a few seconds after the last answer. */
    fun afterAnswer(type: TypeStore) = later(type, QUIET_SECONDS, "answer")

    /** The models fitted again now, as the user asked. */
    fun now(type: TypeStore) = later(type, 0, "asked")

    /**
     * The models fitted again [quiet] seconds from now, unless asked again meanwhile — then from that asking: first of
     * all when the user pressed for it, of the user's change after an answer (owner, 2026-11-19: "do I wait for
     * anything after marking the duplicates? I wait only if I pressed some buttons like 'find the duplicates anew'").
     */
    private fun later(type: TypeStore, quiet: Long, reason: String) =
        tasks.plan(
            work(type),
            Instant.now().plusSeconds(quiet),
            reason,
            priority = if (reason == "asked") TaskPriority.ASKED else TaskPriority.USER,
        ) {
            fitAnew(type)
        }

    private fun work(type: TypeStore) = Task(WHAT, type = type.id)

    /**
     * The models fitted anew on the pairs answered, by the words kept with them — those the user said since the search
     * among them ([DuplicateRepository.said]) —, kept with when and how long it took, and every pair scored anew by
     * them: what a page is chosen in the order of ([DuplicateChoice]). None without a plugin.
     */
    private fun fitAnew(type: TypeStore) {
        val model = models.model(type.id) ?: return
        val started = System.currentTimeMillis()
        val answered = type.duplicates.reads.answered()
        val said = answered.associate { (c, word) -> c.pair to word }
        val fitted = PairModels.fit(model, answered.map { it.first }, said)
        val fit = System.currentTimeMillis() - started
        type.duplicates.keepModels(model.id, fitted.same?.pack(), fitted.series?.pack(), Instant.now(), fit)
        val same = fitted.same ?: model.prior()
        val scored = type.duplicates.rescore { c -> same.chance(c.layers) to fitted.series?.chance(c.layers) }
        log.info(
            "{}: the models of the pairs fitted on {} pairs answered — {} said one work, {} one series, {} related " +
                "otherwise, {} different — in {} ms, and {} pairs scored anew by them in {} ms",
            type.id,
            answered.size,
            said.values.count { it == DuplicateAnswer.SAME },
            said.values.count { it == DuplicateAnswer.SERIES },
            said.values.count { it.related && it != DuplicateAnswer.SERIES },
            said.values.count { it == DuplicateAnswer.DIFFERENT },
            fit,
            scored,
            System.currentTimeMillis() - started - fit,
        )
    }

    private companion object {
        /** The quiet after the last answer before the models are fitted again. */
        const val QUIET_SECONDS = 3L

        /** The work of fitting the models of the pairs. */
        val WHAT = TaskKind.FIT_DUPLICATE_MODEL
    }
}
