package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.vector.Vectors

/**
 * THE TEXTS OF A SITE'S WORKS COMPARED BOTH WAYS (owner, 2026-11-19: "for every text field both the trigram ones and
 * the vector ones"): of every work of the search, the vector of its title by the encoder ([titleVectors], a unit row in
 * half precision, [titled] false of a work without one; of no column, a site of none) and the trigrams of its other
 * texts ([letters], [letterLengths]: by [LetteredText], [Letters], nought of a work of none) — its description and the
 * site's line of its characters as the user corrected them, the names the site gives its fandoms — beside the trigrams
 * of the titles and the vectors of the descriptions the search compared before.
 */
internal class TextsCompared(
    val titleVectors: HalfMatrix,
    val titled: BooleanArray,
    val letters: List<HalfMatrix>,
    val letterLengths: List<FloatArray>,
) {
    companion object {
        /**
         * Of the works [ids] of the source [s], ascending: the vectors of their titles by the [encoder], the letters.
         */
        fun read(s: SourceStore, encoder: String, ids: List<String>): TextsCompared {
            val at = ids.withIndex().associate { it.value to it.index }
            val titled = BooleanArray(ids.size)
            var titleVectors: HalfMatrix? = null
            fun put(id: String, vector: FloatArray) {
                val i = at[id] ?: return
                val into = titleVectors ?: HalfMatrix(ids.size, vector.size).also { titleVectors = it }
                if (vector.size != into.cols) return
                into.put(i, Vectors.unit(vector))
                titled[i] = true
            }
            // Of every work of a site, every title's vector streamed; of a few, theirs by their ids
            if (ids.size > FEW) s.textVectors.forEach(encoder, listOf(TextVectors.TITLE)) { id, _, v -> put(id, v) }
            else
                s.textVectors.ofMany(ids, encoder, listOf(TextVectors.TITLE)).forEach { (id, texts) ->
                    texts[TextVectors.TITLE]?.let { put(id, it) }
                }
            val texts = textsOf(s, ids, at)
            val rows = LetteredText.entries.map { Letters.rows(texts.getValue(it)) }
            return TextsCompared(
                titleVectors ?: HalfMatrix(ids.size, 0),
                titled,
                rows.map { it.first },
                rows.map { it.second },
            )
        }

        /**
         * The texts of the works [ids] compared letter by letter, by their places [at]: the description and the line of
         * the characters as the user corrected them, the names of the fandoms; none of a work of none.
         */
        private fun textsOf(s: SourceStore, ids: List<String>, at: Map<String, Int>): Map<LetteredText, List<String?>> {
            val out = LetteredText.entries.associateWith { arrayOfNulls<String>(ids.size) }
            written(s, ids, at, out)
            fandomsOf(s, ids, at)?.copyInto(out.getValue(LetteredText.FANDOMS))
            return out.mapValues { it.value.asList() }
        }

        /** The description and the line of the characters of the works [ids] as the user corrected them, [into]. */
        private fun written(
            s: SourceStore,
            ids: List<String>,
            at: Map<String, Int>,
            into: Map<LetteredText, Array<String?>>,
        ) {
            val description = s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key
            val keys =
                listOfNotNull(
                    description?.let { LetteredText.DESCRIPTION to it },
                    s.schema.universeLine?.let { LetteredText.CHARACTERS to it },
                )
            if (keys.isEmpty()) return
            val fields = if (ids.size > FEW) s.corrections.allFields() else s.corrections.fieldsOf(ids)
            fun put(id: String, texts: Map<String, String>) {
                val i = at[id] ?: return
                val corrected = Corrected.texts(texts, fields[id].orEmpty())
                keys.forEach { (text, key) -> into.getValue(text)[i] = corrected[key]?.takeIf { it.isNotBlank() } }
            }
            val wanted = keys.map { it.second }
            if (ids.size > FEW) s.items.forEachTexts(wanted, ::put)
            else s.items.textsOf(ids, wanted).forEach { (id, texts) -> put(id, texts) }
        }

        /**
         * The names the site gives the fandoms of the works [ids], one after another as it gives them; none of a work
         * of none, and of a site of no fandoms.
         */
        private fun fandomsOf(s: SourceStore, ids: List<String>, at: Map<String, Int>): Array<String?>? {
            val facet = UniverseFacet.fandomFacet(s.schema) ?: return null
            val fandoms = arrayOfNulls<List<String>>(ids.size)
            if (ids.size > FEW) s.items.forEachFacet(facet) { id, keys -> at[id]?.let { fandoms[it] = keys } }
            else
                s.items.facetsOf(ids).forEach { (id, facets) ->
                    facets[facet]?.let { keys -> at[id]?.let { fandoms[it] = keys } }
                }
            val names = s.names(facet, fandoms.flatMap { it.orEmpty() }.distinct())
            return Array(ids.size) { i ->
                fandoms[i]?.takeIf { it.isNotEmpty() }?.joinToString(", ") { names[it] ?: it }
            }
        }

        /** Of more works than so many, every work's texts streamed and picked from: one pass, not a query a chunk. */
        private const val FEW = 10_000
    }
}
