package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.encoder.EncodedPieces
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.fulltext.FullTextCentre
import io.github.vlsergey.recommend4me.fulltext.FullTextCentres
import io.github.vlsergey.recommend4me.fulltext.FullTextVectors
import io.github.vlsergey.recommend4me.fulltext.TextWindow
import io.github.vlsergey.recommend4me.piece.FittedPieces
import io.github.vlsergey.recommend4me.piece.PieceProjections
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import org.springframework.stereotype.Component

/**
 * What the texts searched by meaning of a type are made of beside their vectors: their pieces by the projection
 * [pieces], the full-text vectors of their windows by the centre [centre]; none of none learnt.
 */
class TextEncoding(val pieces: FittedPieces?, val centre: FullTextCentre?) {
    companion object {
        /** What the texts searched by meaning of the [type] are made of now. */
        fun of(type: TypeStore, projections: PieceProjections, centres: FullTextCentres) =
            TextEncoding(projections.of(type), centres.of(type))
    }
}

/**
 * THE WINDOWS OF THE LONG TEXTS of the items: how many each text whose vector is kept has, and of a text of more than
 * one the vector of every window. Of a text searched by meaning, its pieces ([io.github.vlsergey.recommend4me.part
 * .PieceRepository]) by the same run of the encoder as the windows, and the full-text vectors of its windows — of a
 * text of one window, of its vector — ([io.github.vlsergey.recommend4me.part.FullTextVectorRepository]): what the
 * search finds it by. Made with the text's vector ([TextVectors]).
 */
@Component
class TextWindows(private val plugins: Plugins) {

    /**
     * The windows of the [texts] whose [vectors] were just made kept, with their pieces and full-text vectors, by
     * [with].
     */
    fun keepWindows(store: SourceStore, texts: List<TextToEncode>, vectors: List<FloatArray>, with: TextEncoding) {
        val encoder = plugins.textEncoder() ?: return
        val spans = texts.map { encoder.windowSpans(it.text) }
        val pieces = piecesOf(encoder, texts.filter { it.meaningful }, with)
        val long = texts.indices.filter { spans[it].size > 1 && pieces[texts[it]] == null }
        val windows = if (long.isEmpty()) emptyList() else encoder.encodeWindows(long.map { texts[it].text })
        val of = long.withIndex().associate { (k, i) -> texts[i] to windows[k] }
        texts.forEachIndexed { i, t ->
            val own = pieces[t]?.windows ?: of[t] ?: listOf(vectors[i])
            store.textVectors.windows.saveWindows(
                t.itemId,
                t.key,
                spans[i].size,
                encoder.windowsId,
                own.takeIf { spans[i].size > 1 }.orEmpty(),
            )
            if (t.meaningful) keepMeaning(store, t, spans[i], own, pieces[t], with)
        }
    }

    /**
     * Of the [texts] whose vectors are made, their pieces where they wait — the encoder run again — and the full-text
     * vectors of their windows: of the windows of the run, else of the vectors kept, no model run.
     */
    fun keepMade(store: SourceStore, texts: List<TextToEncode>, with: TextEncoding) {
        val encoder = plugins.textEncoder() ?: return
        val pieces = piecesOf(encoder, texts.filter { it.pieces }, with)
        val rest = texts.filter { pieces[it] == null && it.fullText }
        val ids = rest.map { it.itemId }.distinct()
        val kept =
            if (ids.isEmpty()) emptyMap() else store.textVectors.ofMany(ids, encoder.id, rest.map { it.key }.toSet())
        val windows =
            if (ids.isEmpty()) emptyMap() else store.textVectors.windows.windowsOf(ids, encoder.id, encoder.windowsId)
        texts.forEach { t ->
            val spans = encoder.windowSpans(t.text)
            val own =
                pieces[t]?.windows
                    ?: windows[t.itemId]?.get(t.key)?.takeIf { spans.size > 1 }
                    ?: kept[t.itemId]?.get(t.key)?.let(::listOf)
            if (own != null) keepMeaning(store, t, spans, own, pieces[t], with)
        }
    }

    /** The pieces of the [texts] by the projection of [with], by text; none of no projection, or of no text. */
    private fun piecesOf(
        encoder: TextEncoder,
        texts: List<TextToEncode>,
        with: TextEncoding,
    ): Map<TextToEncode, EncodedPieces> {
        val made =
            with.pieces
                ?.takeIf { texts.isNotEmpty() }
                ?.let { encoder.pieces?.encode(texts.map { it.text }, it.projection) }
        return made?.let { texts.zip(it).toMap() }.orEmpty()
    }

    /**
     * Of the text [t] searched by meaning, its [pieces] kept and the full-text vectors of its [windows] at the
     * characters [spans] — nothing of windows of another text than the one of now.
     */
    private fun keepMeaning(
        store: SourceStore,
        t: TextToEncode,
        spans: List<IntRange>,
        windows: List<FloatArray>,
        pieces: EncodedPieces?,
        with: TextEncoding,
    ) {
        val fitted = with.pieces
        if (fitted != null && pieces != null)
            store.parts.pieces.saveText(t.itemId, t.key, fitted.projection.key, fitted.codebook.codes(pieces.pieces))
        val centre = with.centre
        if (centre != null && spans.size == windows.size) {
            val packed =
                FullTextVectors.pack(spans.zip(windows) { r, v -> TextWindow(r.first, r.last + 1, v) }, centre.centre)
            store.parts.fullText.saveText(t.itemId, t.key, centre.key, packed)
        }
    }
}
