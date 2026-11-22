package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.DuckDatabase
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_MODEL
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_NUMBER
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_TEXT
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VALUE
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VECTOR
import io.github.vlsergey.recommend4me.database.worklist.tables.references.LIST_MARK
import io.github.vlsergey.recommend4me.database.worklist.tables.references.PENDING_DATE
import io.github.vlsergey.recommend4me.database.worklist.tables.references.PENDING_VALUES
import io.github.vlsergey.recommend4me.item.ItemKey
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.duckdb.DuckDBConnection
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * THE INDEX OF THE LIST OF WORKS OF ONE CONTENT TYPE (user, 2026-11-17; owner, 2026-11-22: "work" — of DuckDB, no more
 * of Lucene): what the screen of the list is filtered, sorted, counted and cut into pages by, read of it alone — no
 * database read at a request, no model run, of a million works. A row of every item ([ListRow], [ListDocuments]): its
 * key and source, the work it is of — a work of several items one row, grouped by it —, the values of every filter
 * facet, every number, its texts and vectors, its dates, and what changes of its work without it — graded, the
 * prediction, the chance of interest, their product — set in place ([setValues]): a training writes the prediction of
 * every work without a row made anew.
 *
 * The databases are the truth: the index is made of them ([ListIndexing]), a file of its own made anew, empty, as its
 * tables are other than its script makes ([DuckDatabase]). Written in the queue of the computations alone, one writing
 * at a time, through a connection of its own whose transaction is kept open till a [commit] — what [written] reads —;
 * read by any request as last committed ([searching]).
 */
class ListIndex(private val store: DuckDatabase) : AutoCloseable {

    /** The connection the writing goes through, of one transaction from a commit to the next. */
    private val writer: DuckDBConnection = store.connection().apply { autoCommit = false }

    private val writing = store.dslOf(writer)

    /** One user of the writing's connection at a time: a connection is no thread's of its own. */
    private val lock = ReentrantLock()

    /** Since when each stage of the tasks over the works holds, kept with the commits ([StageTimes]). */
    val stages =
        StageTimes(
            store.dsl.select(LIST_MARK.NAME, LIST_MARK.CONTENT).from(LIST_MARK).fetch().associate {
                it.value1()!! to it.value2()!!
            }
        )

    /** The [block] of a reading of the index as it was last committed. */
    fun <T> searching(block: (DSLContext) -> T): T = block(store.dsl)

    /** The [block] of a reading of the index as written so far, committed or not. */
    fun <T> written(block: (DSLContext) -> T): T = lock.withLock { block(writing) }

    /**
     * The rows of the [rows] written in place of those of their items — each of every table —, and those of the items
     * [gone] removed.
     */
    fun replace(rows: List<ListRow>, gone: Collection<ItemKey> = emptyList()) = lock.withLock {
        remove(rows.map { it.key.toString() } + gone.map { it.toString() })
        ListDocuments.append(writer, rows)
    }

    /** The rows of the [source] whose items are not among [present] removed: how many. */
    fun keepOnly(source: String, present: Set<String>): Int = lock.withLock {
        val gone =
            writing
                .select(ITEM.ITEM_KEY)
                .from(ITEM)
                .where(ITEM.SOURCE.eq(source))
                .fetch { it.value1()!! }
                .filter {
                    ItemKey.parse(it).id !in present
                }
        remove(gone)
        gone.size
    }

    /** The rows of every source not among [sources] removed: of a source the application no longer has. */
    fun keepSources(sources: Set<String>) = lock.withLock {
        val others = ITEM.SOURCE.notIn(sources)
        OTHERS.forEach { (table, key) ->
            writing.deleteFrom(table).where(key.`in`(DSL.select(ITEM.ITEM_KEY).from(ITEM).where(others))).execute()
        }
        writing.deleteFrom(ITEM).where(others).execute()
    }

