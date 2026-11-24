package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.DuckDatabase
import io.github.vlsergey.recommend4me.folder.DataFolder
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/** The index of the list of every content type ([ListIndex]), in `<data-dir>/types/<type>/list.duckdb`. */
@Component
class ListIndexes(private val folder: DataFolder) : DisposableBean {

    /** The open indexes — files, not data: a file is opened once and held, with its connection of the writing. */
    private val open = ConcurrentHashMap<String, Pair<DuckDatabase, ListIndex>>()

    /** The index of the content type [typeId]. */
    fun of(typeId: String): ListIndex = opened(typeId).second

    /** The file of the index of the content type [typeId]: what a query of the files reads. */
    fun store(typeId: String): DuckDatabase = opened(typeId).first

    private fun opened(typeId: String): Pair<DuckDatabase, ListIndex> =
        open.computeIfAbsent(typeId) {
            // Read by the computations of the facets too, as the index of Lucene was: no database of theirs
            val store = DuckDatabase("$it/list", folder.type(it).resolve(FILE), SCRIPT, MEMORY, refused = false)
            store to ListIndex(store)
        }

    override fun destroy() =
        open.values.forEach { (store, index) ->
            index.close()
            store.close()
        }

    private companion object {
        const val FILE = "list.duckdb"

        /** The script the file is made by. */
        const val SCRIPT = "worklist/store.sql"

        /** The memory the index of a content type takes at most, as DuckDB names it. */
        const val MEMORY = "3GB"
    }
}
