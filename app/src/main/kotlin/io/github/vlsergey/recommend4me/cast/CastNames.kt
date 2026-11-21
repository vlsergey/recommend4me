package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.NextWorks
import io.github.vlsergey.recommend4me.worklist.StageSweeps
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(CastNames::class.java)

/** The texts of the casts of these works of a source were encoded: the models that read them read them anew. */
data class SiteItemCastEncodeEvent(val source: String, val ids: List<String>)

/**
 * Every change of the casts of the works of the [scope] of the [type] encoded ([WorkStage.CAST]): the stages reading
 * their names, or waiting for them, look again.
 */
data class WorkCastNameFillEvent(val type: String, val scope: WorkScope)

/**
 * THE TEXTS OF THE SAID CASTS ENCODED — THE STAGE [WorkStage.CAST] OF THE TASKS OVER THE WORKS ([StageSweeps]): of the
 * works whose data changed past its mark, the texts every way of reading the casts asks for ([CastReader.encoded]) —
 * the names, the pairings written so — encoded and kept as the short texts are ([PhraseVectors]), a text once; a task
 * of the marked and rated works, and one of the others after every other task, a [PORTION] of them a turn ([turn]) —
 * the highest expected first, from where the last turn left —; every work marked done, those of texts encoded anew
 * dated so: the fields read them ([CastReading]). In the queue of the computations ([Tasks]), never on the thread of a
 * request.
 */
@Component
class CastNames(
    private val stores: Stores,
    private val plugins: Plugins,
    private val phrases: PhraseVectors,
    private val tasks: Tasks,
    private val lists: ListIndexes,
    private val sweeps: StageSweeps,
    private val events: ApplicationEventPublisher,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start, from where it
    // was to go on
    init {
        tasks.resumption.resumes(KIND) { left, reason ->
            val type = left.work.type ?: return@resumes
            val scopes = left.work.works?.let(::listOf) ?: WorkScope.entries
            scopes.forEach { plan(type, it, reason, left.cursor ?: TaskCursor.FIRST) }
        }
    }

    /** The documents of works written anew, of their data changed: the texts of their casts encoded. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) {
        if (event.anew || STAGE.follows(event.data))
            WorkScope.entries.forEach {
                plan(event.type, it, if (event.anew) "listFormat" else "items", TaskCursor.FIRST)
            }
    }

    /**
     * The texts of the casts of the works of the [scope] of the [typeId] planned, for the [reason], from the [cursor].
     */
    fun plan(typeId: String, scope: WorkScope, reason: String, cursor: TaskCursor) =
        tasks.plan(
            Task(KIND, type = typeId, works = scope),
            null,
            reason,
            priority = NextWorks.priority(scope),
            cursor = cursor,
        ) {
            turn(typeId, scope, tasks.running()?.cursor ?: TaskCursor.FIRST)?.let { tasks.again("sweep", cursor = it) }
        }

    /**
     * ONE TURN: the next [portion] works of the [scope] of the [typeId] whose casts' texts are to be encoded, from the
     * [cursor] on, encoded; where the next turn goes on from — none when none is left.
     */
    internal fun turn(typeId: String, scope: WorkScope, cursor: TaskCursor, portion: Int = PORTION): TaskCursor? {
        val type = stores.type(typeId) ?: return null
        val items = sweeps.next(STAGE, typeId, type.sources.map { it.id }, scope, cursor, portion)
        if (items.isNotEmpty()) {
            val made =
                items.groupBy({ it.key.source }, { it.key.id }).flatMap { (source, ids) ->
                    val s = stores.source(source)?.takeIf(::encodes) ?: return@flatMap emptyList()
                    encode(s, ids)
                        .also { if (it.isNotEmpty()) events.publishEvent(SiteItemCastEncodeEvent(source, it)) }
                        .map { ItemKey(source, it) }
                }
            sweeps.done(STAGE, typeId, scope, items.map { it.key })
            sweeps.made(STAGE, typeId, scope, made)
            lists.of(typeId).commit()
        }
        if (items.size < portion && sweeps.complete(STAGE, typeId, scope))
            events.publishEvent(WorkCastNameFillEvent(typeId, scope))
        return items.lastOrNull()?.cursor()?.takeIf { items.size == portion }
    }

    /** Whether a way of reading asks for texts of the source's casts at all: it has a line, and a way is installed. */
    private fun encodes(store: SourceStore): Boolean =
        WorkCasts.facetOf(store) != null && plugins.castReaders.isNotEmpty()

    /**
     * The texts of the casts of the works [ids] of the [store] not encoded yet, encoded now; the works that had any.
     */
    private fun encode(store: SourceStore, ids: Collection<String>): List<String> {
        val facet = WorkCasts.facetOf(store) ?: return emptyList()
        val entries = store.items.facetsOf(ids).mapValues { it.value[facet].orEmpty() }
        val texts =
            WorkCasts.of(store, facet, entries).mapValues { (_, cast) ->
                plugins.castReaders.flatMap { it.encoded(cast) }.distinct()
            }
        val missing = phrases.missing(store, texts.values.flatten())
        if (missing.isNotEmpty()) {
            val started = System.currentTimeMillis()
            phrases.of(store, missing)
            log.info(
                "{}: {} names of the casts of {} works encoded in {} ms",
                store.id,
                missing.size,
                entries.size,
                System.currentTimeMillis() - started,
            )
        }
        val fresh = missing.toHashSet()
        return texts.filterValues { list -> list.any { it in fresh } }.keys.toList()
    }

    private companion object {
        val KIND = TaskKind.ENCODE_SITE_ITEM_CASTS

        /** The works a turn takes (owner, 2026-11-19: "each of the tasks does at most N operations, say, 1000"). */
        const val PORTION = 1000

        /** The stage of the tasks over the works it is. */
        val STAGE = WorkStage.CAST
    }
}
