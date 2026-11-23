package io.github.vlsergey.recommend4me.worklist

import org.duckdb.DuckDBConnection
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL

/**
 * THE ITEMS A STATEMENT OF THE INDEX OF THE LIST IS OF, by their keys: appended to a table of the statement's
 * connection, temporary — no table of the index's script, whose change makes the index anew — and joined ([keyIn]),
 * never named in a statement a thousand at a time (2026-11-23: a list of keys in a statement scanned its table whole,
 * ten statements a table for the ten thousand documents of a portion of the writing; joined, the rows of a portion were
 * read 4–5 times faster and removed 6 times). One set of keys of a connection at a time: a block given one asks for no
 * other.
 */
internal object ListAmong {

    /** The [block] of statements reading the items [keys] by [keyIn], of the connection of [db]; emptied as it ends. */
    fun <T> of(db: DSLContext, keys: Collection<String>, block: () -> T): T {
        db.execute("CREATE TEMPORARY TABLE IF NOT EXISTS $TEMPORARY.$SCHEMA.$TABLE (item_key VARCHAR NOT NULL)")
        try {
            db.connectionResult { it.unwrap(DuckDBConnection::class.java) }
                .createAppender(TEMPORARY, SCHEMA, TABLE)
                .use { a ->
                    keys.forEach { key ->
                        a.beginRow()
                        a.append(key)
                        a.endRow()
                    }
                }
            return block()
        } finally {
            db.execute("DELETE FROM $TEMPORARY.$SCHEMA.$TABLE")
        }
    }

    /** The item's [key] being among the keys of the block ([of]). */
    fun keyIn(key: Field<String?>): Condition =
        key.`in`(
            DSL.select(DSL.field(DSL.name("item_key"), String::class.java))
                .from(DSL.table(DSL.name(TEMPORARY, SCHEMA, TABLE)))
        )

    /** The catalogue of a connection's temporary tables in DuckDB, and its schema. */
    private const val TEMPORARY = "temp"
    private const val SCHEMA = DuckDBConnection.DEFAULT_SCHEMA

    /** The temporary table of the keys. */
    private const val TABLE = "among_item"
}
