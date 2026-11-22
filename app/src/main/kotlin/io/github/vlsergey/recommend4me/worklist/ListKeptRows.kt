package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_NUMBER
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_TEXT
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VALUE
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VECTOR
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext

/**
 * A DOCUMENT OF THE INDEX OF THE LIST AS IT IS WRITTEN, what a writing of a source whole compares the row read of the
 * databases with ([sameAs]): of a row alike nothing is written, and the stages of the tasks over its work stay (owner,
 * 2026-11-21: "read really everything, and rewrite what changed"). Its work and place in it, its dates, whether graded
 * and marked, its title, the values of every facet and every number — what the databases make of it; the values the
 * models gave it and its predictions are the index's own, read of it to make the row, not compared.
 */
class KeptRow(
    val work: String,
    val rank: Long,
    val linked: Boolean,
    val updated: Long,
    val added: Long,
    val graded: Long,
    val marked: Long,
    val title: String,
    val facets: Map<String, List<String>>,
    val numbers: Map<String, Double>,
    /** When every kind of data of the work last changed, as written ([WorkDatum]); a kind of none left out. */
    val dates: Map<WorkDatum, Long>,
    /** The stages of the tasks over its work, as written: not compared, kept of a document made anew. */
    val stages: WorkStages,
    /** Its texts held whole, by their keys ([ListTexts]). */
    val texts: Map<String, String> = emptyMap(),
    /** The vectors of its texts, of every encoder, by their fields ([ListTexts.vectorField]). */
    val vectors: Map<String, ByteArray> = emptyMap(),
)

/** Whether the document [kept] is this row's as the databases make it now: of the same row, nothing to write. */
fun ListRow.sameAs(kept: KeptRow): Boolean =
    work.toString() == kept.work &&
        rank.toLong() == kept.rank &&
        linked == kept.linked &&
        updatedAt.toEpochMilli() == kept.updated &&
        firstSeenAt.toEpochMilli() == kept.added &&
        (if (values.graded) 1L else 0L) == kept.graded &&
        (if (values.marked) 1L else 0L) == kept.marked &&
        title == kept.title &&
        valuesOf(facets) == valuesOf(kept.facets) &&
        numbers == kept.numbers &&
        dates.filterKeys { it.outer } == kept.dates.filterKeys { it.outer } &&
        holdsAlike(kept)

/** Whether the texts held whole and the vectors of the row's texts are those its document holds ([kept]). */
private fun ListRow.holdsAlike(kept: KeptRow): Boolean = texts == kept.texts && vectorsAlike(kept)

/**
 * Whether the vectors of the row's texts are those its document holds of their encoder; of a row of no encoder, alike
 * whatever the document holds.
 */
private fun ListRow.vectorsAlike(kept: KeptRow): Boolean {
    val own = vectors ?: return true
    val held = kept.vectors.filterKeys { it.startsWith(ListTexts.vectorField(own.encoder, "")) }
    val packed = own.packed.mapKeys { (key, _) -> ListTexts.vectorField(own.encoder, key) }
    return packed.keys == held.keys && packed.all { (field, bytes) -> bytes.contentEquals(held.getValue(field)) }
}

/**
 * Whether the values of the filter facets [filters] — what the dictionaries draft of the site's words — are other than
 * its document holds ([kept]): of a document other than written with its dates alike, a change of a dictionary, not of
 * what the documents held not before.
 */
fun ListRow.draftedOtherThan(kept: KeptRow, filters: Set<String>): Boolean =
    valuesOf(facets.filterKeys { it in filters }) != valuesOf(kept.facets.filterKeys { it in filters })

/**
 * The values of every facet of [facets] as a set — the order of a facet's values matters neither to the index nor to
 * the search (docs/duplicates.md, 15): the drafts of a work read by its key and of every work come in orders of their
 * own, and as lists they told a dictionary's change of 1.1 million works of ficbook none of which changed, every one of
 * them dated anew and its stages done again (2026-11-22) —; a facet of none left out.
 */
private fun valuesOf(facets: Map<String, List<String>>): Map<String, Set<String>> =
    facets.filterValues { it.isNotEmpty() }.mapValues { it.value.toSet() }

/**
 * The row over its document as written ([kept]): the stages that did it and the dates of what they made of it carried;
 * the row itself of a document of none.
 */
fun ListRow.over(kept: KeptRow?): ListRow = kept?.let { with(it.stages, it.dates) } ?: this

/** Whether a date the databases keep of the row is another than its document as written ([kept]) holds. */
fun ListRow.datedOtherThan(kept: KeptRow): Boolean = dates.filterKeys { it.outer } != kept.dates.filterKeys { it.outer }

/**
 * The outer kinds of data of the row whose dates are other than its document as written ([kept]) holds: every one of
 * none.
 */
fun ListRow.changedOf(kept: KeptRow?): Set<WorkDatum> =
    WorkDatum.entries.filterTo(HashSet()) { it.outer && dates[it] != null && dates[it] != kept?.dates?.get(it) }

