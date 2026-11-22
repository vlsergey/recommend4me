package io.github.vlsergey.recommend4me.database

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.fail
import org.jooq.DSLContext
import org.junit.jupiter.api.io.TempDir

/**
 * NO INDEX OF A DATABASE OF THE APPLICATION IS ANOTHER'S BEGINNING (owner, 2026-11-22: "check you made no duplicate,
 * overlapping indexes; maybe even a test case for it"): of every table, an index made by a migration, not unique, whose
 * columns begin another index of the table — a key's, one made by a migration — is that index's work done twice: every
 * write keeps both, every lookup of the first is served by the second. Of the databases the application's schemas make,
 * every index as the database lists it. The index H2 makes of a reference of its own is not one of them: it makes one
 * whatever index begins with the reference's columns, the key's or one made before (H2 2.4, 2026-11-22).
 */
class SchemaIndexesTest {

    @TempDir lateinit var folder: Path

    private class Index(
        val table: String,
        val name: String,
        val unique: Boolean,
        val columns: List<String>,
        /** Made by the database for a reference, not by a migration. */
        val ofReference: Boolean,
    )

    @Test
    fun `no index of a database begins another of its table`() {
        val found =
            Schema.KINDS.flatMap { kind ->
                Database(kind, folder.resolve(kind), kind, placeholders = mapOf("source" to "site")).use { db ->
                    overlapping(indexes(db.dsl)).map { (index, wider) ->
                        "$kind: ${index.table}.${index.name} ${index.columns} begins ${wider.name} ${wider.columns}"
                    }
                }
            }
        if (found.isNotEmpty()) fail("Indexes the wider ones serve:\n" + found.joinToString("\n"))
    }

    /** Of the [indexes], every one not unique whose columns begin another's of its table, with that other. */
    private fun overlapping(indexes: List<Index>): List<Pair<Index, Index>> =
        indexes
            .groupBy { it.table }
            .values
            .flatMap { own ->
                own.filter { !it.unique && !it.ofReference }
                    .mapNotNull { index ->
                        own.firstOrNull { other ->
                                other !== index &&
                                    other.columns.size >= index.columns.size &&
                                    other.columns.subList(0, index.columns.size) == index.columns &&
                                    // Of two alike, one named: the first of them stands
                                    (other.columns.size > index.columns.size || other.unique || other.name < index.name)
                            }
                            ?.let { index to it }
                    }
            }

    /** Every index of the database's own tables, its columns in order. */
    private fun indexes(db: DSLContext): List<Index> {
        val columns =
            db.resultQuery(
                    "SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS " +
                        "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME, INDEX_NAME, ORDINAL_POSITION"
                )
                .fetch()
                .groupBy({ (it.get(0, String::class.java) to it.get(1, String::class.java)) }) {
                    it.get(2, String::class.java)
                }
        val ofReferences =
            db.resultQuery(
                    "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS " +
                        "WHERE TABLE_SCHEMA = 'PUBLIC' AND CONSTRAINT_TYPE = 'FOREIGN KEY'"
                )
                .fetch()
                .mapTo(HashSet()) { it.get(0, String::class.java) }
        return db.resultQuery(
                "SELECT TABLE_NAME, INDEX_NAME, INDEX_TYPE_NAME FROM INFORMATION_SCHEMA.INDEXES " +
                    "WHERE TABLE_SCHEMA = 'PUBLIC'"
            )
            .fetch()
            .map { r ->
                val table = r.get(0, String::class.java)
                val name = r.get(1, String::class.java)
                Index(
                    table,
                    name,
                    r.get(2, String::class.java) != "INDEX",
                    columns[table to name].orEmpty(),
                    name in ofReferences,
                )
            }
    }
}
