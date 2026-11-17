package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * THE OTHER FACETS' VALUES AS THE CONTEXT OF A FACET'S WORKS: of every work, in the order of the task's rows, the
 * columns of its values ([of]); [keys] — the column's value, "facet\u0001key" ([SuggestionTask.context],
 * [SuggestionTask.contextKeys]).
 */
internal class TaskContext(val of: List<IntArray>, val keys: List<String>) {
    companion object {
        /**
         * The context of the works whose columns are given by their keys ([rows]): every key one column, in the order
         * the works first have them. Of the works of a request, read by the columns of the layout fitted last: every
         * value of their own kept.
         */
        fun ofKeys(rows: List<List<String>>): TaskContext {
            val names = ArrayList<String>()
            val number = HashMap<String, Int>()
            val numbered = rows.map { row ->
                row.map { k -> number.getOrPut(k) { names.add(k).let { names.size - 1 } } }.distinct().toIntArray()
            }
            return TaskContext(numbered, names)
        }

        /**
         * Of the works of [read], the keys of their context of the [facet], in the order of the works: of the facets
         * the source has now, the site's own among them. THE CHAIN: of the facets before this one, the values the model
         * gives the item besides the site's and the user's ([modelled] holds those alone, and the drafts); the tags
         * with those above them. Made once of the reading ([WorksRead.context]): the facet's own values, which alone
         * the examples and the drafts of it add to, left out.
         */
        fun keysOf(store: SourceStore, read: WorksRead, modelled: WorkChances, facet: String): List<List<String>> {
            val made = read.context(store, modelled)
            val own = made.places[facet]
            return (0 until read.ids.size).map { i ->
                val work = made.of[i] ?: return@map emptyList()
                val out = ArrayList<String>()
                work.forEachIndexed { k, part ->
                    if (k != own && part != null) part.forEach { out += made.names[it] }
                }
                out
            }
        }
    }
}
