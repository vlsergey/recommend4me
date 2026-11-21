package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.part.WorkChapterWindowFillEvent
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.NextWorks
import io.github.vlsergey.recommend4me.worklist.StageSweeps
import io.github.vlsergey.recommend4me.worklist.TakenWork
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(SetVectorMaking::class.java)

/**
 * Every change the sets read of the works of the [scope] of the [type] done ([WorkStage.SETS]): the stages reading
 * them, or waiting for them, look again.
 */
data class WorkSetVectorFillEvent(val type: String, val scope: WorkScope)

/**
 * THE MAKING OF THE SETS' VECTORS ([SetVectors]) — THE STAGE [WorkStage.SETS] OF THE TASKS OVER THE WORKS
 * ([StageSweeps]; owner, 2026-11-21: "a self-evident decomposition of dependencies"): of the works whose chapters'
 * windows, reviews or pictures changed past its mark — the windows done of them first —, of every work of a type whose
 * directions are of another encoder than the one in use ([start]); a task of the marked and rated works, and one of the
 * others after every other task, a [PORTION] of them a turn ([turn]) — the highest expected first, from where the last
 * turn left —: of every kind of set, each work's set made of its members now and kept, every work marked done, those
 * whose set was made dated so: the fields and the expectations read them.
 *
 * THE DIRECTIONS ARE FITTED ONCE per content type, on a sample of its catalogue, and kept ([SetDirections]); the
 * members of the sets are read by [SetMembers].
 */
@Service
class SetVectorMaking(
    private val stores: Stores,
    private val sets: SetMembers,
    private val directions: SetDirections,
    private val events: ApplicationEventPublisher,
    private val tasks: Tasks,
    private val lists: ListIndexes,
    private val sweeps: StageSweeps,
) {
    /** Whether the vectors of some type's sets are to be made. */
    fun running(): Boolean = stores.types.any { type -> WorkScope.entries.any { tasks.due(work(type.id, it)) } }

    // What the last run of the application left undone of it: planned again by the queue at its start, from where it
    // was to go on
    init {
        tasks.resumption.resumes(KIND) { left, reason ->
            val type = left.work.type ?: return@resumes
            val scopes = left.work.works?.let(::listOf) ?: WorkScope.entries
            scopes.forEach { plan(type, it, reason, left.cursor ?: TaskCursor.FIRST) }
        }
    }

    /** The documents of works written anew, of what their sets are made of changed: their sets made. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) {
        if (STAGE in event.stages)
            WorkScope.entries.forEach {
                plan(event.type, it, if (event.anew) "listFormat" else "items", TaskCursor.FIRST)
            }
    }

    /** The chapters' windows the sets wait for done: the sets of the works they got past made. */
    @EventListener
    fun windowsFilled(event: WorkChapterWindowFillEvent) = plan(event.type, event.scope, "parts", TaskCursor.FIRST)

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
            sweeps.reset(STAGE, type.id, type.sources.map { it.id })
            WorkScope.entries.forEach { plan(type.id, it, "setsGrew", TaskCursor.FIRST) }
        }

    /** The sets of the works of the [scope] of the [typeId] planned, for the [reason], from the [cursor] on. */
    fun plan(typeId: String, scope: WorkScope, reason: String, cursor: TaskCursor) =
        tasks.plan(work(typeId, scope), null, reason, priority = NextWorks.priority(scope), cursor = cursor) {
            turn(typeId, scope, tasks.running()?.cursor ?: TaskCursor.FIRST)?.let { tasks.again("sweep", cursor = it) }
        }

    /**
     * ONE TURN: the next [portion] works of the [scope] of the [typeId] whose sets are to be made, from the [cursor]
     * on, made; where the next turn goes on from — none when none is left.
     */
    internal fun turn(typeId: String, scope: WorkScope, cursor: TaskCursor, portion: Int = PORTION): TaskCursor? {
        val type = stores.type(typeId) ?: return null
        val items = sweeps.next(STAGE, typeId, type.sources.map { it.id }, scope, cursor, portion)
        if (items.isNotEmpty()) made(type, scope, items)
        if (items.size < portion && sweeps.complete(STAGE, typeId, scope))
            events.publishEvent(WorkSetVectorFillEvent(typeId, scope))
        return items.lastOrNull()?.cursor()?.takeIf { items.size == portion }
    }

    /** The sets of every kind of the [items] of the [scope] of the [type] made and kept, dated so; committed, told. */
    private fun made(type: TypeStore, scope: WorkScope, items: List<TakenWork>) {
        val started = System.currentTimeMillis()
        val made = HashSet<ItemKey>()
        val asked = items.groupBy({ it.key.source }, { it.key.id })
        SetKind.entries.forEach { kind ->
            val encoder = sets.encoderOf(kind) ?: return@forEach
            // The sizes of the sets read once, for the directions and for the works made alike
            val sizes = type.sources.associateWith { sets.sizes(it, kind, encoder) }
            val embedding = directions.inUse(type, kind, encoder, sizes) ?: return@forEach
            type.sources.forEach { s ->
                asked[s.id]?.let { ids -> made += made(s, kind, embedding, sizes[s].orEmpty(), ids) }
            }
        }
        sweeps.done(STAGE, type.id, scope, items.map { it.key })
        sweeps.made(STAGE, type.id, scope, made)
        lists.of(type.id).commit()
        // The model learns the new vectors as they come, a portion at a time
        if (made.isNotEmpty()) events.publishEvent(SiteItemSetVectorChangeEvent(type.id, made.size))
        log.info(
            "{}: the sets of {} {} works made in {} ms, {} of them of members",
            type.id,
            items.size,
            scope.value,
            System.currentTimeMillis() - started,
            made.size,
        )
    }

    /** Of the source [s], the vectors of the sets of [kind] of the works [ids] that have members made: those works. */
    private fun made(
        s: SourceStore,
        kind: SetKind,
        embedding: StoredEmbedding,
        all: Map<String, Int>,
        ids: List<String>,
    ): List<ItemKey> {
        val have = ids.mapNotNull { id -> all[id]?.takeIf { it > 0 }?.let { id to it } }.toMap()
        have.keys.chunked(CHUNK).forEach { chunk -> save(s, kind, embedding, have, chunk) }
        return have.keys.map { ItemKey(s.id, it) }
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

        /** The works a turn takes (owner, 2026-11-19: "each of the tasks does at most N operations, say, 1000"). */
        private const val PORTION = 1000

        /** The task of the queue making the works' sets' vectors. */
        private val KIND = TaskKind.MAKE_SITE_ITEM_SET_VECTORS

        /** The stage of the tasks over the works it is. */
        private val STAGE = WorkStage.SETS

        private fun work(type: String, scope: WorkScope) = Task(KIND, type = type, works = scope)
    }
}
