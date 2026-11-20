package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.matrix.ByteMatrix
import io.github.vlsergey.recommend4me.matrix.Rows
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.UniverseFacet

/**
 * THE TEXTS OF A SITE'S WORKS COMPARED BOTH WAYS (owner, 2026-11-19: "for every text field both the trigram ones and
 * the vector ones"): of every work of the search, the vector of its title by the encoder ([titleVectors], a unit row a
 * byte a number ([io.github.vlsergey.recommend4me.matrix.ByteMatrix]), [titled] false of a work without one; of no
 * column, a site of none) and the trigrams of its other texts ([letters], [letterLengths]: by [LetteredText],
 * [Letters], nought of a work of none) — its description and the site's line of its characters as the user corrected
 * them, the names the site gives its fandoms — beside the trigrams of the titles and the vectors of the descriptions
 * the search compared before.
 */
internal class TextsCompared(
    val titleVectors: Rows,
    val titled: BooleanArray,
    /** The vectors of the descriptions by the encoder, a unit row a byte a number; [described] false of none. */
    val descriptions: Rows,
    val described: BooleanArray,
    val letters: List<Rows>,
    val letterLengths: List<FloatArray>,
) {
    companion object {
        /**
         * Of the works [ids] of the source [s], ascending: the vectors of their titles and their descriptions by the
         * [encoder] — of every work of a site in one pass of the table of the vectors, each decoded into one buffer
         * (2026-11-20: two passes and two arrays of every vector took 46 of the 57 seconds of reading ficbook) —, the
         * letters.
         */
        fun read(s: SourceStore, encoder: String, ids: List<String>): TextsCompared {
            val at = HashMap<String, Int>(ids.size * 2).also { m -> ids.forEachIndexed { i, id -> m[id] = i } }
            val description = s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key
            val titles = UnitRows(ids.size)
            val descriptions = UnitRows(ids.size)
            fun rowsOf(key: String) = if (key == TextVectors.TITLE) titles else descriptions
            val keys = listOfNotNull(TextVectors.TITLE, description)
            // Of every work of a site, every vector streamed as kept; of a few, theirs by their ids
            if (ids.size > FEW)
                s.textVectors.forEachHalves(encoder, keys) { id, key, halves ->
                    at[id]?.let { rowsOf(key).putHalves(it, halves) }
                }
            else
                s.textVectors.ofMany(ids, encoder, keys).forEach { (id, texts) ->
                    texts.forEach { (key, v) -> at[id]?.let { rowsOf(key).put(it, v) } }
                }
            val texts = textsOf(s, ids, at)
            // The description long, every row whole; the names of the fandoms and the line of the characters short
            val rows =
                LetteredText.entries.map {
                    if (it == LetteredText.DESCRIPTION) Letters.wholeRows(texts.getValue(it))
                    else Letters.rows(texts.getValue(it))
                }
            return TextsCompared(
                titles.rows(),
                titles.had,
                descriptions.rows(),
                descriptions.had,
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

/**
 * The vectors of one text of [count] works as they come, a unit row a byte a number ([ByteMatrix]) of the numbers of
 * the first one; which have one ([had]). A vector of another number of numbers passed over. Decoded into one buffer: no
 * array a vector.
 */
private class UnitRows(private val count: Int) {
    private var matrix: ByteMatrix? = null
    private var buffer = FloatArray(0)
    val had = BooleanArray(count)

    /** The vector of the [i]-th work of [halves]: half-precision numbers, little-endian, as kept. */
    fun putHalves(i: Int, halves: ByteArray) {
        val n = halves.size / 2
        if (buffer.size != n) buffer = FloatArray(n)
        for (q in 0 until n) {
            val bits = (halves[2 * q].toInt() and BYTE) or ((halves[2 * q + 1].toInt() and BYTE) shl Byte.SIZE_BITS)
            buffer[q] = java.lang.Float.float16ToFloat(bits.toShort())
        }
        put(i, buffer)
    }

    /** The vector [v] of the [i]-th work, made of unit length in place. */
    fun put(i: Int, v: FloatArray) {
        val into = matrix ?: ByteMatrix(count, v.size).also { matrix = it }
        if (v.size != into.cols) return
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        val length = kotlin.math.sqrt(norm).toFloat()
        if (length > 0f) for (q in v.indices) v[q] /= length
        into.put(i, v)
        had[i] = true
    }

    /** The rows read; of none, rows of no column. */
    fun rows(): ByteMatrix = matrix ?: ByteMatrix(count, 0)

    private companion object {
        const val BYTE = 0xff
    }
}
