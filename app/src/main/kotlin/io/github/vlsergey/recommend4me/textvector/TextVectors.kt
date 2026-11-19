package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
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
 * A WORK THE USER WAITS FOR ON THE SCREEN — its card or its page, its chances still to come ([awaited]) — has its texts
 * encoded ahead of everything ([TaskPriority.ASKED]), its items kept waiting in the queue's keys.
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
    // was to go on, or of the items the queue kept
    init {
        tasks.resumption.resumes(KIND) { left, reason ->
            val type = left.work.type ?: return@resumes
            val scope = left.work.works
            if (scope == null) planAsked(type, reason) else plan(type, scope, reason, left.cursor ?: TaskCursor.FIRST)
        }
    }

    /**
     * Of every type whose index of the list never saw the encoder of now, every work's texts encoded again — what the
     * data say, nothing for the start itself.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val encoder = plugins.textEncoder() ?: return
        stores.types
            .filter { lists.of(it.id).stages.since(vectorsBy(encoder.id)) == null }
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
            turn(typeId, scope, tasks.running()?.cursor ?: TaskCursor.FIRST)?.let { tasks.again("sweep", cursor = it) }
        }

    /** Every text of the [typeId] to be encoded encoded now, on the caller's thread, turn after turn: for the tests. */
    fun encodeNow(typeId: String) {
        encodeAsked(typeId)
        WorkScope.entries.forEach { scope ->
            var cursor: TaskCursor? = TaskCursor.FIRST
            while (cursor != null) cursor = turn(typeId, scope, cursor)
        }
    }

    /**
     * ONE TURN: the next [portion] works of the [scope] of the [typeId] whose texts are to be encoded, from the
     * [cursor] on, encoded; where the next turn goes on from — none when none is left. Of no encoder, none: the next
     * task told alike.
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
                done(type!!, index, encoder!!, items.map { it.key })
                events.publishEvent(WorkTextVectorFillEvent(typeId, mapOf(scope to items.first().cursor())))
            }
        }
        return items?.lastOrNull()?.cursor()?.takeIf { items.size == portion }
    }

    /**
     * Of the items [keys] of the [type], the texts new or changed encoded by the [encoder], those gone dropped, every
     * item marked done, the fields of those whose vectors changed to be computed again; committed.
     */
    private fun done(type: TypeStore, index: ListIndex, encoder: TextEncoder, keys: List<ItemKey>) {
        val started = System.currentTimeMillis()
        busy = true
        val changed =
            try {
                keys.groupBy({ it.source }, { it.id }).flatMap { (source, ids) ->
                    type.source(source)?.let { encoded(it, encoder, ids) }.orEmpty()
                }
            } finally {
                busy = false
            }
        WorkStage.VECTORS.done(index, keys, started)
        WorkStage.FIELDS.again(index, changed)
        index.commit()
        log.info(
            "{}: the texts of {} works held, of {} encoded anew, in {} ms",
            type.id,
            keys.size,
            changed.size,
            System.currentTimeMillis() - started,
        )
    }

    /**
     * The texts of the items [ids] of the source [store] encoded by [encoder] where new or changed, and kept with their
     * windows, a batch at a time; those gone dropped. The items whose vectors changed.
     */
    private fun encoded(store: SourceStore, encoder: TextEncoder, ids: List<String>): List<ItemKey> {
        val scan = TextScan(store, ids, encoder.id)
        val gone = scan.gone()
        gone.forEach { (id, key) -> store.textVectors.delete(id, key) }
        if (gone.isNotEmpty())
            events.publishEvent(SiteItemTextVectorChangeEvent(store.id, gone.map { it.first }.distinct()))
        for (batch in scan.todo.chunked(BATCH)) {
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
     * Whether one of the [items] has none of its texts encoded yet and a text to encode: captured or written just now,
     * its texts to come. Till they are, the models of the facets do not judge it
     * ([FacetData.judged][io.github.vlsergey.recommend4me.suggestion.FacetData]): its chances are still to come. The
     * user waits for them on the screen: their encoding planned ahead of everything — planned, never waited for.
     */
    fun awaited(items: Collection<ItemKey>): Boolean {
        val encoder = plugins.textEncoder() ?: return false
        val bare =
            items.groupBy({ it.source }, { it.id }).flatMap { (source, ids) ->
                val store = stores.source(source) ?: return@flatMap emptyList()
                val encoded = store.textVectors.kept(encoder.id, ids).filterValues { it != null }.keys.map { it.first }
                val todo = TextScan(store, ids - encoded.toSet(), encoder.id).todo
                todo.map { ItemKey(source, it.itemId) }.distinct()
            }
        bare
            .groupBy { stores.typeOf(it.source).id }
            .forEach { (type, keys) ->
                tasks.keys.want(asked(type), keys.map { "${it.source}$SEP${it.id}" })
                planAsked(type, "page")
            }
        return bare.isNotEmpty()
    }

    /**
     * The texts of the items of the [typeId] the user waits for, kept in the queue's keys, planned for the [reason].
     */
    private fun planAsked(typeId: String, reason: String) =
        tasks.plan(asked(typeId), null, reason, priority = TaskPriority.ASKED) { encodeAsked(typeId) }

    /** The texts of the items of the [typeId] the user waits for, as the queue keeps them, encoded now. */
    private fun encodeAsked(typeId: String) {
        val type = stores.type(typeId)
        val encoder = plugins.textEncoder()
        val taken = tasks.keys.wanted(asked(typeId))
        if (type == null || encoder == null || taken.isEmpty()) return
        done(type, lists.of(typeId), encoder, taken.map { ItemKey(it.substringBefore(SEP), it.substringAfter(SEP)) })
        tasks.keys.done(asked(typeId), taken)
        // Of the scopes the items are of, the fields of every one to be computed found from the first
        events.publishEvent(WorkTextVectorFillEvent(typeId, WorkScope.entries.associateWith { TaskCursor.FIRST }))
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

        /** An item's key as the queue keeps it: its source and its id. */
        private const val SEP = '\t'

        /** The task of the texts of the works of the [typeId] the user waits for. */
        private fun asked(typeId: String) = Task(KIND, type = typeId)

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
 * Since when the vectors of the [index] are to be made by the [encoder]: an encoder the index never saw is told now —
 * every vector made before it is of another.
 */
private fun vectorsSince(index: ListIndex, encoder: TextEncoder): Long {
    if (index.stages.since(vectorsBy(encoder.id)) == null) {
        val now = System.currentTimeMillis()
        index.stages.changed(VECTORS_SINCE, now)
        index.stages.changed(vectorsBy(encoder.id), now)
        index.commit()
    }
    return index.stages.since(VECTORS_SINCE) ?: 1L
}

/** What the index of the list tells of since when the vectors are to be made by the encoder of now. */
private const val VECTORS_SINCE = "vectors"

/** What the index of the list tells of since when it has seen the encoder [id]. */
private fun vectorsBy(id: String) = "vectorsBy:$id"
