package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.Field

/**
 * WHAT A WORK IS MADE OF, AS THE STAGES OF THE TASKS OVER THE WORKS READ IT (owner, 2026-11-21: "a normal, finished
 * solution without hacks, with a self-evident decomposition of dependencies"): every kind of data of a work has the
 * date it last changed in its row of the index of the list ([column]) — of the [outer] ones the date their writes keep
 * with the rows of the databases, brought into the document as it is written ([ListRows]); of the others the date the
 * stage that makes them gave them, where they came out other than they were ([WorkStage.output]). A stage reads kinds
 * of data, never fields: nothing here tells which field reads which dictionary.
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
    /**
     * Which normalized authors and series its work is of and which works those hold, by the user's word: authors merged
     * or taken apart, works put in a series or out of it, a work of theirs removed.
     */
    GROUPS(true),
    /** The vectors of the site's text fields. */
    VECTORS(false),
    /** The vectors of the windows of its chapters. */
    WINDOWS(false),
    /** The vectors of the names of its cast. */
    CAST(false),
    /** The vectors of its sets: its chapters', reviews', screenshots'. */
    SETS(false),
    /** The values the models of the fields give it. */
    FIELDS(false),
    /** The manners of the texts of its work, of its authors' and of its series' works. */
    MANNERS(false);

    /** The column of the row of an item of the date the data last changed, milliseconds. */
    val column: Field<Long?> = ITEM.columnOf("${name.lowercase()}_at")
}

/**
 * A STAGE OF THE TASKS OVER THE WORKS (docs/computations.md, 9–11): what it [reads], the stages it waits for ([after] —
 * none of its works is taken before they did every change it reads up to the same date), what it makes ([output]), and
 * the column of an item's row telling the sweep that last did it ([column]: what a sweep passes over once done). Each
 * stage keeps of every source and scope the date it did every change up to ([StageSweeps]).
 */
enum class WorkStage(
    val reads: List<WorkDatum>,
    val after: List<WorkStage>,
    val output: WorkDatum?,
    /** The reason the stages after it are planned for as it filled its works. */
    val reason: String,
) {
    VECTORS(
        listOf(WorkDatum.DATA, WorkDatum.ANSWERS),
        emptyList(),
        WorkDatum.VECTORS,
        "texts",
    ),
    WINDOWS(listOf(WorkDatum.CHAPTERS), emptyList(), WorkDatum.WINDOWS, "parts"),
    CAST(listOf(WorkDatum.DATA), emptyList(), WorkDatum.CAST, "castNames"),
    SETS(
        listOf(WorkDatum.WINDOWS, WorkDatum.REVIEWS, WorkDatum.PICTURES),
        listOf(WINDOWS),
        WorkDatum.SETS,
        "setVectors",
    ),
    FIELDS(
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
    // The manners of the texts: of the work's own items, of its authors' and its series' works
    MANNERS(
        listOf(WorkDatum.DATA, WorkDatum.CHAPTERS, WorkDatum.MEMBERS, WorkDatum.GROUPS),
        emptyList(),
        WorkDatum.MANNERS,
        "manner",
    ),
    PREDICTED(
        listOf(
            WorkDatum.DATA,
            WorkDatum.ANSWERS,
            WorkDatum.GRADES,
            WorkDatum.MEMBERS,
            WorkDatum.FIELDS,
            WorkDatum.VECTORS,
            WorkDatum.PICTURES,
            WorkDatum.SETS,
            WorkDatum.MANNERS,
        ),
        listOf(FIELDS, MANNERS),
        null,
        "predicted",
    ),
    // The documents of the search by words: what is searched by meaning, the windows' full-text vectors, is not in them
    TEXTS(listOf(WorkDatum.DATA, WorkDatum.ANSWERS, WorkDatum.CHAPTERS), emptyList(), null, "texts");

    /** The column of the row of an item of the sweep of the stage that last did it, milliseconds; none of never. */
    val column: Field<Long?> = ITEM.columnOf("${name.lowercase()}_done")

    companion object {
        /**
         * The stages what [changed] reaches: those reading it, and those reading what a stage reached makes — the
         * windows of the chapters changed reach the sets, the sets the fields, the fields the expectations.
         */
        fun reached(changed: Collection<WorkDatum>): Set<WorkStage> {
            val reached = HashSet<WorkStage>()
            val data = changed.toHashSet()
            var grown = true
            while (grown) {
                val more = entries.filter { it !in reached && it.reads.any { datum -> datum in data } }
                more.forEach { stage -> stage.output?.let { data += it } }
                grown = reached.addAll(more)
            }
            return reached
        }
    }
}

/**
 * Of an item, the sweep of every stage that last did it ([WorkStage.column]: the date the sweep began, milliseconds;
 * none of never): what a sweep passes over once done of it, carried as the item's row is written anew.
 */
class WorkStages(private val at: Map<WorkStage, Long>) {
    /** The sweep of the [stage] that last did the item; nought of none. */
    operator fun get(stage: WorkStage): Long = at[stage] ?: 0L

    companion object {
        /** Of no stage done. */
        val NONE = WorkStages(emptyMap())

        /** Of the items [ids] of the [source], the stages kept, as written so far; an item of no row left out. */
        fun of(index: ListIndex, source: String, ids: Collection<Long>): Map<Long, WorkStages> = index.written { db ->
            val out = HashMap<Long, WorkStages>()
            val columns = WorkStage.entries.map { it.column }
            ListAmong.of(db, ids.map { ItemKey(source, it).toString() }) {
                db.select(listOf(ITEM.ITEM_KEY) + columns).from(ITEM).where(ListIndex.AMONG).fetch { r ->
                    out[ItemKey.parse(r.get(ITEM.ITEM_KEY)!!).id] =
                        WorkStages(WorkStage.entries.associateWith { r.get(it.column) ?: 0L })
                }
            }
            out
        }
    }
}

/** The items [keys] of the [index] done of the [stage] by the sweep begun [at] — in place, an item of no row left. */
fun WorkStage.done(index: ListIndex, keys: Collection<ItemKey>, at: Long) = index.set(column, keys.associateWith { at })

/** The column of the table of the items of the [name]: of the script of the store, else no column of it at all. */
private fun io.github.vlsergey.recommend4me.database.worklist.tables.Item.columnOf(name: String): Field<Long?> =
    @Suppress("UNCHECKED_CAST") (field(name.uppercase()) as Field<Long?>?)
        ?: error("No column $name of the list's items")
