package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.index.DocValues
import org.apache.lucene.index.Term
import org.apache.lucene.search.DocIdSetIterator

/**
 * WHAT A WORK IS MADE OF, AS THE STAGES OF THE TASKS OVER THE WORKS READ IT (owner, 2026-11-21: "a normal, finished
 * solution without hacks, with a self-evident decomposition of dependencies"): every kind of data of a work has the
 * date it last changed in its document of the index of the list ([field]) — of the [outer] ones the date their writes
 * keep with the rows of the databases, brought into the document as it is written ([ListRows]); of the others the date
 * the stage that makes them gave them, where they came out other than they were ([WorkStage.output]). A stage reads
 * kinds of data, never fields: nothing here tells which field reads which dictionary.
 */
enum class WorkDatum(val outer: Boolean) {
    /** What the site says of the item — its head, values, numbers, texts — and what the dictionaries draft of it. */
    DATA(true),
    /** The texts of its chapters. */
    CHAPTERS(true),
    /** The reviews of it. */
    REVIEWS(true),
    /** Its pictures analysed. */
    PICTURES(true),
    /** The user's word on the values and the fields of its work. */
    ANSWERS(true),
    /** The user's grade and mark of interest of its work. */
    GRADES(true),
    /** Which items its work is of. */
    MEMBERS(true),
    /** The vectors of the site's text fields. */
    VECTORS(false),
    /** The vectors of the windows of its chapters. */
    WINDOWS(false),
    /** The vectors of the names of its cast. */
    CAST(false),
    /** The vectors of its sets: its chapters', reviews', screenshots'. */
    SETS(false),
    /** The values the models of the fields give it. */
    FIELDS(false);

    /** The field of the document of the date the data last changed, milliseconds. */
    val field = "at:${name.lowercase()}"
}

/**
 * A STAGE OF THE TASKS OVER THE WORKS (docs/computations.md, 9–11): what it [reads], the stages it waits for ([after] —
 * none of its works is taken before they did every change it reads up to the same date), what it makes ([output]), and
 * the field of a document telling the sweep that last did it ([field]: what a sweep passes over once done of it). Each
 * stage keeps of every source and scope the date it did every change up to ([StageSweeps]).
 */
enum class WorkStage(
    val field: String,
    val reads: List<WorkDatum>,
    val after: List<WorkStage>,
    val output: WorkDatum?,
    /** The reason the stages after it are planned for as it filled its works. */
    val reason: String,
) {
    VECTORS(
        ListDocuments.VECTORS_AT,
        listOf(WorkDatum.DATA, WorkDatum.ANSWERS),
        emptyList(),
        WorkDatum.VECTORS,
        "texts",
    ),
    WINDOWS(ListDocuments.WINDOWS_AT, listOf(WorkDatum.CHAPTERS), emptyList(), WorkDatum.WINDOWS, "parts"),
    CAST(ListDocuments.CAST_AT, listOf(WorkDatum.DATA), emptyList(), WorkDatum.CAST, "castNames"),
    SETS(
        ListDocuments.SETS_AT,
        listOf(WorkDatum.WINDOWS, WorkDatum.REVIEWS, WorkDatum.PICTURES),
        listOf(WINDOWS),
        WorkDatum.SETS,
        "setVectors",
    ),
    FIELDS(
        ListDocuments.FIELDS_AT,
        listOf(
            WorkDatum.DATA,
            WorkDatum.ANSWERS,
            WorkDatum.VECTORS,
            WorkDatum.PICTURES,
            WorkDatum.SETS,
            WorkDatum.CAST,
        ),
        listOf(VECTORS, SETS, CAST),
        WorkDatum.FIELDS,
        "fields",
    ),
    PREDICTED(
        ListDocuments.PREDICTED_AT,
        listOf(
            WorkDatum.DATA,
            WorkDatum.ANSWERS,
            WorkDatum.GRADES,
            WorkDatum.MEMBERS,
            WorkDatum.FIELDS,
            WorkDatum.VECTORS,
            WorkDatum.PICTURES,
            WorkDatum.SETS,
        ),
        listOf(FIELDS),
        null,
        "predicted",
    ),
    TEXTS(
        ListDocuments.TEXTS_AT,
        listOf(WorkDatum.DATA, WorkDatum.ANSWERS, WorkDatum.CHAPTERS, WorkDatum.VECTORS, WorkDatum.WINDOWS),
        listOf(VECTORS, WINDOWS),
        null,
        "texts",
    );

    /** Whether the stage is to look again of what [changed]: it reads it. */
    fun follows(changed: Collection<WorkDatum>): Boolean = changed.any { it in reads }
}

/**
 * Of a document, the sweep of every stage that last did it ([WorkStage.field]: the date the sweep began, milliseconds;
 * none of never): what a sweep passes over once done of it, carried as the document is made anew.
 */
class WorkStages(private val at: Map<WorkStage, Long>) {
    /** The sweep of the [stage] that last did the document; nought of none. */
    operator fun get(stage: WorkStage): Long = at[stage] ?: 0L

    /** The stages as the fields of a document, updated in place. */
    fun fields() = WorkStage.entries.map { NumericDocValuesField(it.field, get(it)) }.toTypedArray()

    companion object {
        /** Of no stage done. */
        val NONE = WorkStages(emptyMap())

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
                    val fields = WorkStage.entries.associateWith { DocValues.getNumeric(r, it.field) }
                    found.forEach { (doc, id) ->
                        out[id] =
                            WorkStages(fields.mapValues { (_, v) -> if (v.advanceExact(doc)) v.longValue() else 0L })
                    }
                }
                out
            }
    }
}

/** The items [keys] of the [index] done of the [stage] by the sweep begun [at] — in place, a document of none left. */
fun WorkStage.done(index: ListIndex, keys: Collection<ItemKey>, at: Long) =
    index.update(keys.associateWith { arrayOf(NumericDocValuesField(field, at)) })
