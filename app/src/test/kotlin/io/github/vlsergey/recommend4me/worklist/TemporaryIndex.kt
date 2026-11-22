package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.DuckDatabase
import java.nio.file.Files

/** The [block] of an index of the list of a file of its own, made in a folder of nothing else, closed after. */
fun <T> temporaryIndex(block: (ListIndex) -> T): T =
    DuckDatabase("test/list", Files.createTempDirectory("list-index").resolve("list.duckdb"), SCRIPT, MEMORY, false)
        .use { store -> ListIndex(store).use(block) }

private const val SCRIPT = "worklist/store.sql"

private const val MEMORY = "256MB"
