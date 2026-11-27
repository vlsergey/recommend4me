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
 * [pieces], their full-text vector by the centre [centre]; none of none learnt.
 */
class TextEncoding(val pieces: FittedPieces?, val centre: FullTextCentre?) {
    companion object {
        /** What the texts searched by meaning of the [type] are made of now. */
        fun of(type: TypeStore, projections: PieceProjections, centres: FullTextCentres) =
            TextEncoding(projections.of(type), centres.of(type))
    }
}

/** The vectors of texts just made, and of those searched by meaning whose pieces were made by the same pass, those. */
class MadeVectors(val vectors: List<FloatArray?>, val pieces: Map<TextToEncode, EncodedPieces>)

/**
 * THE TEXTS SEARCHED BY MEANING, what the search finds them by beside their vectors: of every text read whole — no
 * windows (owner, 2026-11-27: a description is one vector) — its pieces by the projection learnt
 * ([io.github.vlsergey.recommend4me.part.PieceRepository]), made by the same pass of the encoder as its vector, and its
 * full-text vector, of that vector, over the whole text
 * ([io.github.vlsergey.recommend4me.part.FullTextVectorRepository]).
 */
@Component
class SearchedTexts(private val plugins: Plugins) {

    /**
     * The vectors of the [texts]: of those searched by meaning of a projection learnt, with their pieces, by one pass
     * of the encoder ([io.github.vlsergey.recommend4me.encoder.PieceEncoder.encodeWhole]); of the rest, the vectors
     * alone.
     */
    fun encode(encoder: TextEncoder, texts: List<TextToEncode>, with: TextEncoding): MadeVectors {
        val pieces = piecesOf(encoder, texts.filter { it.meaningful }, with)
        val rest = texts.filter { it !in pieces }
        val vectors = if (rest.isEmpty()) emptyList() else encoder.encode(rest.map { it.text })
        val ofRest = rest.zip(vectors).toMap()
        return MadeVectors(texts.map { t -> pieces[t]?.windows?.singleOrNull() ?: ofRest[t] }, pieces)
    }

    /** Of the [texts] whose [made] vectors were just kept, the pieces and the full-text vectors of those searched. */
    fun keep(store: SourceStore, texts: List<TextToEncode>, made: MadeVectors, with: TextEncoding) =
        texts.forEachIndexed { i, t ->
            val vector = made.vectors[i]
            if (t.meaningful && vector != null) keepMeaning(store, t, vector, made.pieces[t], with)
        }

    /**
     * Of the [texts] whose vectors are kept, their pieces where they wait — the encoder run again — and their full-text
     * vectors, of the vectors kept: no model run.
     */
    fun keepMade(store: SourceStore, texts: List<TextToEncode>, with: TextEncoding) {
        val encoder = plugins.textEncoder() ?: return
        val pieces = piecesOf(encoder, texts.filter { it.pieces }, with)
        val ids = texts.map { it.itemId }.distinct()
        val kept = if (ids.isEmpty()) emptyMap() else store.textVectors.ofMany(ids, encoder.id, texts.map { it.key })
        texts.forEach { t -> kept[t.itemId]?.get(t.key)?.let { keepMeaning(store, t, it, pieces[t], with) } }
    }

    /** The pieces of the [texts], each read whole, by the projection of [with], by text; none of no projection. */
    private fun piecesOf(
        encoder: TextEncoder,
        texts: List<TextToEncode>,
        with: TextEncoding,
    ): Map<TextToEncode, EncodedPieces> {
        val made =
            with.pieces
                ?.takeIf { texts.isNotEmpty() }
                ?.let { encoder.pieces?.encodeWhole(texts.map { it.text }, it.projection) }
        return made?.let { texts.zip(it).toMap() }.orEmpty()
    }

    /**
     * Of the text [t] searched by meaning, its [pieces] kept and the full-text vector of its [vector], over the whole
     * text.
     */
    private fun keepMeaning(
        store: SourceStore,
        t: TextToEncode,
        vector: FloatArray,
        pieces: EncodedPieces?,
        with: TextEncoding,
    ) {
        val fitted = with.pieces
        if (fitted != null && pieces != null)
            store.parts.pieces.saveText(t.itemId, t.key, fitted.projection.key, fitted.codebook.codes(pieces.pieces))
        val centre = with.centre ?: return
        val packed = FullTextVectors.pack(listOf(TextWindow(0, t.text.length, vector)), centre)
        store.parts.fullText.saveText(t.itemId, t.key, centre.key, packed)
    }
}
