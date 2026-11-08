package io.github.vlsergey.recommend4me.database

import org.jooq.Condition
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * THE ROWS OF ITEMS OF A SOURCE in a table keyed by the source and the item — the key of those two alone — found by the
 * key. H2 2.4 takes `source = ? AND item IN (…)` by the source alone and walks every item of it, 75 000 of a site for
 * the 87 cards of a work's author; a row of both, `(source, item) IN ((…), …)`, it finds by the key — when it stands
 * alone, no other condition on the source beside it, or H2 walks the source again (87 items of 300 000 rows: 7.2 ms by
 * the source, 0.46 ms by the key, 17.9 ms both). Of a key of more columns than those two it walks the source too.
 */
object SourceItems {

    /** The rows of the items [ids] of the source [of]: [source] and [item] the columns of the table's key. */
    fun of(source: Field<String?>, item: Field<String?>, of: String, ids: Collection<String>): Condition =
        DSL.row(source, item).`in`(ids.map { DSL.row(DSL.`val`(of, source), DSL.`val`(it, item)) })
}
