package io.github.vlsergey.recommend4me.database

import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.duckdb.DuckDBConnection
import org.junit.jupiter.api.Test

/**
 * A file of DuckDB whose tables lack only columns its script adds, of no NOT NULL, is widened in place, its rows kept;
 * of tables other otherwise, not: it is made anew.
 */
class DuckTablesTest {
    private val narrow =
        """
        CREATE TABLE item (
            item_key VARCHAR NOT NULL PRIMARY KEY,
            data_at BIGINT,
            fields_done BIGINT
        );
        CREATE INDEX item_data ON item (data_at);
        CREATE TABLE mark (name VARCHAR NOT NULL);
        """
            .trimIndent()

    private fun opened(): DuckDBConnection =
        (DriverManager.getConnection("jdbc:duckdb:") as DuckDBConnection).apply {
            DuckTables.run(this, narrow)
            createStatement().use { it.execute("INSERT INTO item VALUES ('a', 1, 2), ('b', 3, NULL)") }
        }

    @Test
    fun `columns added that may be null widen the table in place, its rows and keys kept`() {
        val wide = narrow.replace("data_at BIGINT,", "data_at BIGINT,\n    groups_at BIGINT,")
        opened().use { c ->
            assertEquals(listOf("item"), DuckWidening.widened(c, wide))
            assertEquals(DuckTables.ofScript(wide), DuckTables.of(c))
            val rows =
                c.createStatement().use { s ->
                    s.executeQuery("SELECT item_key, data_at, groups_at, fields_done FROM item ORDER BY item_key").use {
                        buildList {
                            while (it.next()) add(
                                listOf(it.getString(1), it.getObject(2), it.getObject(3), it.getObject(4))
                            )
                        }
                    }
                }
            assertEquals(listOf(listOf("a", 1L, null, 2L), listOf("b", 3L, null, null)), rows)
        }
    }

    @Test
    fun `a column of NOT NULL added, or one taken away, is no widening`() {
        opened().use { c ->
            assertNull(
                DuckWidening.widened(c, narrow.replace("data_at BIGINT,", "data_at BIGINT,\n    w INT NOT NULL,"))
            )
            assertNull(
                DuckWidening.widened(
                    c,
                    narrow.replace("    fields_done BIGINT\n", "").replace("BIGINT,\n)", "BIGINT\n)"),
                )
            )
            assertNull(DuckWidening.widened(c, narrow), "of the same tables nothing to widen")
            assertEquals(DuckTables.ofScript(narrow), DuckTables.of(c))
        }
    }
}
