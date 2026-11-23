package io.github.vlsergey.recommend4me.duplicate

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * THE WORKS OF ONE AUTHOR AND OF ONE TITLE made in one array of numbers are those of their meaning, in their order: of
 * every work the others of an author of its kin ascending; of its title the nearest by the numbering, one before then
 * one after, [Titles.MOST] at most; the candidates the first, then the second not among them; the graph begun of half
 * its neighbours of an author, the rest of the title.
 */
class WorksAlikeTest {

    @Test
    fun `the works of one author and of one title are those of their meaning`() {
        val random = Random(11)
        // A few titles many works bear, more than a work takes of one; and a site of a title a work
        val sites =
            listOf(
                RandomSearches.site(random, "a", 300, 24, titleKinds = 3),
                RandomSearches.site(random, "b", 800, 24, titleKinds = 2),
                RandomSearches.site(random, "c", 200, 24),
            )
        val search =
            PairSearch(sites, RandomSearches.families, RandomSearches.familyOf, listOf("words", "chars"), emptyMap())
        val all = assertNotNull(AllWorks.of(search))
        val ofAuthors = WorksAlike.ofAuthors(all)
        val titles = Titles.of(all)
        val authorsMeant = ofAuthors(all)
        val groups = titleGroups(all)
        val titlesMeant = ofTitles(all, groups)
        assertEquals(authorsMeant, rows(ofAuthors))
        assertEquals(titlesMeant, rows(titles.same))
        assertEquals(
            authorsMeant.indices.map { u -> (authorsMeant[u] + titlesMeant[u]).distinct() },
            rows(WorksAlike.candidates(ofAuthors, titles.same)),
        )
        assertEquals(seeds(authorsMeant, titlesMeant), rows(Seeds.of(all, ofAuthors, titles)))
        assertEquals(groups.count { it.size > 1 }, titles.shared)
        assertEquals(groups.maxOf { it.size }, titles.largest)
        assertTrue(titles.largest > Titles.MOST, "no title of more works than a work takes")
        assertTrue(authorsMeant.count { it.isNotEmpty() } > all.count / 2, "few works of one author")
    }

    private fun rows(lists: Lists): List<List<Int>> =
        (0 until lists.at.size - 1).map { u -> (lists.at[u] until lists.at[u + 1]).map { lists.entries[it] } }

    /** Of every work, the others of an author of its kin, ascending. */
    private fun ofAuthors(all: AllWorks): List<List<Int>> =
        (0 until all.count).map { u ->
            val kin = (all.kin.at[u] until all.kin.at[u + 1]).map { all.kin.entries[it] }.toSet()
            (0 until all.count).filter { v ->
                v != u && (all.authors.at[v] until all.authors.at[v + 1]).any { all.authors.entries[it] in kin }
            }
        }

    /** The works of every title, by their numbers. */
    private fun titleGroups(all: AllWorks): Collection<List<Int>> {
        val title =
            all.sites.flatMap { s -> (0 until s.count).map { i -> FloatArray(s.width).also { s.titles.take(i, it) } } }
        return (0 until all.count).groupBy { title[it].toList() }.values
    }

    /** Of every work, the others of its very title by the numbering: the nearest first, the one before first. */
    private fun ofTitles(all: AllWorks, groups: Collection<List<Int>>): List<List<Int>> {
        val out = MutableList(all.count) { emptyList<Int>() }
        groups.forEach { group ->
            group.forEachIndexed { p, u ->
                val near = ArrayList<Int>()
                var step = 1
                while (near.size < Titles.MOST && step <= Titles.MOST) {
                    val found = listOf(p - step, p + step).filter { it in group.indices }
                    if (found.isEmpty()) break
                    near += found.map { group[it] }
                    step++
                }
                out[u] = near
            }
        }
        return out
    }

    /** Of every work half the graph's neighbours of an author one with its, the rest of its title, each once. */
    private fun seeds(ofAuthors: List<List<Int>>, ofTitles: List<List<Int>>): List<List<Int>> =
        ofAuthors.indices.map { u ->
            val own = LinkedHashSet(ofAuthors[u].take(GraphSearch.K / 2))
            ofTitles[u].take(GraphSearch.K - own.size).forEach { own += it }
            own.toList()
        }
}
