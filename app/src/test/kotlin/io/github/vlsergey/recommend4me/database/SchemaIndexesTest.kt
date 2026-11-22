package io.github.vlsergey.recommend4me.database

import io.github.vlsergey.recommend4me.database.app.Public as AppSchema
import io.github.vlsergey.recommend4me.database.corrections.Public as CorrectionsSchema
import io.github.vlsergey.recommend4me.database.dictionaries.Public as DictionariesSchema
import io.github.vlsergey.recommend4me.database.duplicates.Public as DuplicatesSchema
import io.github.vlsergey.recommend4me.database.model.Public as ModelSchema
import io.github.vlsergey.recommend4me.database.preferences.Public as PreferencesSchema
import io.github.vlsergey.recommend4me.database.ratings.Public as RatingsSchema
import io.github.vlsergey.recommend4me.database.source.Public as SourceSchema
import io.github.vlsergey.recommend4me.database.typecorrections.Public as TypeCorrectionsSchema
import kotlin.test.Test
import kotlin.test.fail
import org.jooq.Schema

/**
 * NO INDEX OF A DATABASE OF THE APPLICATION IS ANOTHER'S BEGINNING (owner, 2026-11-22: "check you made no duplicate,
 * overlapping indexes; maybe even a test case for it"; "you have jOOQ: just check the schema of the indexes"): of every
 * table of every schema jOOQ was made of, an index that is not unique whose columns begin another index or key of the
 * table is that index's work done twice — every write keeps both, every lookup of the first is served by the second.
 */
class SchemaIndexesTest {

    private val schemas: List<Schema> =
        listOf(
            AppSchema.PUBLIC,
            CorrectionsSchema.PUBLIC,
            DictionariesSchema.PUBLIC,
            DuplicatesSchema.PUBLIC,
            ModelSchema.PUBLIC,
            PreferencesSchema.PUBLIC,
            RatingsSchema.PUBLIC,
            SourceSchema.PUBLIC,
            TypeCorrectionsSchema.PUBLIC,
        )

    @Test
    fun `no index of a table begins another index or key of it`() {
        val found = schemas.flatMap { schema ->
            schema.tables.flatMap { table ->
                // Every index and key of the table, by its name, its columns in order
                val wider =
                    table.indexes.map { it.name to it.fields.map { f -> f.name } } +
                        table.keys.map { it.name to it.fields.map { f -> f.name } }
                table.indexes
                    .filter { !it.unique }
                    .mapNotNull { index ->
                        val own = index.fields.map { it.name }
                        wider
                            .firstOrNull { (name, columns) ->
                                name != index.name && columns.size >= own.size && columns.take(own.size) == own
                            }
                            ?.let { (name, columns) ->
                                "${schema.qualifiedName} ${table.name}: ${index.name} $own begins $name $columns"
                            }
                    }
            }
        }
        // The schemas hold indexes at all: a check of none would pass of nothing
        if (schemas.none { schema -> schema.tables.any { it.indexes.isNotEmpty() } }) fail("No index in the schemas")
        if (found.isNotEmpty()) fail("Indexes another index or key of their table serves:\n" + found.joinToString("\n"))
    }
}
