package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.ModelAutoChanges
import io.github.vlsergey.recommend4me.part.SiteItemChapterChangeEvent
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.NextWorks
import io.github.vlsergey.recommend4me.worklist.StageSweeps
import io.github.vlsergey.recommend4me.worklist.WorkDatum
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import io.github.vlsergey.recommend4me.worklist.markedAmong
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Manners::class.java)

/**
 * The manners of this many items the models of the grades learn of were made again: the models may want to learn them.
 */
data class WorkMannerChangeEvent(val type: String, val items: Int)

/**
 * Every change of the manners of the works of the [scope] of the [type] made ([WorkStage.MANNERS]): the stages reading
 * them, or waiting for them, look again.
 */
data class WorkMannerFillEvent(val type: String, val scope: WorkScope)

/**
 * THE MANNERS OF THE TEXTS — THE STAGE [WorkStage.MANNERS] OF THE TASKS OVER THE WORKS ([StageSweeps]): of the works
 * whose chapters, data or items changed past its mark, the manner of every item made by the words of the content type
 * where its texts changed ([ItemManners]), and of the works whose manners changed so those of their authors and series
 * ([MannerGroups]) — by their keys, no other work read; the groups whose lists of works changed made by
 * [GroupMannerChanges] —; a task of the marked and rated works, and one of the others after every other task, a
 * [PORTION] of them a turn, the highest expected first; every work marked done, those whose manners changed dated so,
 * of every item of their works and of their groups' works: the expectations read them. The words ([MannerWords]) are
 * fitted on every text of the type by a task of their own ([fit]) — of none kept, or as the items whose chapters
 * changed come to a hundredth of those they were fitted on ([chaptersWritten]) —, every manner then made again by them:
 * the stage marked at nought.
 */
