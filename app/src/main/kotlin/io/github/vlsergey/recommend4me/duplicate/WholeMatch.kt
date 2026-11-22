package io.github.vlsergey.recommend4me.duplicate

import java.util.stream.IntStream

/**
 * WHAT TWO WORKS OF A PAIR HAVE WHOLLY ALIKE, as the user may choose the pairs by: the authors — the same normalized
 * authors, every one, none counted by a word "partly one" — and the title — the same, as the pair shows it (the user's
 * over the site's), apart from the case and the spaces around. Of a work of no normalized author, no authors alike. Of
 * the works as the search read them ([sides]: of the index of the list), nothing read again, a pair by the places of
 * its works the search found it of ([FoundCandidate]). Every work's title and authors made comparable once, as the
 * search begins to score: of every pair, a work's key looked up among a site's and its title and authors made anew took
 * 9 s of the 2.6 million pairs of the books (2026-11-22).
 */
internal class WholeMatch(sides: List<SearchSide>) {
    /** Of every site, every work's title in lower case, the spaces around gone. */
    private val titles = sides.map { side ->
        arrayOfNulls<String>(side.titleTexts.size).also { out ->
            IntStream.range(0, out.size).parallel().forEach { out[it] = side.titleTexts[it].trim().lowercase() }
        }
    }

    /** Of every site, every work's normalized authors, each once, ascending. */
    private val authors = sides.map { side ->
        arrayOfNulls<LongArray>(side.authors.size).also { out ->
            IntStream.range(0, out.size).parallel().forEach {
                out[it] = side.authors[it].distinct().sorted().toLongArray()
            }
        }
    }

    /** Whether the two works of the pair [f] have the same normalized authors, one at least. */
    fun authors(f: FoundCandidate): Boolean {
        val first = authors[f.site][f.item]!!
        return first.isNotEmpty() && first.contentEquals(authors[f.otherSite][f.other])
    }

    /** Whether the two works of the pair [f] have the same title. */
    fun title(f: FoundCandidate): Boolean = titles[f.site][f.item] == titles[f.otherSite][f.other]
}
