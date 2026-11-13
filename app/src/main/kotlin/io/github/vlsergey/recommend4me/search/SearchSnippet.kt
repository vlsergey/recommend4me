package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * THE PIECE OF A WORK'S TEXT A SEARCH SHOWED for a line — what the user says "this is it" or "this is not it" of (user,
 * 2026-11-13), never the work whole: the field of the work's document it is of ([SearchMatch.field]) and its text as
 * shown. Another piece of the same work shown for the line is another snippet, judged apart.
 */
data class SearchSnippet(val field: String, val text: String) {
    companion object {
        /** The snippet a work is shown with, of what the search provider matched in it. */
        fun of(match: SearchMatch) = SearchSnippet(match.field, match.text)

        /**
         * How far every work [shown] with a snippet answers the line, by the user's word on that very snippet among the
         * [judged] of the line; a work shown with a snippet never judged is not judged.
         */
        fun judgedShown(
            judged: Map<ItemKey, Map<SearchSnippet, SearchFit>>,
            shown: Map<ItemKey, SearchSnippet>,
        ): Map<ItemKey, SearchFit> = shown.mapNotNull { (key, s) -> judged[key]?.get(s)?.let { key to it } }.toMap()
    }
}
