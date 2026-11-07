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
}
