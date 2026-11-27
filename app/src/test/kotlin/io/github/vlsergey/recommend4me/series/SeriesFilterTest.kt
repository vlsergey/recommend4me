package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.manner.GroupCursor
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

/**
 * Which series the list shows by their titles, their sites, the user's word and the pages to be visited — chosen in the
 * database, the most works first — and the series kept: of every work, of every series its works and sites.
 */
class SeriesFilterTest {
    @TempDir lateinit var folder: Path

    private fun made(
        title: String,
        works: Set<Long>,
        vararg sources: String,
        said: Boolean = false,
        toVisit: Boolean = false,
    ) =
        MadeSeries(
            id = null,
            works = works,
            sites = sources.map { SiteSeriesRef(it, title) }.toSet(),
            title = title,
            titles = "$title\n$title of the site",
            byHand = said,
            toVisit = toVisit,
            worksChanged = true,
        )

    /** Four series of seven works written, of their numbers: "a", "b", "c", "d" — and the works. */
    private fun <T> written(test: (NormalizedSeriesRepository, List<Long>, List<Long>, Database) -> T): T =
        Database("dictionaries", folder.resolve("dictionaries"), "dictionaries").use { dictionaries ->
            val works = WorkRepository(dictionaries.dsl)
            val now = Instant.now()
            val w = (1..7L).map { works.of(ItemKey("ficbook", it), now) }
            val series = NormalizedSeriesRepository(dictionaries.dsl, "books")
            val ids =
                series.write(
                    listOf(
                        made("a", setOf(w[0]), "ficbook"),
                        made("b", setOf(w[1], w[2], w[3]), "ficbook", "author.today", toVisit = true),
                        made("c", setOf(w[4], w[5]), "author.today", said = true),
                        made("d", setOf(w[6]), said = true),
                    ),
                    emptyList(),
                    now,
                )
            test(series, ids, w, dictionaries)
        }

    @Test
    fun `of every site with it, without it or whatever, of the user's word, of a page to be visited, of a title`() =
        written { series, ids, _, dictionaries ->
            val a = ids[0]
            val b = ids[1]
            val c = ids[2]
            val d = ids[3]
            fun let(filter: SeriesFilter) = series.list.page(filter, 0, 10).second.map { it.id }

            // The most works first, of as many by the title
            assertEquals(listOf(b, c, a, d), let(SeriesFilter()))
            assertEquals(4, series.list.page(SeriesFilter(), 1, 2).first)
            assertEquals(listOf(c, a), series.list.page(SeriesFilter(), 1, 2).second.map { it.id })
            assertEquals(listOf(b, a), let(SeriesFilter(with = listOf("ficbook"))))
            assertEquals(listOf(c, d), let(SeriesFilter(without = listOf("ficbook"))))
            assertEquals(listOf(b), let(SeriesFilter(with = listOf("ficbook", "author.today"))))
            assertEquals(listOf(c, d), let(SeriesFilter(said = true)))
            assertEquals(listOf(c), let(SeriesFilter(with = listOf("author.today"), said = true)))
            // By how many sites have it: none of the user's word alone, one, two
            assertEquals(listOf(b, c, a), let(SeriesFilter(minSites = 1)))
            assertEquals(listOf(b), let(SeriesFilter(minSites = 2)))
            assertEquals(listOf(b), let(SeriesFilter(toVisit = true)))
            assertEquals(listOf(c), let(SeriesFilter(search = "C OF THE")))
            assertEquals(emptyList(), let(SeriesFilter(minSites = 3)))
            // Of no other type
            assertEquals(
                emptyList(),
                NormalizedSeriesRepository(dictionaries.dsl, "games").list.page(SeriesFilter(), 0, 10).second,
            )
        }

    @Test
    fun `read by the keys either way, made anew of a work and a site moved, a series gone`() =
        written { series, ids, w, _ ->
            val now = Instant.now()
            val a = ids[0]
            val b = ids[1]
            val c = ids[2]
            val d = ids[3]
            // Read by the keys either way: the series of a work, the works and the sites of a series
            assertEquals(mapOf(w[1] to b, w[6] to d), series.ofWorks(listOf(w[1], w[6])))
            assertEquals(setOf(w[4], w[5]), series.worksOf(listOf(c)).getValue(c))
            assertEquals(
                mapOf(SiteSeriesRef("author.today", "b") to b),
                series.ofSites(listOf(SiteSeriesRef("author.today", "b"))),
            )

            // Made anew: a work and a site moved to another series, a series gone with what it held
            series.write(
                listOf(
                    made("a", setOf(w[0], w[1]), "ficbook").let {
                        MadeSeries(a, it.works, it.sites + SiteSeriesRef("ficbook", "b"), "a", "a", false, false, true)
                    }
                ),
                listOf(d),
                now,
            )
            assertEquals(mapOf(w[1] to a), series.ofWorks(listOf(w[1], w[6])))
            assertEquals(
                setOf(SiteSeriesRef("ficbook", "a"), SiteSeriesRef("ficbook", "b")),
                series.sitesOf(listOf(a)).getValue(a).toSet(),
            )
            assertEquals(listOf(SiteSeriesRef("author.today", "b")), series.sitesOf(listOf(b)).getValue(b))
            assertEquals(setOf(a, b, c), series.list.entries(listOf(a, b, c, d)).keys)
            // In the order of the changes of their lists of works, of one date by their numbers, from where it stood:
            // the
            // date kept whole, to its last digit
            val first = series.worksChangedAfter(null, 2)
            val next = series.worksChangedAfter(GroupCursor.parse(first.last().toString()), 2)
            assertEquals(listOf(b, c, a), (first + next).map { it.id })
            val streamed = HashMap<Long, List<Long>>()
            series.forEachSeries { id, of -> streamed[id] = of }
            assertEquals(listOf(w[0], w[1]), streamed[a])
        }
}
