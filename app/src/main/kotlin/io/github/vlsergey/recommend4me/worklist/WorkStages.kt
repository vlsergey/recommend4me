package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.index.DocValues
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator

/**
 * WHEN THE TASKS OVER THE WORKS LAST DID AN ITEM'S DOCUMENT (docs/computations.md, 9 and 10): its fields computed by
 * the models ([fieldsAt]), its grade and interest expected ([predictedAt]), its document of the search written
 * ([textsAt]) — milliseconds, nought of never. A stage is to be done again of an item done before what it depends on
 * changed: the models of its source's fields, the model of the grades, the index of the search made anew ([WorkStage]).
 * A document made anew of a changed item is of the stages its writer did — every other to be done.
 */
data class WorkStages(val fieldsAt: Long, val predictedAt: Long, val textsAt: Long) {
    /** The stages as the fields of a document, updated in place. */
    fun fields() =
        arrayOf(
            NumericDocValuesField(ListDocuments.FIELDS_AT, fieldsAt),
            NumericDocValuesField(ListDocuments.PREDICTED_AT, predictedAt),
            NumericDocValuesField(ListDocuments.TEXTS_AT, textsAt),
        )

    companion object {
        /** Of no stage done. */
        val NONE = WorkStages(0, 0, 0)

        /** Of the items [ids] of the [source], the stages kept, as written so far; an item of no document left out. */
        fun of(index: ListIndex, source: String, ids: Collection<String>): Map<String, WorkStages> =
            index.written { reader ->
                val out = HashMap<String, WorkStages>()
                reader.leaves().forEach { leaf ->
                    val r = leaf.reader()
                    val live = r.liveDocs
                    // Of every key its document not deleted — one made anew replaced another —, read in the order of
                    // the documents: the values are read forward alone
                    val found = sortedMapOf<Int, String>()
                    ids.forEach { id ->
                        val docs = r.postings(Term(ListDocuments.KEY, ItemKey(source, id).toString())) ?: return@forEach
                        var doc = docs.nextDoc()
                        while (doc != DocIdSetIterator.NO_MORE_DOCS && live != null && !live.get(doc)) doc =
                            docs.nextDoc()
                        if (doc != DocIdSetIterator.NO_MORE_DOCS) found[doc] = id
                    }
                    val fields = WorkStage.entries.map { DocValues.getNumeric(r, it.field) }
                    found.forEach { (doc, id) ->
                        val at = fields.map { if (it.advanceExact(doc)) it.longValue() else 0L }
                        out[id] = WorkStages(at[0], at[1], at[2])
                    }
                }
                out
            }
    }
}

/** A stage of the tasks over the works, and the field of a document that tells when it was done. */
enum class WorkStage(val field: String) {
    FIELDS(ListDocuments.FIELDS_AT),
    PREDICTED(ListDocuments.PREDICTED_AT),
    TEXTS(ListDocuments.TEXTS_AT);

    /** The items [keys] of the [index] done of this stage [at] — in place, a document of none left as it is. */
    fun done(index: ListIndex, keys: Collection<ItemKey>, at: Long) =
        index.update(keys.associateWith { arrayOf(NumericDocValuesField(field, at)) })
}
