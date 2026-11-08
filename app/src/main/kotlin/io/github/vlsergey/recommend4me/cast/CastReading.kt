package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.suggestion.Vocabulary
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.textvector.TextVectors
import org.springframework.stereotype.Component

/**
 * How a facet's task reads the site's line of characters and pairings ([CastReader]): the way chosen for the facet of
 * the source, the source's facet of the line's entries, the block of the line encoded whole, the characters each work
 * may have by their names, and the vectors of the texts the way asked for, those kept so far — never encoded on the
 * reading: a task is read on the thread of a request too ([CastNames] encodes them in the background).
 */
internal class CastAsked(
    val reader: CastReader,
    val entries: String,
    val line: String?,
    val characters: (String) -> Map<String, List<String>>?,
    val paired: Boolean,
    val vectors: (Collection<String>) -> Map<String, FloatArray>,
) {
    /** What the line of each work of [ids] says, of the works' values on the site ([site]: facet to keys). */
    fun casts(store: SourceStore, ids: List<String>, site: Map<String, Map<String, List<String>>>): List<SaidCast> {
        val said = WorkCasts.of(store, entries, ids.associateWith { site[it]?.get(entries).orEmpty() })
        return ids.map { said[it] ?: SaidCast.NONE }
    }
}

/**
 * THE READING OF THE SITES' LINES OF CHARACTERS AND PAIRINGS BY THE FACETS THAT LEARN FROM THEM: of a facet whose
 * values the site writes in its line ([FacetDef.original]) — the characters, the secondary characters, the pairings —
 * of a source that keeps the line's entries, the way the user chose for it ([readerOf]).
 */
@Component
class CastReading(
    private val plugins: Plugins,
    private val settings: Settings,
    private val phrases: PhraseVectors,
) {
    /** Whether the [facet] of the [store] reads the site's line: its values are the line's, kept by the source. */
    fun reads(store: SourceStore, facet: FacetDef): Boolean =
        facet.original != null && WorkCasts.facetOf(store) != null && plugins.castReaders.isNotEmpty()

    /**
     * The way a facet of a source reads the line: the one named by `cast.<source>.<facet>` ([setting]), else the line
     * whole, else the first; null when no way is installed.
     */
    fun readerOf(source: String, facet: String): CastReader? =
        plugins.castReaders.firstOrNull { it.id == settings.get(setting(source, facet)) }
            ?: plugins.castReaders.firstOrNull { it.id == DEFAULT }
            ?: plugins.castReaders.firstOrNull()

    /**
     * How the [facet] of the [store] reads the line: the characters each work may have named by the [vocabulary] (of a
     * work, character to its names) — the task's values pairings of them when it says so ([Vocabulary.paired]); null of
     * a facet that reads no line.
     */
    internal fun asked(store: SourceStore, facet: FacetDef, vocabulary: Vocabulary?): CastAsked? {
        val reader = readerOf(store.id, facet.key)
        val entries = WorkCasts.facetOf(store)
        return if (reader == null || entries == null || !reads(store, facet)) null
        else
            CastAsked(
                reader,
                entries,
                facet.original?.let { TextVectors.blocks(store.id, store.schema)[it]?.first },
                { id -> vocabulary?.names?.invoke(id)?.names },
                vocabulary?.paired ?: false,
            ) { texts ->
                phrases.cached(store, texts)
            }
    }

    companion object {
        /** The line whole, as the models read it from the first. */
        const val DEFAULT = "cast-line"

        /** The setting the way a facet reads the site's line of characters and pairings is named by. */
        fun setting(source: String, facet: String) = "cast.$source.$facet"
    }
}
