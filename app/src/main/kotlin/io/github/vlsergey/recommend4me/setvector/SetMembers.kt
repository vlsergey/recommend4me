package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import org.springframework.stereotype.Component

/**
 * THE MEMBERS OF THE SETS of every kind, as their vectors: the screenshots by the picture encoder, the reviews one by
 * one and the windows of the text by the text encoder.
 */
@Component
class SetMembers(private val plugins: Plugins, private val phrases: PhraseVectors) {

    /**
     * What the members of a kind of sets are encoded by: the image encoder, the text encoder, and of the chapters its
     * windows ([io.github.vlsergey.recommend4me.encoder.TextEncoder.windowsId]) — cut otherwise, made again.
     */
    fun encoderOf(kind: SetKind): String? =
        when (kind) {
            SetKind.SCREENS -> plugins.imageEncoder()?.id
            SetKind.REVIEWS -> plugins.textEncoder()?.id
            SetKind.PARTS -> plugins.textEncoder()?.windowsId
        }

    /** How many members every item's set of [kind] has now. */
    fun sizes(s: SourceStore, kind: SetKind, encoder: String): Map<String, Int> =
        when (kind) {
            SetKind.SCREENS -> s.pictures.analysed.screenshotCounts(encoder)
            SetKind.REVIEWS -> s.reviews.counts()
            SetKind.PARTS -> s.parts.windows.windowCounts(encoder)
        }

    /** The members of the sets of [kind] of the items [ids], as vectors by [encoder]. */
    fun members(s: SourceStore, kind: SetKind, encoder: String, ids: List<String>): Map<String, List<FloatArray>> =
        when (kind) {
            SetKind.SCREENS -> s.pictures.analysed.screenshots(ids, encoder)
            SetKind.REVIEWS -> {
                val texts = s.reviews.textsOf(ids).mapValues { (_, list) -> list.distinct() }
                val encoded = phrases.of(s, texts.values.flatten().toSet())
                texts.mapValues { (_, list) -> list.mapNotNull { encoded[it] } }
            }
            SetKind.PARTS -> s.parts.windows.windows(ids, encoder)
        }

    /** [n] members of the sets of [kind] of the source drawn at random, as vectors by [encoder]. */
    fun sample(s: SourceStore, kind: SetKind, encoder: String, n: Int): List<FloatArray> =
        when (kind) {
            SetKind.SCREENS -> s.pictures.analysed.sample(n, encoder)
            SetKind.REVIEWS -> s.reviews.sample(n).distinct().let { texts -> phrases.of(s, texts).values.toList() }
            SetKind.PARTS -> s.parts.windows.sample(n, encoder)
        }
}