/** THE ROWS OF THE INDEX OF THE LIST AS WRITTEN, of the items asked for by their keys ([KeptRow]). */
object ListKeptRows {

    /** Of the items [ids] of the [source], their rows as the [db] holds them; an item of none left out. */
    fun of(db: DSLContext, source: String, ids: Collection<String>): Map<String, KeptRow> {
        val out = HashMap<String, KeptRow>()
        ids.distinct().chunked(ListIndex.BATCH).forEach { chunk ->
            val keys = chunk.map { ItemKey(source, it).toString() }
            val facets = facetsOf(db, keys)
            val numbers = numbersOf(db, keys)
            val texts = textsOf(db, keys)
            val vectors = vectorsOf(db, keys)
            db.select(COLUMNS).from(ITEM).where(ITEM.ITEM_KEY.`in`(keys)).fetch { r ->
                val key = r.get(ITEM.ITEM_KEY)!!
                out[ItemKey.parse(key).id] =
                    KeptRow(
                        work = r.get(ITEM.WORK)!!,
                        rank = r.get(ITEM.WORK_RANK)!!.toLong(),
                        linked = r.get(ITEM.LINKED)!!,
                        updated = r.get(ITEM.UPDATED)!!,
                        added = r.get(ITEM.ADDED)!!,
                        graded = if (r.get(ITEM.GRADED)!!) 1L else 0L,
                        marked = if (r.get(ITEM.MARKED)!!) 1L else 0L,
                        title = r.get(ITEM.TITLE)!!,
                        facets = facets[key].orEmpty(),
                        numbers = numbers[key].orEmpty(),
                        dates = WorkDatum.entries.mapNotNull { d -> r.get(d.column)?.let { d to it } }.toMap(),
                        stages = WorkStages(WorkStage.entries.associateWith { st -> r.get(st.column) ?: 0L }),
                        texts = texts[key].orEmpty(),
                        vectors = vectors[key].orEmpty(),
                    )
            }
        }
        return out
    }

    /** Of the items [keys], their facets' values in their order. */
    private fun facetsOf(db: DSLContext, keys: List<String>): Map<String, Map<String, List<String>>> {
        val out = HashMap<String, LinkedHashMap<String, MutableList<String>>>()
        val v = ITEM_VALUE
        db.select(v.ITEM_KEY, v.FACET, v.VALUE_KEY)
            .from(v)
            .where(v.ITEM_KEY.`in`(keys))
            .orderBy(v.ITEM_KEY, v.FACET, v.POS)
            .fetch { r ->
                out.getOrPut(r.value1()!!) { LinkedHashMap() }.getOrPut(r.value2()!!) { ArrayList() } += r.value3()!!
            }
        return out
    }

    /** Of the items [keys], their numbers. */
    private fun numbersOf(db: DSLContext, keys: List<String>): Map<String, Map<String, Double>> {
        val out = HashMap<String, HashMap<String, Double>>()
        val n = ITEM_NUMBER
        db.select(n.ITEM_KEY, n.NUMBER_ID, n.CONTENT).from(n).where(n.ITEM_KEY.`in`(keys)).fetch { r ->
            out.getOrPut(r.value1()!!) { HashMap() }[r.value2()!!] = r.value3()!!
        }
        return out
    }

    /** Of the items [keys], their texts held whole. */
    private fun textsOf(db: DSLContext, keys: List<String>): Map<String, Map<String, String>> {
        val out = HashMap<String, HashMap<String, String>>()
        val t = ITEM_TEXT
        db.select(t.ITEM_KEY, t.TEXT_KEY, t.CONTENT).from(t).where(t.ITEM_KEY.`in`(keys)).fetch { r ->
            out.getOrPut(r.value1()!!) { HashMap() }[r.value2()!!] = r.value3()!!
        }
        return out
    }

    /** Of the items [keys], the vectors of their texts by the fields of their encoders. */
    private fun vectorsOf(db: DSLContext, keys: List<String>): Map<String, Map<String, ByteArray>> {
        val out = HashMap<String, HashMap<String, ByteArray>>()
        val u = ITEM_VECTOR
        db.select(u.ITEM_KEY, u.ENCODER, u.TEXT_KEY, u.VEC).from(u).where(u.ITEM_KEY.`in`(keys)).fetch { r ->
            out.getOrPut(r.value1()!!) { HashMap() }[ListTexts.vectorField(r.value2()!!, r.value3()!!)] = r.value4()!!
        }
        return out
    }

    /** The columns of an item's own row a written row is read by. */
    private val COLUMNS =
        listOf(ITEM.ITEM_KEY, ITEM.WORK, ITEM.WORK_RANK, ITEM.LINKED, ITEM.UPDATED, ITEM.ADDED) +
            listOf(ITEM.GRADED, ITEM.MARKED, ITEM.TITLE) +
            WorkDatum.entries.map { it.column } +
            WorkStage.entries.map { it.column }
}
