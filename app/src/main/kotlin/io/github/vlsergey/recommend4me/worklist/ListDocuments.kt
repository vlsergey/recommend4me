package io.github.vlsergey.recommend4me.worklist

import java.math.BigDecimal
import java.math.MathContext
import org.apache.lucene.document.BinaryDocValuesField
import org.apache.lucene.document.Document
import org.apache.lucene.document.DoubleDocValuesField
import org.apache.lucene.document.DoubleField
import org.apache.lucene.document.Field
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.document.SortedDocValuesField
import org.apache.lucene.document.SortedSetDocValuesField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.util.BytesRef

/**
 * AN ITEM AS A DOCUMENT OF THE INDEX OF THE LIST ([ListIndex]): its [KEY] and [SOURCE]; the [WORK] it is of, which the
 * rows are grouped by, and its [RANK] among the items of the work; whether the work is of several items ([LINKED]); its
 * dates; the values of every filter facet as terms ([facetField]), kept with the document too, an item of none of a
 * facet the term [NONE]; every number as a point and a sorted value ([numberField]); and what changes of its work
 * without it, updated in place ([valueFields]) — a value of none a -∞, last of a list sorted down.
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

    /** Whether the user marked the item's work ([RowValues.marked]): 1 or 0, updated in place. */
    const val MARKED = "marked"

    /**
     * When the tasks over the works last did the item ([WorkStages]): the vectors of its site's text fields made, its
     * fields computed, its grade and interest expected, its document of the search written — milliseconds, nought of
     * never; updated in place.
     */
    const val VECTORS_AT = "vectorsAt"
    const val FIELDS_AT = "fieldsAt"
    const val PREDICTED_AT = "predictedAt"
    const val TEXTS_AT = "textsAt"

    /** The item's title as the user corrected it: of the doc values, what the search of duplicates reads. */
    const val TITLE = "title"

    /** The term of an item of a work of several items. */
    const val YES = "1"

    private const val FACET = "f:"

    /** The term of an item that has no value of a facet. */
    const val NONE = "\u0000"

    fun facetField(facetId: String) = "$FACET$facetId"

    /** The field of the doc values of the filter facet [facetId]'s values ([ListFacetValues.everyItem]). */
    fun valuesField(facetId: String) = "v:$facetId"

    /** The filter id of the facet of the field [name]; null of a field of no facet. */
    fun facetIdOf(name: String): String? = name.takeIf { it.startsWith(FACET) }?.substring(FACET.length)

    fun numberField(numberId: String) = "n:$numberId"

    private const val MODEL = "m:"

    /**
     * The field the values the models give an item of the facet [facet] are kept in, by the facet's key — of every
     * facet the source works out, a filter or not: the one place they are kept (user, 2026-11-18).
     */
    fun modelField(facet: String) = "$MODEL$facet"

    /** The key of the facet of the field of the models' values [name]; null of another field. */
    fun modelFacetOf(name: String): String? = name.takeIf { it.startsWith(MODEL) }?.substring(MODEL.length)

    /** The document of the [row]. */
    fun of(row: ListRow): Document {
        val doc = Document()
        doc.add(StringField(KEY, row.key.toString(), Field.Store.YES))
        // The key and every value as doc values too: what a computation over every work reads in one pass, a value by
        // its number ([ListFacetValues.everyItem])
        doc.add(SortedDocValuesField(KEY, BytesRef(row.key.toString())))
        doc.add(StringField(SOURCE, row.key.source, Field.Store.NO))
        doc.add(SortedDocValuesField(WORK, BytesRef(row.work.toString())))
        doc.add(StringField(WORK, row.work.toString(), Field.Store.NO))
        doc.add(NumericDocValuesField(RANK, row.rank.toLong()))
        if (row.linked) doc.add(StringField(LINKED, YES, Field.Store.NO))
        doc.add(NumericDocValuesField(UPDATED, row.updatedAt.toEpochMilli()))
        doc.add(NumericDocValuesField(ADDED, row.firstSeenAt.toEpochMilli()))
        row.stages.fields().forEach(doc::add)
        valueFields(row.values).forEach(doc::add)
        row.facets.forEach { (facetId, keys) ->
            val field = facetField(facetId)
            if (keys.isEmpty()) doc.add(StringField(field, NONE, Field.Store.NO))
            // Kept with the document too: the cards of a page read them by the items' keys ([ListFacetValues])
            // The values as doc values in a field of their own: a field of the mark of none has no doc values, and a
            // field is one kind of field in every document
            keys.forEach {
                doc.add(StringField(field, it, Field.Store.YES))
                doc.add(SortedSetDocValuesField(valuesField(facetId), BytesRef(it)))
            }
        }
        row.numbers.forEach { (numberId, value) -> doc.add(DoubleField(numberField(numberId), value, Field.Store.NO)) }
        row.model.forEach { (facet, keys) -> keys.forEach { doc.add(StoredField(modelField(facet), it)) } }
        doc.add(BinaryDocValuesField(TITLE, BytesRef(row.title)))
        return doc
    }

    /** The values of [values] as the fields updated in place, each to its [DIGITS] ([kept]). */
    fun valueFields(values: RowValues): Array<Field> =
        arrayOf(
            NumericDocValuesField(GRADED, if (values.graded) 1L else 0L),
            NumericDocValuesField(MARKED, if (values.marked) 1L else 0L),
            DoubleDocValuesField(PREDICTION, kept(values.prediction) ?: Double.NEGATIVE_INFINITY),
            DoubleDocValuesField(INTEREST, kept(values.interest) ?: Double.NEGATIVE_INFINITY),
            DoubleDocValuesField(EXPECTED, kept(values.expected) ?: Double.NEGATIVE_INFINITY),
        )

    /**
     * A prediction, a chance, an expectation as the index keeps it: to its first [DIGITS] (owner, 2026-11-21: "the
     * grade and the interest are three digits") — a change beyond them is none of the list's, its document not written
     * for it ([io.github.vlsergey.recommend4me.model.PredictionRepository.write]).
     */
    fun kept(value: Double?): Double? =
        if (value == null || value == 0.0 || !value.isFinite()) value
        else BigDecimal(value).round(MathContext(DIGITS)).toDouble()

    /** The digits a value of the list is kept to. */
    const val DIGITS = 3
}
