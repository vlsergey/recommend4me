package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.flywaydb.core.api.configuration.Configuration
import org.flywaydb.core.api.migration.Context
import org.junit.jupiter.api.io.TempDir

/**
 * The normalized series of the data kept made by the migration (V45) as the application joins them: two series of two
 * sites sharing a work one, a group of the user's one held by the user's word, their titles of the sites' pages or
 * names, of the first work of a group alone; broken off, it goes on with the series not made.
 */
class NormalizedSeriesMadeTest {
    @TempDir lateinit var folder: Path

    /** A source's file of what the migration reads of it: every item's series, their names and their pages. */
    private fun source(id: String, facets: List<Pair<Long, String>>, names: Map<String, String>, seen: Set<String>) =
        folder.resolve(id).resolve("source").also { file ->
            DriverManager.getConnection("jdbc:h2:file:${file.toAbsolutePath().toString().replace('\\', '/')}", "sa", "")
                .use { c ->
                    c.createStatement().use { s ->
                        s.execute("CREATE TABLE item (internal_id BIGINT, title VARCHAR)")
                        s.execute(
                            "CREATE TABLE item_facet (item_id BIGINT, facet VARCHAR, value_key VARCHAR, position INT)"
                        )
                        s.execute("CREATE TABLE facet_value (facet VARCHAR, value_key VARCHAR, name VARCHAR)")
                        s.execute(
                            "CREATE TABLE series (series_key VARCHAR, title VARCHAR, " +
                                "seen_at TIMESTAMP WITH TIME ZONE, work_seen_at TIMESTAMP WITH TIME ZONE)"
                        )
                        facets.forEach { (item, key) ->
                            s.execute("INSERT INTO item_facet VALUES ($item, 'series', '$key', 0)")
                            s.execute("INSERT INTO item VALUES ($item, 'Work $item')")
                        }
                        names.forEach { (key, name) ->
                            s.execute("INSERT INTO facet_value VALUES ('series', '$key', '$name')")
                        }
                        seen.forEach { s.execute("INSERT INTO series VALUES ('$it', NULL, CURRENT_TIMESTAMP, NULL)") }
                        s.execute("INSERT INTO item VALUES (9, 'The grouped')")
                    }
                }
        }

    private fun migrated(c: Connection, sources: List<SourceFile>) =
        V45__normalized_series_made(sources)
            .migrate(
                object : Context {
                    override fun getConfiguration(): Configuration = error("not read")

                    override fun getConnection(): Connection = c
                }
            )

    @Test
    fun `the series of the sites and the user's groups joined as the application joins them, numbered once`() {
        val ficbook =
            source("ficbook", listOf(1L to "f1", 2L to "f1", 3L to "f2"), mapOf("f1" to "Galaxy"), setOf("f1"))
        val today = source("author.today", listOf(1L to "t1"), mapOf("t1" to "Галактика"), emptySet())
        Database("dictionaries", folder.resolve("dictionaries"), "dictionaries").use { dictionaries ->
            val works = WorkRepository(dictionaries.dsl)
            val now = Instant.now()
            // The first work of ficbook and of author.today one work: their two series one
            val one = works.of(ItemKey("ficbook", 1), now)
            works.membership.merge(works.of(ItemKey("author.today", 1), now), one, now)
            val two = works.of(ItemKey("ficbook", 2), now)
            val three = works.of(ItemKey("ficbook", 3), now)
            // Two works of no series of a site said one series
            val grouped = works.of(ItemKey("ficbook", 9), now)
            val other = works.of(ItemKey("ficbook", 8), now)
            SeriesGroups(dictionaries.dsl) {}.together(grouped, other, now)
            val sources = listOf(SourceFile("ficbook", "books", ficbook), SourceFile("author.today", "books", today))
            dictionaries.dsl.connectionResult { migrated(it, sources) }

            val series = NormalizedSeriesRepository(dictionaries.dsl, "books")
            val of = series.ofWorks(listOf(one, two, three, grouped, other))
            assertEquals(of[one], of[two])
            assertEquals(of[grouped], of[other])
            assertEquals(3, of.values.toSet().size)
            val joined = series.list.entries(listOf(of.getValue(one))).values.single()
            // Of its first series of a site by the key, as the list orders them
            assertEquals("Галактика", joined.title)
            assertEquals(2, joined.works)
            assertEquals(2, joined.sites)
            // Of author.today's page never read: to be visited
            assertEquals(true, joined.toVisit)
            assertEquals(false, joined.byHand)
            val said = series.list.entries(listOf(of.getValue(grouped))).values.single()
            assertEquals(true, said.byHand)
            assertEquals("The grouped", said.title)
            assertEquals(
                setOf(SiteSeriesRef("ficbook", "f1"), SiteSeriesRef("author.today", "t1")),
                series.sitesOf(listOf(of.getValue(one))).getValue(of.getValue(one)).toSet(),
            )

            // Broken off and run again: the series made kept, nothing made twice
            dictionaries.dsl.connectionResult { migrated(it, sources) }
            assertEquals(of, series.ofWorks(listOf(one, two, three, grouped, other)))
            assertEquals(3, series.list.page(SeriesFilter(), 0, 10).first)
        }
    }
}
