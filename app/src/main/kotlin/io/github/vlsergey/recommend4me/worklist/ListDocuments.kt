package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_MODEL
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_NUMBER
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_TEXT
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VALUE
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VECTOR
import java.math.BigDecimal
import java.math.MathContext
import org.duckdb.DuckDBAppender
import org.duckdb.DuckDBConnection

/**
 * AN ITEM AS THE ROWS OF THE INDEX OF THE LIST ([ListIndex], worklist/store.sql): its row of the items — its key and
 * source, the work it is of and its rank among the work's items, whether the work is of several, its dates, what
 * changes of its work without it to its [DIGITS], its title, the dates of what its work is made of ([WorkDatum]) and
 * the sweeps of the stages that did it ([WorkStage]) —; and of the tables beside, a row of every value of a filter
 * facet, of every number, of every value a model gives it, of every text held whole and of every vector of a text:
 * appended, in the order of the columns of the script.
 */
object ListDocuments {

    /** The rows of the [rows] appended by the [connection], in its transaction. */
    fun append(connection: DuckDBConnection, rows: List<ListRow>) {
        if (rows.isEmpty()) return
        Appenders(connection).use { a -> rows.forEach(a::append) }
    }

    /** An appender of every table of an item's rows, of one connection, closed together. */
    private class Appenders(connection: DuckDBConnection) : AutoCloseable {
        private fun of(c: DuckDBConnection, table: org.jooq.Table<*>) =
            c.createAppender(DuckDBConnection.DEFAULT_SCHEMA, table.name.lowercase())

        private val items = of(connection, ITEM)
        private val values = of(connection, ITEM_VALUE)
        private val numbers = of(connection, ITEM_NUMBER)
        private val models = of(connection, ITEM_MODEL)
        private val texts = of(connection, ITEM_TEXT)
        private val vectors = of(connection, ITEM_VECTOR)

        /** The rows of the [row] appended. */
        fun append(row: ListRow) {
            item(items, row)
            val key = row.key.toString()
            row.facets.forEach { (facet, keys) -> listed(values, key, facet, keys) }
            row.numbers.forEach { (number, value) -> row(numbers, key, number, value) }
            row.model.forEach { (facet, keys) -> listed(models, key, facet, keys) }
            row.texts.forEach { (textKey, text) -> row(texts, key, textKey, text) }
            row.vectors?.let { v -> v.packed.forEach { (textKey, packed) -> vector(key, v.encoder, textKey, packed) } }
        }

        private fun vector(key: String, encoder: String, textKey: String, packed: ByteArray) {
            vectors.beginRow()
            vectors.append(key)
            vectors.append(encoder)
            vectors.append(textKey)
            vectors.append(packed)
            vectors.endRow()
        }

        override fun close() = listOf(items, values, numbers, models, texts, vectors).forEach { it.close() }
    }

    /** Of what changes of a work without its item, the columns of the [values] appended to [a], each to its digits. */
    fun appendValues(a: DuckDBAppender, values: RowValues) {
        a.append(values.graded)
        a.append(values.marked)
        a.add(kept(values.prediction))
        a.add(kept(values.interest))
        a.add(kept(values.modelInterest))
        a.add(kept(values.expected))
    }

    /** The row of the items of the [row], in the order of the columns of the script. */
    private fun item(a: DuckDBAppender, row: ListRow) {
        a.beginRow()
        a.append(row.key.toString())
        a.append(row.key.source)
        a.append(row.work.toString())
        a.append(row.rank)
        a.append(row.linked)
        a.append(row.updatedAt.toEpochMilli())
        a.append(row.firstSeenAt.toEpochMilli())
        appendValues(a, row.values)
        a.append(row.title)
        WorkDatum.entries.forEach { a.add(row.dates[it]) }
        WorkStage.entries.forEach { stage -> a.add(row.stages[stage].takeIf { it > 0 }) }
        a.endRow()
    }

    /** The rows of the [keys] of the [facet] of the item [key], each by its place among them. */
    private fun listed(a: DuckDBAppender, key: String, facet: String, keys: List<String>) =
        keys.forEachIndexed { pos, value ->
            a.beginRow()
            a.append(key)
            a.append(facet)
            a.append(pos)
            a.append(value)
            a.endRow()
        }

    private fun row(a: DuckDBAppender, key: String, name: String, value: Double) {
        a.beginRow()
        a.append(key)
        a.append(name)
        a.append(value)
        a.endRow()
    }

    private fun row(a: DuckDBAppender, key: String, name: String, value: String) {
        a.beginRow()
        a.append(key)
        a.append(name)
        a.append(value)
        a.endRow()
    }

    private fun DuckDBAppender.add(v: Double?) = if (v == null) appendNull() else append(v)

    private fun DuckDBAppender.add(v: Long?) = if (v == null) appendNull() else append(v)

    /**
     * A prediction, a chance, an expectation as the index keeps it: to its first [DIGITS] (owner, 2026-11-21: "the
     * grade and the interest are three digits") — a change beyond them is none of the list's, its row not written for
     * it ([ListPredictions.write]).
     */
    fun kept(value: Double?): Double? =
        if (value == null || value == 0.0 || !value.isFinite()) value?.takeIf { it.isFinite() }
        else BigDecimal(value).round(MathContext(DIGITS)).toDouble()

    /** The digits a value of the list is kept to. */
    const val DIGITS = 3
}
