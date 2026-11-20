package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.TaskCursor
import io.github.vlsergey.recommend4me.task.Tasks
import io.github.vlsergey.recommend4me.worklist.ListIndex
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.NextWorks
import io.github.vlsergey.recommend4me.worklist.WorkDocumentWriteEvent
import io.github.vlsergey.recommend4me.worklist.WorkStage
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(TextVectors::class.java)

/** The vectors of the texts of these items of a source were written or dropped. */
data class SiteItemTextVectorChangeEvent(val source: String, val ids: List<String>)

/**
 * The vectors of the site's text fields made of works of the [type], from where of each of the works ([from]): their
 * fields computed next ([WorkStage.FIELDS]).
 */
data class WorkTextVectorFillEvent(val type: String, val from: Map<WorkScope, TaskCursor>)

/**
 * THE VECTORS OF THE SITE'S TEXT FIELDS OF THE WORKS — THE FIRST OF THE TASKS OVER THE WORKS (owner, 2026-11-19: "we
 * split the vectors into the marked works and the other works"; docs/computations.md, 9 and 10): of every text the
 * models read ([TextDef.block][io.github.vlsergey.recommend4me.source.TextDef.block]), those searched by meaning, and
 * the title ([TITLE]), of the text as the user corrected it, kept with its hash and the encoder that made them — a text
 * is encoded again when it or the encoder changed. Of the works whose documents of the list were written anew
 * ([WorkDocumentWriteEvent]), of every work by an encoder of the index never seen ([start]); a task of the marked and
 * rated works, and one of the others after every other task, a [PORTION] of them a turn ([turn]) — the highest expected
 * first, from where the last turn left —: the texts new or changed encoded, those gone dropped, every one marked done
 * ([WorkStage.VECTORS]), the fields of those whose vectors changed to be computed again; told
 * ([WorkTextVectorFillEvent]).
 *
 * A WORK THE USER OPENS — its card or its page — has its texts encoded on the spot ([encodeOwn]).
 */
