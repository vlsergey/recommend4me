package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkPairJournal
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Which items are one work and the user's word on those that are not, kept by their normalized works. */
class TypeLinkRepositoryTest {

    @TempDir lateinit var folder: Path

    @Test
    fun `a link makes one work, a word on two works is a word on every item of them`() {
        Database("dictionaries", folder.resolve("dictionaries"), "dictionaries").use { dictionaries ->
            val links =
                TypeLinkRepository(
                    WorkRepository(dictionaries.dsl),
                    WorkPairJournal(dictionaries.dsl),
                    listOf("a", "b"),
                )
            val now = Instant.now()
            links.link(ItemKey("a", "3"), ItemKey("a", "1"), now)
            links.link(ItemKey("a", "1"), ItemKey("b", "2"), now)
            links.distinct(ItemKey("a", "1"), ItemKey("b", "7"), now)
            links.relate(ItemKey("a", "1"), ItemKey("b", "8"), "TRANSLATION", now)
            assertEquals(
                setOf(ItemKey("a", "1"), ItemKey("a", "3"), ItemKey("b", "2")),
                links.groups().single().toSet(),
            )
            assertTrue(links.ordered(ItemKey("a", "3"), ItemKey("b", "7")) in links.distincts())
            assertEquals("TRANSLATION", links.relations()[links.ordered(ItemKey("b", "2"), ItemKey("b", "8"))])

            // Taken out of a work of several, an item goes on its own; gone from the library, with its work
            links.unlink(ItemKey("a", "1"), ItemKey("a", "3"), now)
            assertEquals(setOf(ItemKey("a", "1"), ItemKey("b", "2")), links.groups().single().toSet())

            // Every word in the journal, the newest first, with the word the pair had before
            links.distinct(ItemKey("b", "2"), ItemKey("b", "7"), now)
            links.takeBack(ItemKey("a", "1"), ItemKey("b", "7"), now)
            links.unlink(ItemKey("a", "3"), ItemKey("b", "7"), now)
            assertEquals(
                listOf(
                    null to "DIFFERENT",
                    "DIFFERENT" to "DIFFERENT",
                    null to "SAME",
                    "TRANSLATION" to null,
                    "DIFFERENT" to null,
                    "SAME" to null,
                    "SAME" to null,
                ),
                links.journal().map { it.answer to it.before },
            )
            assertEquals(
                mapOf(
                    links.ordered(ItemKey("a", "1"), ItemKey("b", "2")) to "SAME",
                    links.ordered(ItemKey("a", "1"), ItemKey("b", "8")) to "TRANSLATION",
                    (ItemKey("a", "1") to ItemKey("b", "9")) to null,
                ),
                links.words(
                    listOf(
                        links.ordered(ItemKey("a", "1"), ItemKey("b", "2")),
                        links.ordered(ItemKey("a", "1"), ItemKey("b", "8")),
                        ItemKey("a", "1") to ItemKey("b", "9"),
                    )
                ),
            )
            links.forget(ItemKey("b", "2"))
            assertEquals(emptyList(), links.groups())
            links.forget(ItemKey("a", "1"))
            assertEquals(emptySet(), links.distincts())
        }
    }

    @Test
    fun `a word on two works reaches every item of them, those linked after it too`() {
        Database("dictionaries", folder.resolve("dictionaries"), "dictionaries").use { dictionaries ->
            val links =
                TypeLinkRepository(
                    WorkRepository(dictionaries.dsl),
                    WorkPairJournal(dictionaries.dsl),
                    listOf("a", "b", "c"),
                )
            val now = Instant.now()
            fun said(x: ItemKey, y: ItemKey) =
                links.relations()[links.ordered(x, y)]
                    ?: "DIFFERENT".takeIf { links.ordered(x, y) in links.distincts() }
            val a1 = ItemKey("a", "1")
            val b1 = ItemKey("b", "1")
            val c1 = ItemKey("c", "1")
            val c2 = ItemKey("c", "2")
            links.link(a1, b1, now)
            links.relate(a1, c1, "SERIES", now)
            links.distinct(b1, c2, now)
            assertEquals("SERIES", said(b1, c1))
            assertEquals("DIFFERENT", said(a1, c2))

            // An item linked to a work after the word: the word is on it too, of either side
            val b2 = ItemKey("b", "2")
            links.link(b2, c1, now)
            assertEquals("SERIES", said(a1, b2))
            assertEquals("SERIES", said(b1, b2))

            // Taken out of its work by a word, an item takes no word of the work with it
            links.distinct(c1, b2, now)
            assertEquals("DIFFERENT", said(c1, b2))
            assertEquals("SERIES", said(a1, c1))
            assertEquals(null, said(a1, b2))
        }
    }

    @Test
    fun `works of one series by a chain are of one series, a word that parts two of them parts both from it`() {
        Database("dictionaries", folder.resolve("dictionaries"), "dictionaries").use { dictionaries ->
            val links =
                TypeLinkRepository(
                    WorkRepository(dictionaries.dsl),
                    WorkPairJournal(dictionaries.dsl),
                    listOf("a", "b", "c", "d"),
                )
            val now = Instant.now()
            val a = ItemKey("a", "1")
            val b = ItemKey("b", "1")
            val c = ItemKey("c", "1")
            val d = ItemKey("d", "1")
            fun chained(x: ItemKey, y: ItemKey) = links.ordered(x, y) in links.inSeries()
            fun series(x: ItemKey, y: ItemKey) = links.relations()[links.ordered(x, y)] == "SERIES" || chained(x, y)
            links.relate(a, b, "SERIES", now)
            links.relate(b, c, "SERIES", now)
            links.relate(c, d, "SERIES", now)
            assertTrue(chained(a, c) && chained(a, d) && chained(b, d))
            assertTrue(!chained(a, b), "a word said is no word by the chain")

            // Different, of two of one series by the chain: both out of it, the rest of it one series still
            links.distinct(a, c, now)
            assertTrue(!series(a, b) && !series(b, c) && !series(c, d) && !series(a, d))
            assertEquals(
                "DIFFERENT",
                links.relations()[links.ordered(a, c)]
                    ?: "DIFFERENT"
                        .takeIf {
                            links.ordered(a, c) in links.distincts()
                        },
            )

            // The word taken back, of two of one series by the chain: both out of it
            links.relate(a, b, "SERIES", now)
            links.relate(b, d, "SERIES", now)
            links.relate(d, c, "SERIES", now)
            assertTrue(chained(a, d))
            links.takeBack(a, d, now)
            assertTrue(!series(a, b) && !series(b, d) && !series(d, c))
            // Every word of one series taken back so is in the journal, the word before it "one series"
            assertEquals(
                listOf(null to "SERIES", null to "SERIES", null to "SERIES"),
                links.journal().take(3).map {
                    it.answer to it.before
                },
            )
        }
    }
}
