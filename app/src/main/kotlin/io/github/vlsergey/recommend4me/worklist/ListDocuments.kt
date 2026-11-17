package io.github.vlsergey.recommend4me.worklist

import org.apache.lucene.document.Document
import org.apache.lucene.document.DoubleDocValuesField
import org.apache.lucene.document.DoubleField
import org.apache.lucene.document.Field
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.SortedDocValuesField
import org.apache.lucene.document.StringField
import org.apache.lucene.util.BytesRef

/**
 * AN ITEM AS A DOCUMENT OF THE INDEX OF THE LIST ([ListIndex]): its [KEY] and [SOURCE]; the [WORK] it is of, which the
 * rows are grouped by, and its [RANK] among the items of the work; whether the work is of several items ([LINKED]); its
 * dates; the values of every filter facet as terms ([facetField]), an item of none of a facet the term [NONE]; every
 * number as a point and a sorted value ([numberField]); and what changes of its work without it, updated in place
 * ([valueFields]) — a value of none a -∞, last of a list sorted down.
 */
object ListDocuments {
    const val KEY = "key"
    const val SOURCE = "source"
    const val WORK = "work"
    const val RANK = "rank"
    const val LINKED = "linked"
    const val UPDATED = "updated"
    const val ADDED = "added"
    const val GRADED = "graded"
    const val PREDICTION = "prediction"
    const val INTEREST = "interest"
    const val EXPECTED = "expected"

    /** The term of an item of a work of several items. */
    const val YES = "1"

    /** The term of an item that has no value of a facet. */
    const val NONE = "\u0000"

    fun facetField(facetId: String) = "f:$facetId"

    fun numberField(numberId: String) = "n:$numberId"

    /** The document of the [row]. */
    fun of(row: ListRow): Document {
        val doc = Document()
        doc.add(StringField(KEY, row.key.toString(), Field.Store.YES))
        doc.add(StringField(SOURCE, row.key.source, Field.Store.NO))
        doc.add(SortedDocValuesField(WORK, BytesRef(row.work.toString())))
        doc.add(NumericDocValuesField(RANK, row.rank.toLong()))
        if (row.linked) doc.add(StringField(LINKED, YES, Field.Store.NO))
        doc.add(NumericDocValuesField(UPDATED, row.updatedAt.toEpochMilli()))
        doc.add(NumericDocValuesField(ADDED, row.firstSeenAt.toEpochMilli()))
        valueFields(row.values).forEach(doc::add)
        row.facets.forEach { (facetId, keys) ->
            val field = facetField(facetId)
            if (keys.isEmpty()) doc.add(StringField(field, NONE, Field.Store.NO))
            keys.forEach { doc.add(StringField(field, it, Field.Store.NO)) }
        }
        row.numbers.forEach { (numberId, value) -> doc.add(DoubleField(numberField(numberId), value, Field.Store.NO)) }
        return doc
    }

    /** The values of [values] as the fields updated in place. */
    fun valueFields(values: RowValues): Array<Field> =
        arrayOf(
            NumericDocValuesField(GRADED, if (values.graded) 1L else 0L),
            DoubleDocValuesField(PREDICTION, values.prediction ?: Double.NEGATIVE_INFINITY),
            DoubleDocValuesField(INTEREST, values.interest ?: Double.NEGATIVE_INFINITY),
            DoubleDocValuesField(EXPECTED, values.expected ?: Double.NEGATIVE_INFINITY),
        )
}
