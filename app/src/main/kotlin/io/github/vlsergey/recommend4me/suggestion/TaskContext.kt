package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * THE OTHER FACETS' VALUES AS THE CONTEXT OF A FACET'S WORKS: of every work, in the order of the task's rows, the
 * columns of its values ([of]); [keys] — the column's value, "facet\u0001key", in the order the works first have them
 * ([SuggestionTask.context], [SuggestionTask.contextKeys]).
 */
internal class TaskContext(val of: List<IntArray>, val keys: List<String>) {
    companion object {
        /**
         * The context of the [facet] of the works of [read]: of the facets the source has now, the site's own among
         * them. THE CHAIN: of the facets before this one, the values the model gives the item besides the site's and
         * the user's, made in the same pass ([modelled] holds those alone, and the drafts); the tags with those above
         * them. Made once of the pass ([WorksRead.context]): the facet's own values, which alone the examples and the
         * drafts of it add to, left out.
         */
        fun of(store: SourceStore, read: WorksRead, modelled: WorkChances, facet: String): TaskContext {
            val made = read.context(store, modelled)
            val contextOf = valuesOf(made, read.ids.size, made.places[facet])
            // A value of the context only one item of the fitting has teaches nothing of the others: no
            // column of it — of a catalogue of games, the developers of one game each, the operating
            // systems and languages written by hand once. An item judged alone ([WorksRead.only]) is read by the
            // columns of the layout fitted last: every value of its own kept
            val shared =
                if (read.only != null) null
                else IntArray(made.names.size).also { n -> contextOf.forEach { keys -> keys.forEach { n[it]++ } } }
            return columns(made.names, contextOf, shared)
        }

        /**
         * Every work's values of the other facets by their numbers of the pass, each facet's apart from the others' —
         * the facet's [own] place left out.
         */
        private fun valuesOf(made: WorksRead.Context, works: Int, own: Int?): List<IntArray> =
            (0 until works).map { i ->
                val work = made.of[i] ?: return@map IntArray(0)
                var n = 0
                work.forEachIndexed { k, part -> if (k != own && part != null) n += part.size }
                val out = IntArray(n)
                var at = 0
                work.forEachIndexed { k, part ->
                    if (k == own || part == null) return@forEachIndexed
                    part.copyInto(out, at)
                    at += part.size
                }
                out
            }

        /**
         * The columns of the task by the values' numbers, in the order the works first have them: of a value more than
         * one work has ([shared]; every value when null) alone.
         */
        private fun columns(names: List<String>, contextOf: List<IntArray>, shared: IntArray?): TaskContext {
            val column = IntArray(names.size) { -1 }
            val contextKeys = ArrayList<String>()
            val context = contextOf.map { keys ->
                var n = 0
                val out = IntArray(keys.size)
                keys.forEach { key ->
                    if (shared != null && shared[key] <= 1) return@forEach
                    if (column[key] < 0) {
                        column[key] = contextKeys.size
                        contextKeys += names[key]
                    }
                    out[n++] = column[key]
                }
                if (n == out.size) out else out.copyOf(n)
            }
            return TaskContext(context, contextKeys)
        }
    }
}
