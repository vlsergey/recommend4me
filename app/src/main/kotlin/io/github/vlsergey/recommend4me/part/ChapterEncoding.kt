package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.fulltext.FullTextCentre
import io.github.vlsergey.recommend4me.fulltext.FullTextVectors
import io.github.vlsergey.recommend4me.fulltext.TextWindow
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.piece.FittedPieces
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.vector.Vectors
import org.springframework.context.ApplicationEventPublisher

/**
 * The encoder of the texts, and what the pieces and the full-text vectors of the windows of the type are made with:
 * none of no projection of the pieces, none of no centre of the windows learnt.
 */
internal class Encoding(val encoder: TextEncoder, val pieces: FittedPieces?, val centre: FullTextCentre?) {
    /**
     * What the parts are encoded by, as the index of the list keeps it: a change of any of it makes every part again.
     */
    val key: String
        get() =
            "${encoder.windowsId}|${Paragraphs.keyOf(encoder.id)}" +
                (pieces?.let { "|pieces:${it.projection.key}" } ?: "") +
                (centre?.let { "|fullText:${it.key}" } ?: "")
}

/**
 * What a batch of parts is encoded for: its windows — with their pieces and full-text vectors —, its full-text vectors
 * alone, its paragraphs.
 */
internal enum class BatchKind {
    WINDOWS,
    FULL_TEXT,
    PARAGRAPHS,
}

/** A batch of parts of one source to encode. */
internal class Batch(val source: SourceStore, val parts: List<PartWaiting>, val kind: BatchKind)

/**
 * THE PARTS OF THE WORKS ENCODED A BATCH AT A TIME, by [with]: of a part whose windows or pieces wait, the windows by
 * the encoder — of a type with a projection of the pieces, the pieces by the same run, the windows' vectors of it — and
 * the full-text vectors of the windows; of a part whose windows and pieces are made and whose full-text vectors wait,
 * the full-text vectors of the windows' kept vectors, no model run; of a part whose paragraphs wait, the paragraphs. A
 * part whose windows are made, of its pieces or full-text vectors alone, has its windows as they were: nothing the
 * models read is told of as changed.
 */
internal class ChapterEncoding(private val with: Encoding, private val events: ApplicationEventPublisher) {
    private val encoder = with.encoder

    /**
     * The batches of the parts of the items [ids] of the source [s] waiting to be encoded, [BATCH] parts each: their
     * windows, their full-text vectors alone, then their paragraphs — the parts read once, by their works.
     */
    fun batchesOf(s: SourceStore, ids: List<String>): List<Batch> {
        val waiting =
            s.parts.windows.waitingOf(
                ids,
                encoder.windowsId,
                Paragraphs.keyOf(encoder.id),
                with.pieces?.projection?.key,
                with.centre?.key,
            )
        val model = waiting.filter { it.windows || it.pieces }
        return model.chunked(BATCH).map { Batch(s, it, BatchKind.WINDOWS) } +
            (waiting - model.toSet()).filter { it.fullText }.chunked(BATCH).map { Batch(s, it, BatchKind.FULL_TEXT) } +
            waiting.filter { it.paragraphs }.chunked(BATCH).map { Batch(s, it, BatchKind.PARAGRAPHS) }
    }

    /** The parts of the [batch] encoded, the works of windows changed kept in [encoded]. */
    fun encode(batch: Batch, encoded: MutableSet<ItemKey>) {
        val todo = batch.source.parts.windows.texts(batch.parts)
        if (todo.isEmpty()) return
        when (batch.kind) {
            BatchKind.WINDOWS -> windows(batch, todo, encoded)
            BatchKind.FULL_TEXT -> fullTextOf(batch.source, todo)
            BatchKind.PARAGRAPHS -> paragraphs(batch.source, todo)
        }
    }

    /** The windows of the parts [todo] of the [batch], their pieces by the same run, their full-text vectors. */
    private fun windows(batch: Batch, todo: List<PartToEncode>, encoded: MutableSet<ItemKey>) {
        val s = batch.source
        // The pieces made by the run of the windows: the windows' vectors of it, the pieces' codes kept beside
        val fitted = with.pieces
        val pieces = fitted?.let { f -> encoder.pieces?.encode(todo.map { it.content }, f.projection) }
        val windows = pieces?.map { it.windows } ?: encoder.encodeWindows(todo.map { it.content })
        val ofWindows = batch.parts.filter { it.windows }.map { it.itemId to it.partId }.toSet()
        val changed = todo.filter { (it.itemId to it.partId) in ofWindows }
        todo.forEachIndexed { i, part ->
            if ((part.itemId to part.partId) in ofWindows)
                s.parts.windows.saveVectors(part.itemId, part.partId, encoder.windowsId, part.hash, windows[i])
            if (fitted != null && pieces != null)
                s.parts.pieces.save(
                    part.itemId,
                    part.partId,
                    fitted.projection.key,
                    fitted.codebook.codes(pieces[i].pieces),
                )
            saveFullText(s, part, windows[i])
        }
        // What the models learnt of these works read: told, the models fitted of them fitted again
        if (changed.isNotEmpty())
            events.publishEvent(SiteItemChapterVectorChangeEvent(s.id, changed.map { it.itemId }.distinct()))
        changed.forEach { encoded += ItemKey(s.id, it.itemId) }
    }

    /** The full-text vectors of the parts [todo] of the source [s], of the vectors of their windows kept. */
    private fun fullTextOf(s: SourceStore, todo: List<PartToEncode>) {
        val kept = s.parts.windows.windowsOfParts(todo.map { it.itemId to it.partId })
        todo.forEach { part -> kept[part.itemId to part.partId]?.let { saveFullText(s, part, it) } }
    }

    /**
     * The full-text vectors of the [windows] of the [part], by the centre of the type, each window with its characters
     * in the part's text; nothing of no centre, nor of windows of another text than the part's of now.
     */
    private fun saveFullText(s: SourceStore, part: PartToEncode, windows: List<FloatArray>) {
        val centre = with.centre ?: return
        val spans = encoder.windowSpans(part.content)
        if (spans.size != windows.size) return
        val packed =
            FullTextVectors.pack(spans.zip(windows) { r, v -> TextWindow(r.first, r.last + 1, v) }, centre.centre)
        s.parts.fullText.save(part.itemId, part.partId, centre.key, packed)
    }

    /**
     * The paragraphs of every part of [todo] encoded together, then given back to their parts: a chapter's few dozen
     * alone ran the graph's batches half empty.
     */
    private fun paragraphs(s: SourceStore, todo: List<PartToEncode>) {
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
    }

    private companion object {
        /** Parts per encoder call: a chapter is tens of windows already. */
        const val BATCH = 8

        /** Paragraphs per encoder call: a chapter is hundreds of them. */
        const val PARAGRAPHS = 256
    }
}