    /**
     * Of every item of [values], the [column] of its row set — a sweep of a stage, a date —; an item of no row left.
     */
    fun set(column: Field<Long?>, values: Map<ItemKey, Long>) = lock.withLock {
        if (values.isEmpty()) return@withLock
        writer.createAppender(DuckDBConnection.DEFAULT_SCHEMA, PENDING_DATE.name.lowercase()).use { a ->
            values.forEach { (key, at) ->
                a.beginRow()
                a.append(key.toString())
                a.append(at)
                a.endRow()
            }
        }
        writing
            .update(ITEM)
            .set(column, PENDING_DATE.CONTENT)
            .from(PENDING_DATE)
            .where(ITEM.ITEM_KEY.eq(PENDING_DATE.ITEM_KEY))
            .execute()
        writing.deleteFrom(PENDING_DATE).execute()
    }

    /**
     * Of every item of [values], what changes of its work without it set in place ([ListDocuments.kept]); an item of no
     * row left.
     */
    fun setValues(values: Map<ItemKey, RowValues>) = lock.withLock {
        if (values.isEmpty()) return@withLock
        writer.createAppender(DuckDBConnection.DEFAULT_SCHEMA, PENDING_VALUES.name.lowercase()).use { a ->
            values.forEach { (key, v) ->
                a.beginRow()
                a.append(key.toString())
                ListDocuments.appendValues(a, v)
                a.endRow()
            }
        }
        val p = PENDING_VALUES
        writing
            .update(ITEM)
            .set(ITEM.GRADED, p.GRADED)
            .set(ITEM.MARKED, p.MARKED)
            .set(ITEM.PREDICTION, p.PREDICTION)
            .set(ITEM.INTEREST, p.INTEREST)
            .set(ITEM.MODEL_INTEREST, p.MODEL_INTEREST)
            .set(ITEM.EXPECTED, p.EXPECTED)
            .from(p)
            .where(ITEM.ITEM_KEY.eq(p.ITEM_KEY))
            .execute()
        writing.deleteFrom(p).execute()
    }

    /** Of every item of the [source], its prediction, its chances of interest and their product forgotten. */
    fun forgetPredictions(source: String) = lock.withLock {
        writing
            .update(ITEM)
            .setNull(ITEM.PREDICTION)
            .setNull(ITEM.MODEL_INTEREST)
            .setNull(ITEM.EXPECTED)
            .setNull(ITEM.INTEREST)
            .where(ITEM.SOURCE.eq(source))
            .execute()
    }

    /** What was written made what the requests read, and kept, with where every stage stands. */
    fun commit() = lock.withLock {
        writing.deleteFrom(LIST_MARK).execute()
        val marks = stages.data()
        if (marks.isNotEmpty()) {
            writer.createAppender(DuckDBConnection.DEFAULT_SCHEMA, LIST_MARK.name.lowercase()).use { a ->
                marks.forEach { (name, value) ->
                    a.beginRow()
                    a.append(name)
                    a.append(value)
                    a.endRow()
                }
            }
        }
        writer.commit()
    }

    /** The writing under way committed, as an index's writer did as it closed; the connection given back. */
    override fun close() = lock.withLock {
        commit()
        writer.close()
    }

    /** The rows of the items [keys] removed of every table. */
    private fun remove(keys: List<String>) =
        keys.chunked(BATCH).forEach { chunk ->
            OTHERS.forEach { (table, key) -> writing.deleteFrom(table).where(key.`in`(chunk)).execute() }
            writing.deleteFrom(ITEM).where(ITEM.ITEM_KEY.`in`(chunk)).execute()
        }

    companion object {
        /** The items of one statement: the length of its list of keys, not a meaning. */
        const val BATCH = 1000

        /** The tables of an item's rows beside its own, by the column of its key. */
        private val OTHERS =
            listOf(
                ITEM_VALUE to ITEM_VALUE.ITEM_KEY,
                ITEM_NUMBER to ITEM_NUMBER.ITEM_KEY,
                ITEM_MODEL to ITEM_MODEL.ITEM_KEY,
                ITEM_TEXT to ITEM_TEXT.ITEM_KEY,
                ITEM_VECTOR to ITEM_VECTOR.ITEM_KEY,
            )
    }
}
