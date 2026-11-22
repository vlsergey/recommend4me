package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.part.WorkChapterWindowFillEvent
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.textvector.WorkTextVectorFillEvent
import io.github.vlsergey.recommend4me.worklist.ListIndex
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.NextWorks
import io.github.vlsergey.recommend4me.worklist.StageSweeps
import io.github.vlsergey.recommend4me.worklist.TakenWork
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(WorkTexts::class.java)

/**
 * THE DOCUMENTS OF THE SEARCH OF THE WORKS — THE STAGE [WorkStage.TEXTS] ([StageSweeps]; docs/computations.md, 9 and
 * 10): of the works whose data, answers, chapters, vectors or chapters' windows changed past its mark — the vectors and
 * the windows done of them first —; a task of the marked and rated works, and one of the others after every other task,
 * a [PORTION] of them a turn ([turn]) — the highest expected first, from where the last turn left —: their documents
 * brought into the search plugin's index ([SourceCatchUp]: of the site's and the user's values alone, a document whose
 * fingerprint did not change not written again), every one marked done. An index of the plugin not of its format, or
 * never brought to every work ([SearchProvider.current]), is made anew empty as the task begins, and every work's
 * document written again since.
 */
@Component
class WorkTexts(
    private val stores: Stores,
    private val plugins: Plugins,
    private val documents: SearchDocuments,
    private val tasks: Tasks,
    private val lists: ListIndexes,
    private val sweeps: StageSweeps,
) {
    private val sources = SourceCatchUp(documents)

    // What the last run of the application left undone of it: planned again by the queue at its start, from where it
    // was to go on
    init {
        tasks.resumption.resumes(KIND) { left, reason ->
            val type = left.work.type ?: return@resumes
            val scope = left.work.works ?: return@resumes
            plan(type, scope, reason, left.cursor ?: TaskCursor.FIRST)
        }
    }

    /**
     * Of every type whose index of the search is not of the plugin's format, or not brought to every work, every work's
     * document written again — what the data say, nothing for the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val provider = plugins.search ?: return
        stores.types
            .filter { !provider.current(it.id) }
            .forEach { type -> WorkScope.entries.forEach { plan(type.id, it, "searchFormat", TaskCursor.FIRST) } }
    }

    /** The documents of works written anew, of what the search reads changed: their documents of the search written. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) {
        if (STAGE in event.stages) WorkScope.entries.forEach { plan(event.type, it, "items", TaskCursor.FIRST) }
    }

    /** The vectors of the texts the search waits for done: the documents of the works they got past written. */
    @EventListener
    fun vectorsFilled(event: WorkTextVectorFillEvent) = plan(event.type, event.scope, "texts", TaskCursor.FIRST)

    /** The chapters' windows the search waits for done: the documents of the works they got past written. */
    @EventListener
    fun windowsFilled(event: WorkChapterWindowFillEvent) = plan(event.type, event.scope, "parts", TaskCursor.FIRST)

    /** The documents of the works of the [scope] of the [typeId] planned, for the [reason], from the [cursor] on. */
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
     * Every document of the search of the [typeId] to be written written now, on the caller's thread: for the tests.
     */
    fun indexNow(typeId: String) =
        WorkScope.entries.forEach { scope ->
            var cursor: TaskCursor? = TaskCursor.FIRST
            while (cursor != null) cursor = turn(typeId, scope, cursor)
        }

    /**
     * ONE TURN: the next [portion] works of the [scope] of the [typeId] whose documents are to be written, from the
     * [cursor] on, written; where the next turn goes on from — none when none is left.
     */
    internal fun turn(typeId: String, scope: WorkScope, cursor: TaskCursor, portion: Int = PORTION): TaskCursor? {
        val provider = plugins.search
        val type = stores.type(typeId)
        if (provider == null || type == null) return null
        val index = lists.of(typeId)
        if (!provider.current(typeId)) {
            // Made anew empty: every work's document written again — the stage marked at nought first, so a stop
            // between the two leaves it to be done of every work
            sweeps.reset(STAGE, typeId, type.sources.map { it.id })
            provider.rebuild(typeId, emptySequence())
        }
        val items = sweeps.next(STAGE, typeId, type.sources.map { it.id }, scope, cursor, portion)
        if (items.isNotEmpty()) written(provider, type, index, scope, items)
        if (items.size < portion) sweeps.complete(STAGE, typeId, scope)
        return items.lastOrNull()?.cursor()?.takeIf { items.size == portion }
    }

    /** The documents of the [items] of the [scope] of the [type] brought into the index of the search. */
    private fun written(
        provider: SearchProvider,
        type: TypeStore,
        index: ListIndex,
        scope: WorkScope,
        items: List<TakenWork>,
    ) {
        val started = System.currentTimeMillis()
        val written =
            items.groupBy({ it.key.source }, { it.key }).entries.sumOf { (source, keys) ->
                val s = type.source(source) ?: return@sumOf 0
                sources.written(provider, type.id, s, keys)
            }
        sweeps.done(STAGE, type.id, scope, items.map { it.key })
        index.commit()
        log.info(
            "{}: the documents of the search of {} {} works brought in in {} ms, {} of them written again",
            type.id,
            items.size,
            scope.value,
            System.currentTimeMillis() - started,
            written,
        )
    }

    companion object {
        /** The task of the queue writing the works' documents of the search. */
        val KIND = TaskKind.INDEX_WORK_TEXTS

        /** The works a turn takes (owner, 2026-11-19: "each of the tasks does at most N operations, say, 1000"). */
        const val PORTION = 1000

        /** The stage of the tasks over the works it is. */
        private val STAGE = WorkStage.TEXTS
    }
}
