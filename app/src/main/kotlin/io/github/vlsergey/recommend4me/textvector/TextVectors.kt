package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.WorkScope
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.fulltext.FullTextCentreFitEvent
import io.github.vlsergey.recommend4me.fulltext.FullTextCentres
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.piece.PieceProjectionFitEvent
import io.github.vlsergey.recommend4me.piece.PieceProjections
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
import io.github.vlsergey.recommend4me.worklist.ListVectors
import io.github.vlsergey.recommend4me.worklist.NextWorks
import io.github.vlsergey.recommend4me.worklist.StageSweeps
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
 * Every change the vectors of the site's text fields read of the works of the [scope] of the [type] done
 * ([WorkStage.VECTORS]): the stages reading them, or waiting for them, look again.
 */
data class WorkTextVectorFillEvent(val type: String, val scope: WorkScope)

/**
 * THE VECTORS OF THE SITE'S TEXT FIELDS OF THE WORKS — THE FIRST OF THE TASKS OVER THE WORKS (owner, 2026-11-19: "we
 * split the vectors into the marked works and the other works"; docs/computations.md, 9 and 10): of every text the
 * models read ([TextDef.block][io.github.vlsergey.recommend4me.source.TextDef.block]), those searched by meaning, and
 * the title ([TITLE]), of the text as the user corrected it, kept with its hash and the encoder that made them — a text
 * is encoded again when it or the encoder changed. THE STAGE [WorkStage.VECTORS] ([StageSweeps]): of the works whose
 * data or answers changed past its mark, of every work by an encoder of the index never seen ([start]); a task of the
 * marked and rated works, and one of the others after every other task, a [PORTION] of them a turn ([turn]) — the
 * highest expected first, from where the last turn left —: the texts new or changed encoded, those gone dropped, every
 * one marked done, the works whose vectors changed dated so: the stages reading them see them; the documents of the
 * list holding other vectors than kept written anew with them ([ListVectors]) — what the search of the duplicates reads
 * of the index alone.
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
    private val sweeps: StageSweeps,
    private val documents: ListVectors,
    private val projections: PieceProjections,
    private val centres: FullTextCentres,
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
            .filter { !madeBy(lists.of(it.id), Encoding(encoder, TextEncoding.of(it, projections, centres)).key) }
            .forEach { type -> WorkScope.entries.forEach { plan(type.id, it, "encoder", TaskCursor.FIRST) } }
    }

    /**
     * The centre of the windows of a type learnt anew: every text of it searched by meaning read again for its
     * full-text vectors.
     */
    @EventListener
    fun centreFitted(event: FullTextCentreFitEvent) =
        WorkScope.entries.forEach { plan(event.type, it, "encoder", TaskCursor.FIRST) }

    /** The projection of the pieces of a type learnt anew: every text of it to be encoded again for its pieces. */
    @EventListener
    fun projectionFitted(event: PieceProjectionFitEvent) =
        WorkScope.entries.forEach { plan(event.type, it, "encoder", TaskCursor.FIRST) }

    /** The documents of works written anew, of what the texts are made of changed: the works of it encoded. */
    @EventListener
    fun documentsWritten(event: WorkDocumentWriteEvent) {
        if (STAGE in event.stages)
            WorkScope.entries.forEach {
                plan(event.type, it, "items", TaskCursor.FIRST)
            }
    }

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

    /**
     * ONE TURN: the next [portion] works of the [scope] of the [typeId] whose texts are to be encoded, from the
     * [cursor] on, encoded; where the next turn goes on from — none when none is left. Of no encoder, none: the next
     * task told alike. Stopped between its batches for a work the user asked for ([Tasks.outranked]), none of its works
     * marked done: it goes on from the [cursor] again, the texts it encoded found encoded.
     */
    internal fun turn(typeId: String, scope: WorkScope, cursor: TaskCursor, portion: Int = PORTION): TaskCursor? {
        val type = stores.type(typeId)
        val encoder = plugins.textEncoder()
        if (type == null || encoder == null) return null
        val with = Encoding(encoder, TextEncoding.of(type, projections, centres))
        madeBy(type, with.key)
        val items = sweeps.next(STAGE, typeId, type.sources.map { it.id }, scope, cursor, portion)
        val gaveWay = items.isNotEmpty() && !done(type, scope, with, items.map { it.key })
        if (!gaveWay && items.size < portion && sweeps.complete(STAGE, typeId, scope))
            events.publishEvent(WorkTextVectorFillEvent(typeId, scope))
        return if (gaveWay) cursor else items.lastOrNull()?.cursor()?.takeIf { items.size == portion }
    }

    /**
     * The vectors of the [type] by the encoder, the projection of the pieces and the centre of the full-text vectors of
     * [key] ([Encoding.key]): of one other than the one they were made by last — never seen, or seen before another —,
     * told so, and the stage to be done of every work again.
     */
    private fun madeBy(type: TypeStore, key: String) {
        val index = lists.of(type.id)
        if (madeBy(index, key)) return
        val now = System.currentTimeMillis()
        index.stages.changed(VECTORS_SINCE, now)
        index.stages.changed(vectorsBy(key), now)
        sweeps.reset(STAGE, type.id, type.sources.map { it.id })
    }

    /**
     * Of the items [keys] of the [scope] of the [type], the texts new or changed encoded by the [encoder], those gone
     * dropped, their documents brought in step with them ([ListVectors]), every item marked done, those whose vectors
     * changed dated so ([StageSweeps.made]); committed. False, nothing marked done, of a turn that gave way to a work
     * the user asked for ([Tasks.outranked]).
     */
    private fun done(type: TypeStore, scope: WorkScope, encoder: Encoding, keys: List<ItemKey>): Boolean {
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
        // Of a turn that gave way, the works not marked done — met again, their texts found encoded — but those encoded
        // anew dated all the same: the next turn finds them unchanged
        val gaveWay = tasks.outranked
        // The documents written anew before they are marked: the marks are written in place, over them
        val brought = documents.bring(type, keys)
        if (!gaveWay) sweeps.done(STAGE, type.id, scope, keys)
        sweeps.made(STAGE, type.id, scope, changed)
        lists.of(type.id).commit()
        log.info(
            "{}: the texts of {} works held, of {} encoded anew, {} documents given their vectors, in {} ms{}",
            type.id,
            keys.size,
            changed.size,
            brought,
            System.currentTimeMillis() - started,
            if (gaveWay) ", given way to a work asked for" else "",
        )
        return !gaveWay
    }

    /**
     * The texts of the items [ids] of the source [store] encoded by [with] where new or changed, and kept with their
     * windows and their pieces, a batch at a time; those gone dropped. The items whose vectors changed.
     */
    private fun encoded(
        store: SourceStore,
        with: Encoding,
        ids: List<String>,
        giveWay: () -> Boolean = { false },
    ): List<ItemKey> {
        val encoder = with.encoder
        val scan = TextScan(store, ids, encoder.id, with.texts.pieces?.projection?.key, with.texts.centre?.key)
        val gone = scan.gone()
        gone.forEach { (id, key) ->
            store.textVectors.delete(id, key)
            store.parts.fullText.forgetText(id, key)
        }
        if (gone.isNotEmpty())
            events.publishEvent(SiteItemTextVectorChangeEvent(store.id, gone.map { it.first }.distinct()))
        val (changed, made) = scan.todo.partition { it.vector }
        for (batch in changed.chunked(BATCH)) {
            if (giveWay()) break
            val vectors = encoder.encode(batch.map { it.text })
            batch.forEachIndexed { i, t ->
                vectors[i]?.let { store.textVectors.save(t.itemId, t.key, encoder.id, t.hash, it) }
            }
            val encoded = batch.indices.filter { vectors[it] != null }
            windows.keepWindows(store, encoded.map { batch[it] }, encoded.map { vectors[it]!! }, with.texts)
            events.publishEvent(SiteItemTextVectorChangeEvent(store.id, batch.map { it.itemId }.distinct()))
        }
        // The texts whose vectors are made and whose pieces or full-text vectors are not, of a projection or a centre
        // learnt anew:
        // nothing the models or the index read changed
        for (batch in made.chunked(BATCH)) {
            if (giveWay()) break
            windows.keepMade(store, batch, with.texts)
        }
        return (changed.map { it.itemId } + gone.map { it.first }).distinct().map { ItemKey(store.id, it) }
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
            stores.source(source)?.let { s ->
                encoded(
                    s,
                    Encoding(
                        encoder,
                        stores.type(s.type)?.let { TextEncoding.of(it, projections, centres) }
                            ?: TextEncoding(null, null),
                    ),
                    ids,
                )
            }
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

        /** The stage of the tasks over the works it is. */
        private val STAGE = WorkStage.VECTORS

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

/** The encoder of the texts, and what the texts searched by meaning are made of beside their vectors. */
private class Encoding(val encoder: TextEncoder, val texts: TextEncoding) {
    /** What the vectors are made by: a change of any of it makes every text again. */
    val key: String
        get() =
            encoder.id +
                (texts.pieces?.let { "|pieces:${it.projection.key}" } ?: "") +
                (texts.centre?.let { "|fullText:${it.key}" } ?: "")
}

/**
 * Whether the vectors of the [index] are made by the encoder and the projection of [key] since they were last told to
 * be made anew: told then, not before another (the encoder of the tests changed and changed back).
 */
private fun madeBy(index: ListIndex, key: String): Boolean =
    index.stages.since(vectorsBy(key)).let { it != null && it == index.stages.since(VECTORS_SINCE) }

/** What the index of the list tells of since when the vectors are to be made by the encoder of now. */
private const val VECTORS_SINCE = "vectors"

/** What the index of the list tells of since when the vectors were made by [key] ([Encoding.key]), when last told. */
private fun vectorsBy(key: String) = "vectorsBy:$key"
