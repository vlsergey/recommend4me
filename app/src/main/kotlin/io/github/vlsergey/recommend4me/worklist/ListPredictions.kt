package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.interest.Interest
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.model.Predicted
import io.github.vlsergey.recommend4me.source.TypeStore
import org.jooq.DSLContext
import org.jooq.Record3

/**
 * THE PREDICTIONS OF THE WORKS, KEPT IN THE INDEX OF THE LIST ALONE (owner, 2026-11-21: "do we need the table of the
 * predictions? isn't it simpler to keep them in the index at once?"): of every item, its work's grade by the model of
 * the grades and its chance of interest by the model of interest — of its row ([ListDocuments]), to their digits
 * ([ListDocuments.kept]), set in place where they changed so ([write]): a change of the last digits writes nothing.
 * Recomputable: every stage of the tasks over the works done again, the predictions among them. Read by the items' keys
 * — the cards — or of every item of a source in one pass of its rows.
 */
object ListPredictions {

    /** Of the items [ids] of the [source], their predictions as the [db] holds them; an item of none left out. */
    fun of(db: DSLContext, source: String, ids: Collection<Long>): Map<Long, Predicted> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, Predicted>()
        ListAmong.of(db, ids.map { ItemKey(source, it).toString() }) {
            db.select(ITEM.ITEM_KEY, ITEM.PREDICTION, ITEM.MODEL_INTEREST)
                .from(ITEM)
                .where(ListIndex.AMONG, ITEM.PREDICTION.isNotNull)
                .fetch { put(out, it) }
        }
        return out
    }

    /** Of every item of the [source] with a prediction, its prediction as the [db] holds it, in one pass. */
    fun ofEvery(db: DSLContext, source: String): Map<Long, Predicted> {
        val out = HashMap<Long, Predicted>()
        db.select(ITEM.ITEM_KEY, ITEM.PREDICTION, ITEM.MODEL_INTEREST)
            .from(ITEM)
            .where(ITEM.SOURCE.eq(source), ITEM.PREDICTION.isNotNull)
            .fetchSize(STREAMED)
            .fetchLazy()
            .use { cursor -> cursor.forEach { put(out, it) } }
        return out
    }

    /**
     * The [predicted] of a portion of the items of the [type] set in place in the [index] where they changed as it
     * keeps them — their grade, their chance of interest, to their digits —, with the other values of their works as
     * the databases say them ([ListRows.valuesOf]); not committed. Answers the items whose prediction was written.
     */
    fun write(index: ListIndex, type: TypeStore, predicted: List<Pair<ItemKey, Predicted>>): List<ItemKey> {
        val changed = ArrayList<ItemKey>()
        predicted.groupBy({ it.first.source }, { it.first.id to it.second }).forEach { (source, made) ->
            val s = type.source(source) ?: return@forEach
            val kept = index.written { of(it, source, made.map { m -> m.first }) }
            val other = made.filter { (id, p) -> kept[id]?.let(::kept) != kept(p) }.toMap()
            if (other.isEmpty()) return@forEach
            index.setValues(ListRows.valuesOf(s, other).mapKeys { (id, _) -> ItemKey(source, id) })
            other.keys.forEach { changed += ItemKey(source, it) }
        }
        return changed
    }

    /**
     * Every prediction of the [type] gone from the [index] — of no model of the grades any more —, the chance of
     * interest of a work the user marked left as the mark says it; not committed.
     */
    fun forget(index: ListIndex, type: TypeStore) {
        type.sources.forEach { s ->
            index.forgetPredictions(s.id)
            val marked = s.interest.all()
            if (marked.isEmpty()) return@forEach
            val graded = index.written { db -> gradedOf(db, s.id, marked.keys) }
            index.setValues(
                marked.entries.associate { (id, mark) ->
                    ItemKey(s.id, id) to
                        RowValues(
                            graded = id in graded,
                            prediction = null,
                            interest = Interest.chance(mark.interesting, null),
                            expected = null,
                            marked = true,
                        )
                }
            )
        }
    }

    /** Of the items [ids] of the [source], those graded as the [db] holds them. */
    private fun gradedOf(db: DSLContext, source: String, ids: Collection<Long>): Set<Long> =
        ListAmong.of(db, ids.map { ItemKey(source, it).toString() }) {
            db.select(ITEM.ITEM_KEY).from(ITEM).where(ListIndex.AMONG, ITEM.GRADED.isTrue).fetchSet {
                ItemKey.parse(it.value1()!!).id
            }
        }

    private fun put(out: MutableMap<Long, Predicted>, r: Record3<String?, Double?, Double?>) {
        out[ItemKey.parse(r.value1()!!).id] = Predicted(r.value2()!!, r.value3())
    }

    /** The prediction as the index keeps it: its grade and its chance of interest, to their digits. */
    private fun kept(p: Predicted) = ListDocuments.kept(p.score) to ListDocuments.kept(p.interest)

    /** The rows streamed at a time: memory, not meaning. */
    private const val STREAMED = 10_000
}
