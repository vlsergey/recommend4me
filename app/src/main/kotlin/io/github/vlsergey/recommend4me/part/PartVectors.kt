package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.interest.LikeliestFirst
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.vector.Vectors
import jakarta.annotation.PreDestroy
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

/** The vectors of the windows of the chapters of these items of a source were written. */
data class SiteItemChapterVectorChangeEvent(val source: String, val ids: List<String>)

/**
 * The text of every part of every work read, encoded window by window in the background: a vector for every few hundred
 * words, so a chapter is a set of vectors and one scene unlike the rest is not averaged away. And paragraph by
 * paragraph ([Paragraphs]): what the tags are held against to be offered by the work's own text, a paragraph quoted
 * beside its tag. A part read again with another text is encoded again.
 */
@Service
class PartVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val tasks: Tasks,
    private val likeliest: LikeliestFirst,
) {

    @Volatile private var stopping = false

    @Volatile private var busy = false

    fun running(): Boolean = busy

    @EventListener(SiteItemChapterChangeEvent::class) fun partsSaved() = plan("parts")

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(ENCODING.what) { _, reason -> plan(reason) }
    }

    /**
     * The parts waiting encoded a PORTION at a time, planned at once for the [reason]: of every source the parts of the
     * likeliest works waiting ([LikeliestFirst]), [PORTION_WORKS] of them, in one work of the queue of the
     * computations; of more waiting, the next portion planned as a work of its own — the chapters of a million works of
     * a dump never hold the queue for days, a grade and a training go between (user, 2026-11-17). A part left unencoded
     * — the work failed — is looked for again on the next part saved, or the start.
     */
    private fun plan(reason: String): Unit =
        tasks.plan(ENCODING, null, reason) {
            try {
                val encoder = plugins.textEncoder() ?: return@plan
                val waiting =
                    stores.sources.associate { s ->
                        s.id to s.parts.windows.itemsToEncode(encoder.windowsId, Paragraphs.keyOf(encoder.id))
                    }
                val portion =
                    stores.sources.associate { s ->
                        s.id to likeliest.of(s, waiting.getValue(s.id)).take(PORTION_WORKS).toSet()
                    }
                val reached = Reached()
                while (!stopping && encodeSome(reached, portion) > 0) continue
                if (!stopping && waiting.values.any { it.size > PORTION_WORKS }) tasks.again("portion")
            } finally {
                busy = false
            }
        }

    /**
     * Of one run, the last part of every source given to encode, of its windows and of its paragraphs: the next batch
     * looked for past it. A part left unencoded is met again on the next run.
     */
    private class Reached {
        val windows = HashMap<String, Pair<String, String>>()
        val paragraphs = HashMap<String, Pair<String, String>>()
    }

    /**
     * Encodes a batch of parts of every source's works of the [portion], their windows and then their paragraphs, from
     * past those [reached]; returns how many.
     */
    private fun encodeSome(reached: Reached, portion: Map<String, Set<String>>): Int {
        val encoder = plugins.textEncoder() ?: return 0
        var done = 0
        stores.sources.forEach { s ->
            val works = portion[s.id].orEmpty()
            if (works.isEmpty()) return@forEach
            val todo = s.parts.windows.toEncode(encoder.windowsId, BATCH, reached.windows[s.id], works)
            if (todo.isEmpty()) return@forEach
            reached.windows[s.id] = todo.last().place
            busy = true
            val windows = encoder.encodeWindows(todo.map { it.content })
            todo.forEachIndexed { i, part ->
                s.parts.windows.saveVectors(part.itemId, part.partId, encoder.windowsId, part.hash, windows[i])
            }
            // The works searched by the meaning of their chapters' windows: indexed again with them
            events.publishEvent(SiteItemChapterVectorChangeEvent(s.id, todo.map { it.itemId }.distinct()))
            done += todo.size
        }
        stores.sources.forEach { s ->
            val works = portion[s.id].orEmpty()
            if (works.isEmpty()) return@forEach
            val todo =
                s.parts.paragraphs.paragraphsToEncode(
                    Paragraphs.keyOf(encoder.id),
                    BATCH,
                    reached.paragraphs[s.id],
                    works,
                )
            if (todo.isEmpty()) return@forEach
            reached.paragraphs[s.id] = todo.last().place
            busy = true
            // The paragraphs of every part of the batch encoded together, then given back to their parts: a chapter's
            // few dozen alone ran the graph's batches half empty
            val texts = todo.map { Paragraphs.of(it.content) }
            val vectors = texts.flatten().chunked(PARAGRAPHS).flatMap { encoder.encode(it) }
            var from = 0
            todo.forEachIndexed { i, part ->
                val own = texts[i]
                s.parts.paragraphs.saveParagraphVectors(
                    part.itemId,
                    part.partId,
                    Paragraphs.keyOf(encoder.id),
                    part.hash,
                    vectors.subList(from, from + own.size),
                    own.map(Vectors::hash),
                )
                from += own.size
            }
            done += todo.size
        }
        return done
    }

    @PreDestroy
    fun shutdown() {
        stopping = true
    }

    companion object {
        private val ENCODING = Task(TaskKind.ENCODE_SITE_ITEM_CHAPTERS)

        /** Parts per encoder call: a chapter is tens of windows already. */
        private const val BATCH = 8

        /** Paragraphs per encoder call: a chapter is hundreds of them. */
        private const val PARAGRAPHS = 256

        /** The works of a source whose parts one work of the queue encodes: minutes of the card, not days. */
        private const val PORTION_WORKS = 50
    }
}
