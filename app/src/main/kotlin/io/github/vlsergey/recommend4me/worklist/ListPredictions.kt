package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.interest.Interest
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.Predicted
import io.github.vlsergey.recommend4me.source.TypeStore
import org.apache.lucene.document.DoubleDocValuesField
import org.apache.lucene.index.DocValues
import org.apache.lucene.index.IndexReader
import org.apache.lucene.index.LeafReader
import org.apache.lucene.index.NumericDocValues
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator

/**
 * THE PREDICTIONS OF THE WORKS, KEPT IN THE INDEX OF THE LIST ALONE (owner, 2026-11-21: "do we need the table of the
 * predictions? isn't it simpler to keep them in the index at once?"): of every item, its work's grade by the model of
 * the grades and its chance of interest by the model of interest — the values of its document
 * ([ListDocuments.PREDICTION], [ListDocuments.MODEL_INTEREST]), to their digits ([ListDocuments.kept]), written in
 * place where they changed so ([write]): a change of the last digits writes nothing. Recomputable: an index made anew
 * has every stage of the tasks over the works to be done again, its predictions among them. Read by the items' keys —
 * the cards — or of every item of a source in one pass of its documents.
 */
object ListPredictions {

    /** Of the items [ids] of the [source], their predictions as the [reader] holds them; an item of none left out. */
    fun of(reader: IndexReader, source: String, ids: Collection<String>): Map<String, Predicted> {
        val out = HashMap<String, Predicted>()
        reader.leaves().forEach { leaf ->
            val r = leaf.reader()
            // Of every key its live document, read in the order of the documents: the values are read forward alone
            val found = sortedMapOf<Int, String>()
            ids.forEach { id ->
                val docs = r.postings(Term(ListDocuments.KEY, ItemKey(source, id).toString())) ?: return@forEach
                var doc = docs.nextDoc()
                while (doc != DocIdSetIterator.NO_MORE_DOCS && r.liveDocs?.get(doc) == false) doc = docs.nextDoc()
                if (doc != DocIdSetIterator.NO_MORE_DOCS) found[doc] = id
            }
            val values = Values(r)
            found.forEach { (doc, id) -> values.of(doc)?.let { out[id] = it } }
        }
        return out
    }

    /** Of every item of the [source] with a prediction, its prediction as the [reader] holds it, in one pass. */
    fun ofEvery(reader: IndexReader, source: String): Map<String, Predicted> {
        val out = HashMap<String, Predicted>()
        reader.leaves().forEach { leaf ->
            val r = leaf.reader()
            val keys = DocValues.getSorted(r, ListDocuments.KEY)
            val values = Values(r)
            val docs = r.postings(Term(ListDocuments.SOURCE, source)) ?: return@forEach
            var doc = docs.nextDoc()
            while (doc != DocIdSetIterator.NO_MORE_DOCS) {
                if (r.liveDocs?.get(doc) != false && keys.advanceExact(doc)) {
                    val key = ItemKey.parse(keys.lookupOrd(keys.ordValue()).utf8ToString())
                    values.of(doc)?.let { out[key.id] = it }
                }
                doc = docs.nextDoc()
            }
        }
        return out
    }

    /**
     * The [predicted] of a portion of the items of the [type] written in place into the [index] where they changed as
     * it keeps them — their grade, their chance of interest, to their digits —, with the other values of their works as
     * the databases say them ([ListRows.valuesOf]); not committed. Answers the items whose prediction was written.
     */
    fun write(index: ListIndex, type: TypeStore, predicted: List<Pair<ItemKey, Predicted>>): List<ItemKey> {
        val changed = ArrayList<ItemKey>()
        predicted.groupBy({ it.first.source }, { it.first.id to it.second }).forEach { (source, made) ->
            val s = type.source(source) ?: return@forEach
            val kept = index.written { of(it, source, made.map { m -> m.first }) }
            val other = made.filter { (id, p) -> kept[id]?.let(::kept) != kept(p) }.toMap()
            if (other.isEmpty()) return@forEach
            val values = ListRows.valuesOf(s, other)
            index.update(values.entries.associate { (id, v) -> ItemKey(source, id) to ListDocuments.valueFields(v) })
            other.keys.forEach { changed += ItemKey(source, it) }
        }
        return changed
    }

    /**
     * Every prediction of the [type] gone from the [index] — of no model of the grades any more —, the chance of
     * interest of a work the user marked left as the mark says it; not committed.
     */
    fun forget(index: ListIndex, type: TypeStore) {
        val none = Double.NEGATIVE_INFINITY
        type.sources.forEach { s ->
            index.updateEvery(
                s.id,
                arrayOf(
                    DoubleDocValuesField(ListDocuments.PREDICTION, none),
                    DoubleDocValuesField(ListDocuments.MODEL_INTEREST, none),
                    DoubleDocValuesField(ListDocuments.EXPECTED, none),
                    DoubleDocValuesField(ListDocuments.INTEREST, none),
                ),
            )
            index.update(
                s.interest.all().entries.associate { (id, mark) ->
                    ItemKey(s.id, id) to
                        arrayOf(DoubleDocValuesField(ListDocuments.INTEREST, Interest.chance(mark.interesting, null)!!))
                }
            )
        }
    }

    /** The prediction as the index keeps it: its grade and its chance of interest, to their digits. */
    private fun kept(p: Predicted) = ListDocuments.kept(p.score) to ListDocuments.kept(p.interest)

    /** The predictions of the documents of one segment, read forward. */
    private class Values(r: LeafReader) {
        private val scores: NumericDocValues = DocValues.getNumeric(r, ListDocuments.PREDICTION)
        private val interest: NumericDocValues = DocValues.getNumeric(r, ListDocuments.MODEL_INTEREST)

        /** The prediction of the document [doc], none of a work never predicted. */
        fun of(doc: Int): Predicted? {
            val score = if (scores.advanceExact(doc)) java.lang.Double.longBitsToDouble(scores.longValue()) else null
            if (score == null || !score.isFinite()) return null
            val chance =
                if (interest.advanceExact(doc)) java.lang.Double.longBitsToDouble(interest.longValue()) else null
            return Predicted(score, chance?.takeIf { it.isFinite() })
        }
    }
}
