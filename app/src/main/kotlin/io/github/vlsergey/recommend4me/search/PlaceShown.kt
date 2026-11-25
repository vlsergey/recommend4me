package io.github.vlsergey.recommend4me.search

/**
 * THE PLACE THE PIECES FOUND, AS IT IS SHOWN: the characters of the pieces of the [SearchPlace] in the text of its
 * field, from the beginning of the word of its first piece to the end of the word of its last, and every word of a
 * piece that matched a piece of the line best marked — a piece is often a part of a word, and a part marked reads as
 * broken.
 */
object PlaceShown {

    /**
     * What is shown of the [place] in the [text] of its field, cut into pieces at the characters [spans] — the text's
     * pieces as the encoder cuts it; null when the text has fewer pieces than the place reaches (a text changed since
     * its pieces were made).
     */
    fun of(place: SearchPlace, text: String, spans: List<IntRange?>): SearchMatch? {
        val own = if (place.to > spans.size) emptyList() else (place.from until place.to).mapNotNull { spans[it] }
        if (own.isEmpty()) return null
        val start = wordStart(text, own.first().first)
        val end = wordEnd(text, own.last().last + 1)
        val marks =
            place.marked
                .mapNotNull { spans.getOrNull(it) }
                .map { wordStart(text, it.first) to wordEnd(text, it.last + 1) }
                .sortedBy { it.first }
        val merged = ArrayList<Pair<Int, Int>>()
        marks.forEach { m ->
            val last = merged.lastOrNull()
            if (last != null && m.first <= last.second)
                merged[merged.size - 1] = last.first to maxOf(last.second, m.second)
            else merged += m
        }
        return SearchMatch(
            place.field,
            true,
            text.substring(start, end),
            merged.map { (a, b) -> (a.coerceAtLeast(start) - start) to (b.coerceAtMost(end) - start) },
        )
    }

    /** The beginning of the word the character [at] of the [text] is of. */
    private fun wordStart(text: String, at: Int): Int {
        var i = at.coerceIn(0, text.length)
        while (inWord(text, i)) i--
        return i
    }

    /** The end of the word the character before [at] of the [text] is of. */
    private fun wordEnd(text: String, at: Int): Int {
        var i = at.coerceIn(0, text.length)
        while (inWord(text, i)) i++
        return i
    }

    /** Whether the place before the character [i] of the [text] is within a word: a letter on either side of it. */
    private fun inWord(text: String, i: Int) =
        i in 1 until text.length && text[i - 1].isLetterOrDigit() && text[i].isLetterOrDigit()
}