@Service
class TextVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val tasks: Tasks,
    private val windows: TextWindows,
    private val lists: ListIndexes,
) {
    /** Whether texts are being encoded now. */
    val running: Boolean
        get() = busy

    @Volatile private var busy = false

    // What the last run of the application left undone of it: planned again by the queue at its start, from where it
    // was to go on
    init {
        tasks.resumption.resumes(KIND) { left, reason ->
            val type = left.work.type ?: return@resumes
            plan(type, left.work.works ?: return@resumes, reason, left.cursor ?: TaskCursor.FIRST)
        }
    }

    /**
     * Of every type whose index of the list has its vectors by another encoder than the one of now, every work's texts
     * encoded again — what the data say, nothing for the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val encoder = plugins.textEncoder() ?: return
        stores.types
            .filter { !madeBy(lists.of(it.id), encoder) }
            .forEach { type -> WorkScope.entries.forEach { plan(type.id, it, "encoder", TaskCursor.FIRST) } }
    }

    /** The documents of works written anew: their texts encoded from the first of them on. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) =
        event.from.forEach { (scope, from) -> plan(event.type, scope, if (event.anew) "listFormat" else "items", from) }

    /** The texts of the works of the [scope] of the [typeId] planned, for the [reason], from the [cursor] on. */
    fun plan(typeId: String, scope: WorkScope, reason: String, cursor: TaskCursor) =
        tasks.plan(
            Task(KIND, type = typeId, works = scope),
            null,
            reason,
            priority = NextWorks.priority(scope),
            cursor = cursor,
        ) {
            turn(typeId, scope, tasks.running()?.cursor ?: TaskCursor.FIRST)?.let {
                tasks.again(if (tasks.outranked) "yielded" else "sweep", cursor = it)
            }
        }

    /** Every text of the [typeId] to be encoded encoded now, on the caller's thread, turn after turn: for the tests. */
    fun encodeNow(typeId: String) =
        WorkScope.entries.forEach { scope ->
            var cursor: TaskCursor? = TaskCursor.FIRST
            while (cursor != null) cursor = turn(typeId, scope, cursor)
        }

    /**
     * ONE TURN: the next [portion] works of the [scope] of the [typeId] whose texts are to be encoded, from the
     * [cursor] on, encoded; where the next turn goes on from — none when none is left. Of no encoder, none: the next
     * task told alike. Stopped between its batches for a work the user asked for ([Tasks.outranked]), none of its works
     * marked done: it goes on from the [cursor] again, the texts it encoded found encoded.
     */
    internal fun turn(typeId: String, scope: WorkScope, cursor: TaskCursor, portion: Int = PORTION): TaskCursor? {
        val type = stores.type(typeId)
        val encoder = plugins.textEncoder()
        val index = lists.of(typeId)
        val items =
            if (type == null || encoder == null) null
            else
                NextWorks.next(
                    index,
                    scope,
                    NextWorks.before(WorkStage.VECTORS, vectorsSince(index, encoder)),
                    cursor,
                    portion,
                )
        when {
            items == null -> events.publishEvent(WorkTextVectorFillEvent(typeId, mapOf(scope to cursor)))
            items.isNotEmpty() -> {
                if (!done(type!!, index, encoder!!, items.map { it.key })) return cursor
                events.publishEvent(WorkTextVectorFillEvent(typeId, mapOf(scope to items.first().cursor())))
            }
        }
        return items?.lastOrNull()?.cursor()?.takeIf { items.size == portion }
    }

    /**
     * Of the items [keys] of the [type], the texts new or changed encoded by the [encoder], those gone dropped, every
     * item marked done, the fields of those whose vectors changed to be computed again; committed. False, nothing
     * marked done, of a turn that gave way to a work the user asked for ([Tasks.outranked]).
     */
    private fun done(type: TypeStore, index: ListIndex, encoder: TextEncoder, keys: List<ItemKey>): Boolean {
        val started = System.currentTimeMillis()
        busy = true
        val changed =
            try {
                keys.groupBy({ it.source }, { it.id }).flatMap { (source, ids) ->
                    type.source(source)?.let { encoded(it, encoder, ids) { tasks.outranked } }.orEmpty()
                }
            } finally {
                busy = false
            }
        // Of a turn that gave way, the works not marked done — met again, their texts found encoded — but the fields
        // of those encoded anew to be computed again all the same: the next turn finds them unchanged
        val gaveWay = tasks.outranked
        if (!gaveWay) WorkStage.VECTORS.done(index, keys, started)
        WorkStage.FIELDS.again(index, changed)
        index.commit()
        log.info(
            "{}: the texts of {} works held, of {} encoded anew, in {} ms{}",
            type.id,
            keys.size,
            changed.size,
            System.currentTimeMillis() - started,
            if (gaveWay) ", given way to a work asked for" else "",
        )
        return !gaveWay
    }

    /**
     * The texts of the items [ids] of the source [store] encoded by [encoder] where new or changed, and kept with their
     * windows, a batch at a time; those gone dropped. The items whose vectors changed.
     */
    private fun encoded(
        store: SourceStore,
        encoder: TextEncoder,
        ids: List<String>,
        giveWay: () -> Boolean = { false },
    ): List<ItemKey> {
        val scan = TextScan(store, ids, encoder.id)
        val gone = scan.gone()
        gone.forEach { (id, key) -> store.textVectors.delete(id, key) }
        if (gone.isNotEmpty())
            events.publishEvent(SiteItemTextVectorChangeEvent(store.id, gone.map { it.first }.distinct()))
        for (batch in scan.todo.chunked(BATCH)) {
            if (giveWay()) break
            val vectors = encoder.encode(batch.map { it.text })
            batch.forEachIndexed { i, t ->
                vectors[i]?.let { store.textVectors.save(t.itemId, t.key, encoder.id, t.hash, it) }
            }
            windows.keepWindows(
                store,
                batch.filterIndexed { i, _ -> vectors[i] != null }.map { Triple(it.itemId, it.key, it.text) },
            )
            events.publishEvent(SiteItemTextVectorChangeEvent(store.id, batch.map { it.itemId }.distinct()))
        }
        return (scan.todo.map { it.itemId } + gone.map { it.first }).distinct().map { ItemKey(store.id, it) }
    }

    /**
     * THE TEXTS OF A WORK THE USER OPENS, ENCODED ON THE SPOT (owner, 2026-11-19: "when we open the page, I want to
     * begin marking; as I mark, the chances change all the time; so I need a method that computes the vectors if there
     * are none yet and keeps them"): the texts new or changed of the [items] encoded now, on the caller's thread — a
     * request's —, and kept, those gone dropped; the work's chances and expectation made of them at once. The second
     * computation of a request ([Requests][io.github.vlsergey.recommend4me.task.Requests]): a work's few texts. The
     * stages of the index of the list are the background's: it finds them encoded.
     */
    fun encodeOwn(items: Collection<ItemKey>) {
        val encoder = plugins.textEncoder() ?: return
        items.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            stores.source(source)?.let { encoded(it, encoder, ids) }
        }
    }

    /**
     * The vector of a search line as a query; null without a text encoder. THE ONE COMPUTATION ON THE THREAD OF A
     * REQUEST ([io.github.vlsergey.recommend4me.task.Requests]): the search of the line asked for is the answer itself,
     * there is nothing to answer before it; one short line through the encoder, milliseconds, out of the queue.
     */
    fun query(line: String): FloatArray? =
        plugins.textEncoder()?.encode(listOf(line), io.github.vlsergey.recommend4me.encoder.TextKind.QUERY)?.first()

    companion object {
        /** The task of the queue encoding the works' texts. */
        private val KIND = TaskKind.ENCODE_SITE_ITEM_TEXTS

        /** The works a turn takes (owner, 2026-11-19: "each of the tasks does at most N operations, say, 1000"). */
        const val PORTION = 1000

        /** The key the title of an item is kept under among its texts' vectors. */
        const val TITLE = "title"

        /**
         * Every text of the source's items the models read, by its key, with the block of the models' input it is and
         * the key of its label: every text that has vectors — those of the source's own blocks, the lines searched by
         * meaning (the site's line of characters) — and the title.
         */
        fun blocks(source: String, schema: SourceSchema): Map<String, Pair<String, String>> =
            schema.texts
                .filter { it.block != null || it.searchByMeaning }
                .associate { it.key to ((it.block ?: "text:${it.key}") to I18nKeys.textLabel(source, it.key)) } +
                (TITLE to ("text:$TITLE" to TITLE_LABEL))

        /** The key of the label of the title among the texts: "standard:text.title". */
        val TITLE_LABEL = I18nKeys.qualified(I18nKeys.STANDARD, "text.title")

        /**
         * Texts per encoder call; their windows share batches of the graph, sorted by length among them: of a few, the
         * graph's batches ran half full.
         */
        private const val BATCH = 128
    }
}

/**
 * Since when the vectors of the [index] are to be made by the [encoder]: an encoder other than the one they were made
 * by last — never seen, or seen before another — is told now: every vector made before it is of another.
 */
private fun vectorsSince(index: ListIndex, encoder: TextEncoder): Long {
    if (!madeBy(index, encoder)) {
        val now = System.currentTimeMillis()
        index.stages.changed(VECTORS_SINCE, now)
        index.stages.changed(vectorsBy(encoder.id), now)
        index.commit()
    }
    return index.stages.since(VECTORS_SINCE) ?: 1L
}

/**
 * Whether the vectors of the [index] are made by the [encoder] since they were last told to be made anew: the encoder
 * was told then, not before another (the encoder of the tests changed and changed back).
 */
private fun madeBy(index: ListIndex, encoder: TextEncoder): Boolean =
    index.stages.since(vectorsBy(encoder.id)).let { it != null && it == index.stages.since(VECTORS_SINCE) }

/** What the index of the list tells of since when the vectors are to be made by the encoder of now. */
private const val VECTORS_SINCE = "vectors"

/** What the index of the list tells of since when the vectors were made by the encoder [id], when it was last told. */
private fun vectorsBy(id: String) = "vectorsBy:$id"
