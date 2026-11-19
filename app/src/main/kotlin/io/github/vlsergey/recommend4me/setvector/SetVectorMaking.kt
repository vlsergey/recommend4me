package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.item.SiteItemWriteEvent
import io.github.vlsergey.recommend4me.picture.SiteItemPictureAnalysisEvent
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskKeys
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.textvector.SiteItemTextVectorChangeEvent
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(SetVectorMaking::class.java)

/**
 * THE MAKING OF THE SETS' VECTORS ([SetVectors]) IN THE QUEUE OF THE COMPUTATIONS, one work of a type (owner,
 * 2026-11-19: "no loop in the background in the code: a work that sees it has a tail plans itself again"; "where did
 * the missing ones come from — why is it not a consequence of an outer event?"): a set grows of an event alone — the
 * works' reviews written, their texts' windows made, their pictures analysed — and those works are kept waiting in the
 * queue ([TaskKeys]), made a portion at a time, the graded works first, then from the top of the recommendations down;
 * a tail left planned again after it ([Tasks.again]), its bound the sets made already. Every set of a type is made
 * again when the encoder of the directions kept is not the one in use.
 *
 * THE DIRECTIONS ARE FITTED ONCE per content type, on a sample of its catalogue, and kept ([SetDirections]); the
 * members of the sets are read by [SetMembers].
 */