@Component
class Manners(
    private val stores: Stores,
    private val tasks: Tasks,
    private val lists: ListIndexes,
    private val sweeps: StageSweeps,
    private val events: ApplicationEventPublisher,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start, from where it
    // was to go on
    init {
        tasks.resumption.resumes(MAKING) { left, reason ->
            val type = left.work.type ?: return@resumes
            val scopes = left.work.works?.let(::listOf) ?: WorkScope.entries
            scopes.forEach { plan(type, it, reason, left.cursor ?: TaskCursor.FIRST) }
        }
        tasks.resumption.resumes(FITTING) { left, reason ->
            left.work.type?.let(stores::type)?.let { planFit(it, reason) }
        }
    }

    /** The documents of works written anew, of what their manners are made of changed: their manners made. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) {
        if (STAGE in event.stages) WorkScope.entries.forEach { plan(event.type, it, "items", TaskCursor.FIRST) }
    }

    /**
     * The chapters of items written: counted beside the words ([ModelAutoChanges]), fitted again once they come to a
     * hundredth of the items with a text they were fitted on (owner, 2026-11-25, of the models: "accumulated --
     * scheduled the update") — not of every new chapter: every text of the type read again, half an hour of ficbook's
     * —; of no words yet, at the first.
     */
    @EventListener
    fun chaptersWritten(event: SiteItemChapterChangeEvent) {
        val type = stores.typeOf(event.source)
        if (type.autoChanges.count(ModelAutoChanges.MANNER, event.ids.size)) planFit(type, "catalogueGrew")
    }

    /** The manners of the works of the [scope] of the [typeId] planned, for the [reason], from the [cursor]. */
    fun plan(typeId: String, scope: WorkScope, reason: String, cursor: TaskCursor) =
        tasks.plan(
            Task(MAKING, type = typeId, works = scope),
            null,
            reason,
            priority = NextWorks.priority(scope),
            cursor = cursor,
        ) {
            turn(typeId, scope, tasks.running()?.cursor ?: TaskCursor.FIRST)?.let { tasks.again("sweep", cursor = it) }
        }

    /**
     * ONE TURN: the next [portion] works of the [scope] of the [typeId] whose manners are to be made, from the [cursor]
     * on, made; where the next turn goes on from — none when none is left.
     */
    internal fun turn(typeId: String, scope: WorkScope, cursor: TaskCursor, portion: Int = PORTION): TaskCursor? {
        val type = stores.type(typeId) ?: return null
        val taken = sweeps.next(STAGE, typeId, type.sources.map { it.id }, scope, cursor, portion)
        if (taken.isNotEmpty()) {
            val keys = taken.map { it.key }
            // Of no words yet, nothing to measure by: the works done, made again of the words once fitted
            type.mannerWords.find()?.let { words -> made(type, scope, words, keys) }
            sweeps.done(STAGE, typeId, scope, keys)
            lists.of(typeId).commit()
        }
        if (taken.size < portion && sweeps.complete(STAGE, typeId, scope))
            events.publishEvent(WorkMannerFillEvent(typeId, scope))
        return taken.lastOrNull()?.cursor()?.takeIf { taken.size == portion }
    }

    /** The manners of the items [keys] and of their works' groups made by the [words], and those changed dated. */
    private fun made(type: TypeStore, scope: WorkScope, words: MannerWords, keys: List<ItemKey>) {
        val started = System.currentTimeMillis()
        val items = ItemManners.made(type, words, keys)
        // A work's manner is of all its items: every one of a work whose item's changed
        val works = WorkClusters.of(type.links.groupsOf(items))
        val own = items.flatMap(works::members).distinct()
        sweeps.made(STAGE, type.id, scope, own)
        // The authors and the series of the works whose manners changed: theirs made anew, their works dated so
        val groups = MannerGroups.refresh(type, words.id, MannerGroups.ofItems(type, items))
        if (groups.dated.isNotEmpty()) type.works.changed(groups.dated, WorkDatum.GROUPS, Instant.now())
        // Of the works the models of the grades learn of alone: the manners of the others train them nothing
        val learnt = markedAmong(lists.of(type.id), own + groups.dated)
        if (learnt.isNotEmpty()) events.publishEvent(WorkMannerChangeEvent(type.id, learnt.size))
        if (items.isNotEmpty() || groups.groups > 0) {
            log.info(
                "{}: of {} works the manners of {} items and of {} authors and series made or taken away, in {} ms",
                type.id,
                keys.size,
                items.size,
                groups.groups,
                System.currentTimeMillis() - started,
            )
        }
    }

    /** How many items of the [type] have a text: counted by the databases. */
    private fun itemsWithText(type: TypeStore): Int = type.sources.sumOf { it.parts.marks.itemsWithText() }

    /** The words of the manners of the [type] fitted anew, for the [reason]. */
    private fun planFit(type: TypeStore, reason: String) =
        tasks.plan(Task(FITTING, type = type.id), null, reason, priority = TaskPriority.BACKGROUND) { fit(type) }

    /**
     * The words of the [type] fitted on every text of it, kept, and the stage marked at nought: every manner made again
     * by them. Two passes over every text: the words chosen of the first, their means and spreads of the second.
     */
    internal fun fit(type: TypeStore) {
        val started = System.currentTimeMillis()
        val count = itemsWithText(type)
        val counted = type.autoChanges.of(ModelAutoChanges.MANNER)?.changes ?: 0
        if (count == 0) return
        val tally = WordTally()
        type.sources.forEach { s -> s.parts.texts.forEach { _, own -> tally.add(own) } }
        val chosen = tally.chosen(MannerWords.SIZE)
        // The shares of the chosen words in every text, their means and spreads not known yet: of a draft of them
        val draft = MannerWords(0, chosen, FloatArray(chosen.size), FloatArray(chosen.size) { 1f }, 0)
        val moments = ShareMoments(chosen)
        type.sources.forEach { s -> s.parts.texts.forEach { _, own -> draft.shares(own)?.let(moments::add) } }
        val words = moments.fitted(Vectors.keyOf("${type.id}:manner:${System.nanoTime()}"), count)
        type.mannerWords.keep(words, Instant.now())
        type.autoChanges.learnt(ModelAutoChanges.MANNER, count)
        type.autoChanges.letGo(ModelAutoChanges.MANNER, counted)
        sweeps.reset(STAGE, type.id, type.sources.map { it.id })
        WorkScope.entries.forEach { plan(type.id, it, "manner", TaskCursor.FIRST) }
        log.info(
            "{}: the words of the manners fitted on {} items with a text in {} ms",
            type.id,
            count,
            System.currentTimeMillis() - started,
        )
    }

    companion object {
        val MAKING = TaskKind.MAKE_WORK_MANNERS

        val FITTING = TaskKind.FIT_MANNER_WORDS

        /** The stage of the tasks over the works it is. */
        val STAGE = WorkStage.MANNERS

        /** The works a turn takes (owner, 2026-11-19: "each of the tasks does at most N operations, say, 1000"). */
        const val PORTION = 1000
    }
}
