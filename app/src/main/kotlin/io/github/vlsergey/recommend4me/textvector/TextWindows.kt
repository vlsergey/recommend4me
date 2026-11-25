package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.part.PieceRepository
import io.github.vlsergey.recommend4me.piece.FittedPieces
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import org.springframework.stereotype.Component

/**
 * THE WINDOWS OF THE LONG TEXTS of the items: how many each text whose vector is kept has, and of a text of more than
 * one the vector of every window — what the search finds a piece of a long text by. And, of a type with a projection of
 * the pieces, the codes of every text's pieces ([PieceRepository.saveText]), made by the same run of the encoder as the
 * windows: what the search finds the place of a line in a text by. Made with the text's vector ([TextVectors]).
 */
@Component
class TextWindows(private val plugins: Plugins) {

    /** The windows of the texts just encoded — (item, text key, text) — kept, and their pieces by [fitted]. */
    fun keepWindows(store: SourceStore, texts: List<Triple<String, String, String>>, fitted: FittedPieces?) {
        val encoder = plugins.textEncoder() ?: return
        val counts = texts.map { encoder.windowSpans(it.third).size }
        val pieces = fitted?.let { f -> encoder.pieces?.encode(texts.map { it.third }, f.projection) }
        val long = texts.indices.filter { counts[it] > 1 }
        val windows =
            when {
                pieces != null -> long.map { pieces[it].windows }
                long.isEmpty() -> emptyList()
                else -> encoder.encodeWindows(long.map { texts[it].third })
            }
        val of = long.withIndex().associate { (k, i) -> i to windows[k] }
        texts.forEachIndexed { i, (id, key, _) ->
            store.textVectors.windows.saveWindows(id, key, counts[i], encoder.windowsId, of[i].orEmpty())
            if (fitted != null && pieces != null)
                store.parts.pieces.saveText(id, key, fitted.projection.key, fitted.codebook.codes(pieces[i].pieces))
        }
    }
}