@Service
class SetVectorMaking(
    private val stores: Stores,
    private val sets: SetMembers,
    private val directions: SetDirections,
    private val contexts: SourceContexts,
    private val events: ApplicationEventPublisher,
    private val tasks: Tasks,
) {
    /** Whether the vectors of some type's sets are to be made. */
    fun running(): Boolean = stores.types.any { tasks.due(work(it.id)) }

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        // Of a work of a source of before (app V4), every work of the source waiting
        tasks.resumption.resumes(TaskKind.MAKE_SITE_ITEM_SET_VECTORS) { left, reason ->
            left.work.type?.let { plan(it, reason) } ?: left.work.source?.let { whole(it, reason) }
        }
    }

    /** The works written: their sets of reviews may have grown. */
    @EventListener fun itemsWritten(event: SiteItemWriteEvent) = grew(event.source, event.ids, "items")

    /** The windows of the works' texts made: their sets of chapters may have grown. */
    @EventListener fun textsEncoded(event: SiteItemTextVectorChangeEvent) = grew(event.source, event.ids, "texts")

    /** The works' pictures analysed: their sets of screenshots may have grown. */
    @EventListener fun picturesAnalysed(event: SiteItemPictureAnalysisEvent) = grew(event.source, event.ids, "pictures")

    /**
     * Of every type whose sets' directions were fitted by another encoder than the one in use — a model of the pictures
     * or the texts chosen since — every set made again: what the data say, nothing for the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() =
        stores.types.forEach { type ->
            val other =
                SetKind.entries.any { kind ->
                    val encoder = sets.encoderOf(kind)
                    val kept = type.embeddings.find(kind)?.encoder
                    encoder != null && kept != null && kept != encoder
                }
            if (!other) return@forEach
            tasks.keys.want(work(type.id), type.sources.map { "${it.id}$SEP$WHOLE" })
            plan(type.id, "setsGrew")
        }

    /** Every work of the [source] waiting, the making planned. */
    private fun whole(source: String, reason: String) {
        val type = stores.typeOf(source).id
        tasks.keys.want(work(type), listOf("$source$SEP$WHOLE"))
        plan(type, reason)
    }

    /** The sets of the works [ids] of the [source] may have grown: kept waiting, their making planned. */
    private fun grew(source: String, ids: Collection<String>, reason: String) {
        if (ids.isEmpty()) return
        val type = stores.typeOf(source).id
        tasks.keys.want(work(type), ids.map { "$source$SEP$it" })
        plan(type, reason, ids.size)
    }

    private fun plan(type: String, reason: String, count: Int = 1) =
        tasks.plan(work(type), Instant.now().plus(QUIET), reason, count) { made(type) }

    /**
     * The vectors of the sets of the works waiting made, a [PORTION] of each source and kind at most: of a tail left,
     * the work planned again after it, the works waiting kept ([Tasks.again]) — the sets made already its bound.
     */
    private fun made(typeId: String) {
        val type = stores.type(typeId) ?: return
        val work = work(typeId)
        val taken = tasks.keys.wanted(work)
        if (taken.isEmpty()) return
        val asked = taken.groupBy({ it.substringBefore(SEP) }, { it.substringAfter(SEP) })
        val turn = Turn()
        SetKind.entries.forEach { kind ->
            val encoder = sets.encoderOf(kind) ?: return@forEach
            // The sizes of the sets read once, for the directions and for what grew alike
            val sizes = type.sources.associateWith { sets.sizes(it, kind, encoder) }
            val embedding = directions.inUse(type, kind, encoder, sizes) ?: return@forEach
            type.sources.forEach { s ->
                asked[s.id]?.let { ids -> made(s, kind, embedding, sizes[s].orEmpty(), ids, turn) }
            }
        }
        // The model learns the new vectors as they come, a portion at a time
        if (turn.made > 0) events.publishEvent(SiteItemSetVectorChangeEvent(typeId, turn.made))
        if (turn.tail) tasks.again("portion") else tasks.keys.done(work, taken)
    }

    /** What a turn of the work made, and whether it left a tail. */
    private class Turn {
        var made = 0
        var tail = false
    }

    /**
     * Of the source [s], the vectors of the sets of [kind] of the works [ids] waiting — of every one of [WHOLE] — whose
     * set grew past its vector made, a [PORTION] at most, the likeliest works first.
     */
    private fun made(
        s: SourceStore,
        kind: SetKind,
        embedding: StoredEmbedding,
        all: Map<String, Int>,
        ids: List<String>,
        turn: Turn,
    ) {
        val have = if (WHOLE in ids) all else ids.mapNotNull { id -> all[id]?.let { id to it } }.toMap()
        val stored = if (have.isEmpty()) emptyMap() else s.setVectors.sources(kind, embedding.id)
        val todo = have.filter { (id, n) -> n > 0 && stored[id] != n }.keys
        if (todo.isEmpty()) return
        val ordered = contexts.of(s.id).itemsInOrder().filter { it in todo }
        ordered.take(PORTION).chunked(CHUNK).forEach { chunk ->
            save(s, kind, embedding, have, chunk)
            turn.made += chunk.size
        }
        turn.tail = turn.tail || ordered.size > PORTION
        log.info(
            "{}: vectors of the {} of {} of {} works made",
            s.id,
            kind.key,
            minOf(PORTION, ordered.size),
            ordered.size,
        )
    }

    /** The vectors of the sets of [kind] of the items [ids] made and kept, each with the size [have] of its set. */
    private fun save(
        s: SourceStore,
        kind: SetKind,
        embedding: StoredEmbedding,
        have: Map<String, Int>,
        ids: List<String>,
    ) {
        val made = sets.members(s, kind, embedding.encoder, ids)
        ids.forEach { id ->
            embedding.embedding.of(made[id].orEmpty())?.let {
                s.setVectors.save(id, kind, embedding.id, have.getValue(id), it)
            }
        }
    }

    companion object {
        private const val CHUNK = 200

        /** The sets made by one turn of the work, of a source and a kind: minutes, a grade and a training between. */
        private const val PORTION = 2_000

        /** The quiet after a change before the sets are made: the changes of a crawl's pages at once. */
        private val QUIET: Duration = Duration.ofSeconds(30)

        /** The key of a work waiting in the queue: its source and its id; of every work of the source, [WHOLE]. */
        private const val SEP = '\t'
        private const val WHOLE = ""

        private fun work(type: String) = Task(TaskKind.MAKE_SITE_ITEM_SET_VECTORS, type = type)
    }
}
