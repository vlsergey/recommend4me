package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LuceneSearchTest {

    private fun doc(id: String, title: String, author: String, text: String) = SearchDocument(
        ItemKey("test", id),
        listOf(
            SearchField("title", "Название", 1.0f, listOf(title), naming = true),
            SearchField("author", "Автор", 1.0f, listOf(author), list = true, naming = true),
            SearchField("annotation", "Аннотация", 0.6f, listOf(text)),
        ),
        emptyList(),
    )

    private val documents = listOf(
        doc("1", "Stolen Memories", "Herandu Games", "A detective story in a rainy city."),
        doc("2", "Beach Day", "Somebody", "Her clothes were stolen at the beach, and the day went on from there."),
        doc("3", "Повелитель драконов", "Иван Петров", "Юноша находит яйцо дракона и становится всадником."),
    )

    private val context = object : SearchContext {
        override fun queryVector(line: String): FloatArray? = null
        override fun phraseVectors(texts: Collection<String>): Map<String, FloatArray> = emptyMap()
        override fun documents(keys: Collection<ItemKey>) = documents.filter { it.key in keys }.associateBy { it.key }
    }

    @Test
    fun `words in any form, names typed in part, Russian endings`() {
        LuceneSearch(Files.createTempDirectory("search")).use { search ->
            search.rebuild("books", documents.asSequence())
            // The words standing together in one text beat a title that has one of them
            val clothes = search.search("books", "stolen clothes", context)
            assertEquals("2", clothes.keys.first().id)
            // A developer's name typed in part
            assertEquals(listOf("1"), search.search("books", "herand", context).keys.map { it.id })
            // Another case of a Russian word
            assertEquals(listOf("3"), search.search("books", "драконы", context).keys.map { it.id })

            val match = search.matches("books", clothes, clothes.keys, context).getValue(ItemKey("test", "2"))
            assertEquals("Аннотация", match.field)
            assertTrue(match.highlights.isNotEmpty())
        }
    }

    @Test
    fun `an update is found without a rebuild`() {
        LuceneSearch(Files.createTempDirectory("search")).use { search ->
            search.rebuild("books", documents.take(1).asSequence())
            search.update("books", listOf(doc("9", "Lighthouse Keeper", "Nobody", "A quiet game about a lighthouse.")), emptyList())
            assertEquals(listOf("9"), search.search("books", "lighthouse", context).keys.map { it.id })
            search.update("books", emptyList(), listOf(ItemKey("test", "9")))
            assertTrue(search.search("books", "lighthouse", context).keys.isEmpty())
        }
    }
}
