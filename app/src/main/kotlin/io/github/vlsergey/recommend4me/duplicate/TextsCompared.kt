package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.ByteMatrix
import io.github.vlsergey.recommend4me.matrix.Rows
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.worklist.HeldTexts
import io.github.vlsergey.recommend4me.worklist.ListFacetValues
import io.github.vlsergey.recommend4me.worklist.ListIndex
import io.github.vlsergey.recommend4me.worklist.ListTexts
import org.apache.lucene.util.BytesRef
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(TextsCompared::class.java)

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
         * Of the works [ids] of the source [s], ascending, as the [index] of the list holds them (owner, 2026-11-22:
         * "everything the search of the duplicates needs taken from the index"): the vectors of their titles and their
         * descriptions by the [encoder], their description and their line of the characters as the user corrected them,
         * the names the site gives their fandoms — of every work of a site in one pass of the index, of a few by their
         * keys —, the letters.
         */
        fun read(s: SourceStore, encoder: String, ids: List<String>, index: ListIndex): TextsCompared {
            val at = HashMap<String, Int>(ids.size * 2).also { m -> ids.forEachIndexed { i, id -> m[id] = i } }
            val description = ListTexts.descriptionOf(s.schema)
            val titles = UnitRows(ids.size)
            val descriptions = UnitRows(ids.size)
            val texts = LetteredText.entries.associateWith { arrayOfNulls<String>(ids.size) }
            // Every doc value read, by its place: the vectors' rows, the texts' arrays
            val read: List<Pair<String, (Int, BytesRef) -> Unit>> =
                listOfNotNull(
                    ListTexts.vectorField(encoder, TextVectors.TITLE) to titles::put,
                    description?.let { ListTexts.vectorField(encoder, it) to descriptions::put },
                    description?.let {
                        ListTexts.textField(it) to texts.getValue(LetteredText.DESCRIPTION).putter()
                    },
                    s.schema.universeLine?.let {
                        ListTexts.textField(it) to texts.getValue(LetteredText.CHARACTERS).putter()
                    },
                )
            val fields = read.map { it.first }
            val started = System.nanoTime()
            if (ids.size > FEW)
                HeldTexts.forEvery(index, s.id, fields) { id, k, bytes -> at[id]?.let { read[k].second(it, bytes) } }
            else
                HeldTexts.of(index, ids.map { ItemKey(s.id, it) }, fields).forEach { (key, own) ->
                    val i = at[key.id] ?: return@forEach
                    own.forEach { (field, bytes) -> read[fields.indexOf(field)].second(i, BytesRef(bytes)) }
                }
            val streamed = System.nanoTime()
            fandomsOf(s, ids, at, index)?.copyInto(texts.getValue(LetteredText.FANDOMS))
            val named = System.nanoTime()
            // The description long, every row whole; the names of the fandoms and the line of the characters short
            val rows =
                LetteredText.entries.map {
                    val own = texts.getValue(it).asList()
                    if (it == LetteredText.DESCRIPTION) Letters.wholeRows(own) else Letters.rows(own)
                }
            if (ids.size > FEW)
                log.info(
                    "{}: the texts compared both ways read of the index in {} ms: the vectors and the texts {}, the " +
                        "fandoms {}, their letters {}",
                    s.id,
                    (System.nanoTime() - started) / NANOS_IN_MILLI,
                    (streamed - started) / NANOS_IN_MILLI,
                    (named - streamed) / NANOS_IN_MILLI,
                    (System.nanoTime() - named) / NANOS_IN_MILLI,
                )
            return TextsCompared(
                titles.rows(),
                titles.had,
                descriptions.rows(),
                descriptions.had,
                rows.map { it.first },
                rows.map { it.second },
            )
        }

        /** The texts of the works by their places as a doc value of the index holds them: its UTF-8, a blank none. */
        private fun Array<String?>.putter(): (Int, BytesRef) -> Unit = { i, b ->
            this[i] = b.utf8ToString().takeIf { it.isNotBlank() }
        }

        /**
         * The names the site gives the fandoms of the works [ids] as the [index] of the list holds them, one after
         * another; none of a work of none, and of a site of no fandoms. The names read by their keys.
         */
        private fun fandomsOf(
            s: SourceStore,
            ids: List<String>,
            at: Map<String, Int>,
            index: ListIndex,
        ): Array<String?>? {
            val key = UniverseFacet.fandomFacet(s.schema) ?: return null
            val def = s.schema.facets.first { it.key == key }
            val facetId = FeatureNames.facetId(s.source, def)
            val fandoms = arrayOfNulls<List<String>>(ids.size)
            if (ids.size > FEW) {
                val every = ListFacetValues.everyItem(index, s.id, listOf(facetId), emptyList())
                val keyOf = every.names.map { it.removePrefix("$facetId:") }
                every.ids.forEachIndexed { k, id ->
                    val own = every.values[k]
                    if (own.isNotEmpty()) at[id]?.let { i -> fandoms[i] = own.map(keyOf::get) }
                }
            } else
                ListFacetValues.of(index, ids.map { ItemKey(s.id, it) }).forEach { (item, facets) ->
                    facets[facetId]?.let { keys -> at[item.id]?.let { fandoms[it] = keys } }
                }
            val names = s.names(key, fandoms.flatMap { it.orEmpty() }.distinct())
            return Array(ids.size) { i ->
                fandoms[i]?.takeIf { it.isNotEmpty() }?.joinToString(", ") { names[it] ?: it }
            }
        }

        private const val NANOS_IN_MILLI = 1_000_000L

        /** Of more works than so many, every work's texts streamed and picked from: one pass, not a query a chunk. */
        private const val FEW = 10_000
    }
}

/**
 * The vectors of one text of [count] works as they come, a unit row a byte a number ([ByteMatrix]) as the index of the
 * list holds them ([ListTexts.packed]), of the numbers of the first one; which have one ([had]). A vector of another
 * number of numbers passed over.
 */
private class UnitRows(private val count: Int) {
    private var matrix: ByteMatrix? = null
    val had = BooleanArray(count)

    /** The vector of the [i]-th work as the index holds it. */
    fun put(i: Int, packed: BytesRef) {
        val into = matrix ?: ByteMatrix(count, ByteMatrix.packedCols(packed.length)).also { matrix = it }
        if (into.putPacked(i, packed.bytes, packed.offset, packed.length)) had[i] = true
    }

    /** The rows read; of none, rows of no column. */
    fun rows(): ByteMatrix = matrix ?: ByteMatrix(count, 0)
}
