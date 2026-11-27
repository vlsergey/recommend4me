package io.github.vlsergey.recommend4me.database

import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * THE ROWS OF A KEY OF TWO COLUMNS ASKED WHOLE, of one value of the first and many of the second: `(a, b) IN ((?, ?),
 * …)`, not `a = ? AND b IN (?, …)`. Asked so by parameters, as jOOQ binds them, H2 2.4 uses the key for the first
 * column alone and walks every row of its value for every batch, comparing each with the list (2026-11-27: the bindings
 * of 500 of ficbook's 370 000 authors 1.8 s, by the pairs 14 ms — `KeysAskedWholeTest`). The pairs are found by the key
 * when they are the key — its columns in its order, no other condition on them beside —, or H2 walks the first column's
 * value again ([SourceItems]).
 */
object WholeKeys {

    /** The rows whose key is [value] of the [first] column and any of [seconds] of the [second]. */
    fun <A, B> of(first: Field<A?>, second: Field<B?>, value: A, seconds: Collection<B>): Condition =
        DSL.row(first, second).`in`(seconds.map { DSL.row(DSL.`val`(value, first), DSL.`val`(it, second)) })
}
