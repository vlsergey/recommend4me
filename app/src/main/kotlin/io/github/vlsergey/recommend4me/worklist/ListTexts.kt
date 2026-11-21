package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.ByteMatrix
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.vector.Vectors
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator
import org.apache.lucene.util.BytesRef

/**
 * THE TEXTS OF AN ITEM ITS DOCUMENT OF THE INDEX OF THE LIST HOLDS WHOLE, AND THE VECTORS OF ITS TEXTS (owner,
 * 2026-11-22: "everything the search of the duplicates needs taken from the index"; "every text — the fields, not the
 * texts of the chapters — in the index of Lucene"): every text of its own as the user corrected it ([textKeys]), and
 * the vectors of its title and its description by the encoder of now ([vectorKeys]), each of unit length, a byte a
 * number with its scale ([ByteMatrix.pack]) — what a computation over every work reads in one pass of the index, no
 * database read: of the 1.4 million works of ficbook the texts read of the database, a seek of the disk a row, took
 * twelve minutes of a search (2026-11-22).
 */
object ListTexts {

    /** The key of the description among the texts of a source of the [schema]; none of a source of none. */
    fun descriptionOf(schema: SourceSchema): String? = schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key

    /**
     * The texts a document holds whole, by their keys: every text the site gives a work — its own fields, not the texts
     * of its chapters — and the line of the characters.
     */
    fun textKeys(schema: SourceSchema): List<String> =
        (schema.texts.map { it.key } + listOfNotNull(schema.universeLine)).distinct()

    /** The texts a document holds the vectors of, by their keys: the title and the description. */
    fun vectorKeys(schema: SourceSchema): List<String> = listOfNotNull(TextVectors.TITLE, descriptionOf(schema))

    /** The vector as a document holds it, of its half-precision numbers as kept ([Vectors.fromHalf]). */
    fun packedHalves(halves: ByteArray): ByteArray = packed(Vectors.fromHalf(halves))

    /** The vector [v] as a document holds it: of unit length, a byte a number with its scale. */
    fun packed(v: FloatArray): ByteArray {
        var norm = 0.0
        for (x in v) norm += x.toDouble() * x
        val length = kotlin.math.sqrt(norm).toFloat()
        val unit = if (length > 0f) FloatArray(v.size) { v[it] / length } else v
        return ByteMatrix.pack(unit)
    }

    /**
     * Of every item of the [source] the index holds, the value of each of the doc values [fields] it has, by the
     * field's place among them, as the index holds it — handed on at once, the bytes valid till the next is.
     */
    fun forEvery(index: ListIndex, source: String, fields: List<String>, action: (String, Int, BytesRef) -> Unit) =
        index.searching { s ->
            s.indexReader.leaves().forEach { leaf ->
                val r = leaf.reader()
                val keys = r.getSortedDocValues(ListDocuments.KEY) ?: return@forEach
                val values = fields.map { r.getBinaryDocValues(it) }
                if (values.all { it == null }) return@forEach
                val docs = r.postings(Term(ListDocuments.SOURCE, source)) ?: return@forEach
                val live = r.liveDocs
                var doc = docs.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                    if ((live == null || live.get(doc)) && keys.advanceExact(doc)) {
                        val id = ItemKey.parse(keys.lookupOrd(keys.ordValue()).utf8ToString()).id
                        values.forEachIndexed { k, dv ->
                            if (dv != null && dv.advanceExact(doc)) action(id, k, dv.binaryValue())
                        }
                    }
                    doc = docs.nextDoc()
                }
            }
        }

    /** Of the items [keys], the value of each of the doc values [fields] they have, by the field: as committed. */
    fun of(index: ListIndex, keys: Collection<ItemKey>, fields: List<String>): Map<ItemKey, Map<String, ByteArray>> =
        index.searching {
            of(it.indexReader, keys, fields)
        }

    /**
     * Of the items [keys], the value of each of the doc values [fields] they have, by the field, as the [reader] has
     * it.
     */
    fun of(reader: IndexReader, keys: Collection<ItemKey>, fields: List<String>): Map<ItemKey, Map<String, ByteArray>> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<ItemKey, HashMap<String, ByteArray>>()
        reader.leaves().forEach { leaf ->
            val r = leaf.reader()
            val live = r.liveDocs
            // Of every key its live document, in the order of the documents: the values are read forward alone
            val found = sortedMapOf<Int, ItemKey>()
            keys.forEach { key ->
                val docs = r.postings(Term(ListDocuments.KEY, key.toString())) ?: return@forEach
                var doc = docs.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                    if (live == null || live.get(doc)) found[doc] = key
                    doc = docs.nextDoc()
                }
            }
            fields.forEach { field ->
                val dv = r.getBinaryDocValues(field) ?: return@forEach
                found.forEach { (doc, key) ->
                    if (dv.advanceExact(doc))
                        out.getOrPut(key) { HashMap() }[field] = BytesRef.deepCopyOf(dv.binaryValue()).bytes
                }
            }
        }
        return out
    }
}
