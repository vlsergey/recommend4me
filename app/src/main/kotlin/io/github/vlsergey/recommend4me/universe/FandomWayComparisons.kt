package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(FandomWayComparisons::class.java)

/** A comparison of the ways a site's fandom is read: under way until [finishedAt], every way's finding once done. */
class FandomWayComparison(val startedAt: Instant) {
    @Volatile var found: Map<FandomWay, FandomFound?> = emptyMap()
    @Volatile var error: String? = null
    @Volatile var millis: Long? = null
    @Volatile var finishedAt: Instant? = null
}

/**
 * THE WAYS A SITE'S FANDOM IS READ COMPARED, WHEN THE USER ASKS ON THE PAGE OF THE MODELS ([FandomRetrieval]): how well
 * each finds the universes as the user made them, by the encoder's likeness — a minute and more of the card over every
 * fandom, never a part of learning the likeness. Nothing is kept but the comparison last started, while the application
 * runs: it is a measuring, not data — the way chosen is the user's ([FandomWays]). A comparison under way is not
 * started again.
 */
@Component
class FandomWayComparisons(
    private val stores: Stores,
    private val fandoms: FandomRowsReading,
    private val learning: FandomLikenessLearning,
    private val tasks: Tasks,
) {
    @Volatile private var last: FandomWayComparison? = null

    /** The comparison last started. */
    fun last(): FandomWayComparison? = last

    @Synchronized
    fun start(): FandomWayComparison {
        last
            ?.takeIf { it.finishedAt == null }
            ?.let {
                return it
            }
        return FandomWayComparison(Instant.now()).also { c ->
            last = c
            tasks.plan(Task(TaskKind.COMPARE_FANDOM_WAYS), null, "asked", priority = TaskPriority.ASKED) { run(c) }
        }
    }

    private fun run(comparison: FandomWayComparison) {
        val started = System.currentTimeMillis()
        // The boundary of the comparison: whatever stops it is told on the page, and the comparison finished all
        // the same — else it would stand under way till the application stops
        @Suppress("TooGenericExceptionCaught")
        try {
            val dictionary = stores.withUniverses().firstOrNull()?.universes?.dictionary() ?: return
            val read = fandoms.read()
            comparison.found =
                FandomRetrieval.of(
                    dictionary.fandoms.keys.associateWith { dictionary.sitesOf(it) },
                    read,
                    learning.card(),
                )
            log.info(
                "The ways of reading the sites' fandoms compared in {} ms: {}",
                System.currentTimeMillis() - started,
                comparison.found.entries.joinToString("; ") { (way, f) ->
                    "${way.name.lowercase()} " +
                        (f?.let { "first %.3f, 1/rank %.3f".format(it.first, it.reciprocal) } ?: "none asked")
                },
            )
        } catch (e: Exception) {
            log.warn("The ways of reading the sites' fandoms could not be compared", e)
            comparison.error = e.message ?: e.javaClass.simpleName
        } finally {
            comparison.millis = System.currentTimeMillis() - started
            comparison.finishedAt = Instant.now()
        }
    }
}
