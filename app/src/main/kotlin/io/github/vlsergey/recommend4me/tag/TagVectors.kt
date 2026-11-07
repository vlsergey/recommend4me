package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.item.SynonymsSet
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(TagVectors::class.java)

/**
 * THE VECTORS OF THE TAGS, MADE IN THE BACKGROUND: of every normalized tag that has none by the text encoder in use —
 * found by a join, not by reading every tag — one of every name of it, its sites' tags' and its entries', kept in the
 * dictionaries ([TagVectorRepository.vectors]): a tag is alike by the best of its names, so a synonym added finds more
 * and blurs nothing. A tag whose names change loses its vectors there ([TagVectorRepository.forgetVectors]) and is made
 * again here.
 *
 * Made after any change of the tags — the user's word on them, the sites' tags bound as works come — once the changes
 * have paused, and five minutes after the start. The screen of the tags reads the vectors kept; a tag without one yet
 * is alike nothing for a while.
 */
@Component
class TagVectors(
    private val normalization: TagNormalization,
    private val plugins: Plugins,
    private val phrases: PhraseVectors,
    private val workload: Workload,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tag-vectors").apply { isDaemon = true }
    }
    private val pending = AtomicReference<ScheduledFuture<*>?>()

    @EventListener(ApplicationReadyEvent::class) fun start() = schedule(START_DELAY_SECONDS, "start")

    @EventListener(TagsChanged::class) fun tagsChanged() = schedule(QUIET_SECONDS, "tags")

    /** Works came or changed: their sites' tags bound to tags ([TagNormalization]), maybe new ones. */
    @EventListener(ItemsChanged::class) fun itemsChanged() = schedule(QUIET_SECONDS, "items")

    /**
     * A site named the synonyms of its tags: bound to their tags at once ([TagNormalization]), each a name more of its
     * tag.
     */
    @EventListener(SynonymsSet::class) fun synonymsSet() = schedule(QUIET_SECONDS, "synonyms")

    /** The vectors made after the quiet; the [reason], a key the interface words, told in the queue of the works. */
    private fun schedule(delaySeconds: Long, reason: String) {
        workload.plan(VECTORS, Instant.now().plusSeconds(delaySeconds), reason)
        val next = scheduler.schedule({ fillInBackground() }, delaySeconds, TimeUnit.SECONDS)
        pending.getAndSet(next)?.cancel(false)
    }

    /** The vectors made in the queue of the works: a task of the background, which logs what fails. */
    // The background's loop: whatever fails is told in the log, and the next change plans it again
    @Suppress("TooGenericExceptionCaught")
    private fun fillInBackground() {
        try {
            workload.run(VECTORS) { fill() }
        } catch (e: Exception) {
            log.warn("The vectors of the tags were not made: {}", e.message, e)
        }
    }

    /** Makes the vector of every tag that has none by the encoder in use; how many were made. */
    fun fill(): Int =
        plugins.textEncoder()?.let { encoder -> normalization.scope()?.let { scope -> fill(scope, encoder) } } ?: 0

    private fun fill(scope: TagScope, encoder: TextEncoder): Int {
        val plugin = plugins.pluginOf(encoder)
        val missing = scope.tags.vectors.withoutVectors(plugin, encoder.id).toSet()
        if (missing.isEmpty()) return 0
        val started = System.currentTimeMillis()
        val ofTag = TagNameVectors(scope, encoder, normalization, phrases).of(missing)
        // Every tag without a vector is kept with one — of no name, an empty one — not asked again till its names
        // change
        missing.chunked(KEEP).forEach { chunk ->
            scope.tags.vectors.keepVectors(
                plugin,
                encoder.id,
                chunk.associateWith { tag -> ofTag[tag].orEmpty().mapNotNull(::unit) },
            )
        }
        log.info(
            "Vectors of {} tags made by {} ({}) in {} ms",
            missing.size,
            encoder.id,
            plugin,
            System.currentTimeMillis() - started,
        )
        return missing.size
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        /** After the start: the sources read, the encoder loaded, the first fittings under way. */
        private const val START_DELAY_SECONDS = 5 * 60L

        /** The quiet after a change: a page is sent several times as it grows, the user answers in a row. */
        private const val QUIET_SECONDS = 10L

        /** Tags written at once. */
        private const val KEEP = 500

        /** The making of the vectors, as the queue of the works tells it. */
        private val VECTORS = Work("tagVectors")

        /** [v] of unit length; null of a vector of nought. */
        fun unit(v: FloatArray): FloatArray? {
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: return null
            return FloatArray(v.size) { v[it] / norm }
        }

        /**
         * How alike two sets of names are: the best cosine of a name of one and a name of the other; nought of an empty
         * one.
         */
        fun likeness(a: List<FloatArray>, b: List<FloatArray>): Double =
            a.maxOfOrNull { x -> b.maxOfOrNull { y -> x.indices.sumOf { (x[it] * y[it]).toDouble() } } ?: 0.0 } ?: 0.0
    }
}
