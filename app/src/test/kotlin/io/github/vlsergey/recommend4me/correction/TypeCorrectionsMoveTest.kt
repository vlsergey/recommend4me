package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.search.SearchJudgmentRepository
import io.github.vlsergey.recommend4me.work.WorkRepository
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A type's corrections of before moved: its links to the sources' corrections and on to the normalized works, its search judgments to the preferences. */
class TypeCorrectionsMoveTest {

    @TempDir
    lateinit var folder: Path

    @Test
    fun `the links and judgments of a type are moved, the file kept aside`() {
        val old = folder.resolve("types/books/corrections")
        Files.createDirectories(old.parent)
        Database("books/corrections", old, "typecorrections").use { db ->
            db.dsl.execute("INSERT INTO item_link VALUES ('a', '1', 'b', '2', CURRENT_TIMESTAMP)")
            db.dsl.execute("INSERT INTO search_judgment VALUES ('space', 'b', '2', TRUE, CURRENT_TIMESTAMP)")
        }
        val a = Database("a/corrections", folder.resolve("a"), "corrections", placeholders = mapOf("source" to "a"))
        val b = Database("b/corrections", folder.resolve("b"), "corrections", placeholders = mapOf("source" to "b"))
        val preferences = Database("preferences", folder.resolve("preferences"), "preferences")
        val dictionaries = Database("dictionaries", folder.resolve("dictionaries"), "dictionaries")
        listOf(a, b, preferences, dictionaries).use {
            val corrections = mapOf("a" to a.dsl, "b" to b.dsl)
            TypeCorrectionsMove.move("books", old, corrections, preferences.dsl, dictionaries.dsl)
            assertFalse(Files.exists(folder.resolve("types/books/corrections.mv.db")))
            assertTrue(Files.exists(folder.resolve("types/books/corrections.mv.db.moved")))
            // Started again: nothing more to move
            TypeCorrectionsMove.move("books", old, corrections, preferences.dsl, dictionaries.dsl)

            assertEquals(mapOf(ItemKey("b", "2") to true), SearchJudgmentRepository(preferences.dsl, listOf("a", "b")).of(" Space "))
            assertEquals(emptyMap(), SearchJudgmentRepository(preferences.dsl, listOf("c")).of("space"))

            // Given to the normalized works: the link, a word on two works, and nothing left to give
            a.dsl.execute("INSERT INTO item_link (item_id, other_source, other_item_id, linked_at) VALUES ('3', 'a', '1', CURRENT_TIMESTAMP)")
            a.dsl.execute("INSERT INTO item_distinct (item_id, other_source, other_item_id, said_at) VALUES ('1', 'b', '7', CURRENT_TIMESTAMP)")
            a.dsl.execute("INSERT INTO item_relation (item_id, other_source, other_item_id, kind, said_at) VALUES ('1', 'b', '8', 'TRANSLATION', CURRENT_TIMESTAMP)")
            val links = TypeLinkRepository(WorkRepository(dictionaries.dsl), corrections)
            val now = Instant.now()
            assertEquals(4, links.moveToWorks(now))
            assertEquals(0, links.moveToWorks(now))
            assertEquals(setOf(ItemKey("a", "1"), ItemKey("a", "3"), ItemKey("b", "2")), links.groups().single().toSet())
            assertEquals(0, a.dsl.fetchCount(a.dsl.selectFrom("ITEM_LINK")))
            // A word on two works is a word on every item of them
            assertTrue(links.ordered(ItemKey("a", "3"), ItemKey("b", "7")) in links.distincts())
            assertEquals("TRANSLATION", links.relations()[links.ordered(ItemKey("b", "2"), ItemKey("b", "8"))])

            // Taken out of a work of several, an item goes on its own; gone from the library, with its work
            links.unlink(ItemKey("a", "1"), ItemKey("a", "3"), now)
            assertEquals(setOf(ItemKey("a", "1"), ItemKey("b", "2")), links.groups().single().toSet())
            links.forget(ItemKey("b", "2"))
            assertEquals(emptyList(), links.groups())
            links.forget(ItemKey("a", "1"))
            assertEquals(emptySet(), links.distincts())
        }
    }

    private fun <T> List<Database>.use(block: () -> T): T = try {
        block()
    } finally {
        forEach(Database::close)
    }
}
