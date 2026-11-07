package io.github.vlsergey.recommend4me.database

import java.sql.ResultSet
import org.jooq.DSLContext

/** The rows a streamed reading takes from H2 at a time: memory, not meaning. */
private const val FETCH = 2000

/**
 * EVERY ROW OF [sql] OF THE [params] HANDED TO [row] AS IT IS READ — the result set at it, valid during the call alone
 * — by JDBC alone, of H2's lazy execution: the rows made as they are asked for, none laid out whole before the first is
 * handed over. Of what a pass reads in bulk, the vectors of every work: of fanfics.me's 213 194 vectors of the texts,
 * 416 MB, jOOQ's records took 480 ms, JDBC 340, lazily 305 (`TextVectorReadBench`). The connection's lazy execution set
 * back after, whatever happens: the pool's next borrower runs as it would.
 */
fun DSLContext.streamed(sql: String, params: List<Any>, row: (ResultSet) -> Unit) = connection { c ->
    c.createStatement().use { it.execute("SET LAZY_QUERY_EXECUTION TRUE") }
    try {
        c.prepareStatement(sql).use { s ->
            params.forEachIndexed { k, p -> s.setObject(k + 1, p) }
            s.fetchSize = FETCH
            s.executeQuery().use { r -> while (r.next()) row(r) }
        }
    } finally {
        c.createStatement().use { it.execute("SET LAZY_QUERY_EXECUTION FALSE") }
    }
}
