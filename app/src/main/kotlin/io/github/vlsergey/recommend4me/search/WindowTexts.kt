package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.fulltext.NearWindow
import io.github.vlsergey.recommend4me.part.PieceRepository
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.stereotype.Component

/**
 * THE TEXTS OF THE WINDOWS FOUND near a line ([NearWindow]), as their full-text vectors were made of them: a chapter's
 * of the store of the parts, a text's as the user corrected it — no document of a work made: a document reads every
 * chapter and every value of its work.
 */
@Component
class WindowTexts(private val stores: Stores) {

    /**
     * The text of every window of [windows]; a window whose text is gone or shorter since left out. The chapters of
     * every work of a source read by one MultiGet of the store of the parts, in the order of its keys.
     */
    fun of(windows: List<NearWindow>): Map<NearWindow, String> {
        val out = HashMap<NearWindow, String>()
        windows
            .groupBy { it.key.source }
            .forEach { (source, list) ->
                val s = stores.source(source) ?: return@forEach
                val (ofTexts, ofParts) = list.partition { it.text.startsWith(TEXT) }
                // Every chapter of every work at once, one MultiGet in the order of the store's keys
                val texts = s.parts.textsOf(ofParts.map { it.key.id to it.text })
                ofParts.forEach { w -> cut(texts[w.key.id to w.text], w)?.let { out[w] = it } }
                textsOf(s, ofTexts).forEach { (w, text) -> cut(text, w)?.let { out[w] = it } }
            }
        return out
    }

    /** The whole text of every window of a text of the items of [s]: the site's, as the user corrected it. */
    private fun textsOf(s: SourceStore, windows: List<NearWindow>): Map<NearWindow, String> {
        if (windows.isEmpty()) return emptyMap()
        val ids = windows.map { it.key.id }.distinct()
        val keys = windows.map { it.text.removePrefix(TEXT) }.distinct()
        val site = s.items.textsOf(ids, keys)
        val own = s.corrections.fieldsOf(ids)
        val texts = ids.associateWith { Corrected.texts(site[it].orEmpty(), own[it].orEmpty()) }
        return windows.mapNotNull { w -> texts[w.key.id]?.get(w.text.removePrefix(TEXT))?.let { w to it } }.toMap()
    }

    private fun cut(text: String?, w: NearWindow): String? =
        text?.takeIf { w.from in 0..w.to && w.to <= it.length }?.substring(w.from, w.to)

    private companion object {
        /** The prefix of a text of an item among the keys of the store of the parts. */
        val TEXT = PieceRepository.textKey("")
    }
}
