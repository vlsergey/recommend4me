package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.suggestion.InputBlock
import io.github.vlsergey.recommend4me.suggestion.TaskContext
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.junit.jupiter.api.Test

/** What the way chosen makes of a facet's task: the line's own inputs and columns given to it, its own put in place. */
class TaskCastTest {
    private val line = InputBlock.of("text:pairingsLine", Matrix(3, 2), booleanArrayOf(true, true, false))
    private val annotation = InputBlock.of("text:annotation", Matrix(3, 2), booleanArrayOf(true, true, true))
    private val own = InputBlock.of("text:own", Matrix(3, 2), booleanArrayOf(true, false, false))

    /** A way that hands back what it was given, and of its own a block, a column of a name and a value named. */
    private val reader =
        object : CastReader {
            override val id = "test"
            var given: CastWorks? = null

            override fun encoded(cast: SaidCast) = cast.characters

            override fun read(works: CastWorks): CastRead {
                given = works
                return CastRead(
                    listOf(own),
                    works.casts.map { c -> c.characters.map { "name\u0001$it" } },
                    works.casts.map { c -> if ("A" in c.characters) listOf("u:a") else emptyList() },
                )
            }
        }

    private val asked =
        CastAsked(
            reader,
            entries = "pairing",
            line = "text:pairingsLine",
            characters = { id -> if (id == "w3") null else mapOf("u:a" to listOf("A")) },
            paired = false,
            vectors = { texts -> texts.filter { it == "A" }.associateWith { floatArrayOf(1f, 0f) } },
        )

    @Test
    fun `the line and its entries go to the way, its blocks and columns take their place`() {
        val casts =
            listOf(
                SaidCast.of(listOf("A/B"), '/'),
                SaidCast.of(listOf("A", "C"), '/'),
                SaidCast.NONE,
            )
        val context =
            listOf(
                listOf("tag\u0001x", "pairing\u0001a/b"),
                listOf("tag\u0001x", "pairing\u0001a", "pairing\u0001c"),
                listOf("tag\u0001y"),
            )
        val cast = TaskCast.of(asked, listOf("w1", "w2", "w3"), casts, listOf(annotation, line), context)
        val given = reader.given!!
        // The way is given the line, the site's entries, the vectors asked for that are kept, the characters
        assertSame(line, given.line)
        assertEquals(
            listOf(listOf("pairing\u0001a/b"), listOf("pairing\u0001a", "pairing\u0001c"), emptyList()),
            given.entries,
        )
        assertEquals(setOf("A"), given.vectors.keys)
        assertEquals(null, given.characters[2])
        // The line's block taken out, the way's put in
        assertEquals(listOf("text:annotation", "text:own"), cast.inputs.map { it.name })
        assertEquals(
            listOf(
                listOf("tag\u0001x", "name\u0001A", "name\u0001B"),
                listOf("tag\u0001x", "name\u0001A", "name\u0001C"),
                listOf("tag\u0001y"),
            ),
            cast.context,
        )
        assertEquals(listOf(listOf("u:a"), listOf("u:a"), emptyList()), cast.named)
        assertEquals("test:1", cast.signature)
    }

    @Test
    fun `the columns by their keys, of a key more than one work has alone when shared`() {
        val rows = listOf(listOf("a", "b"), listOf("a", "c"), listOf("b", "b"))
        val shared = TaskContext.ofKeys(rows, shared = true)
        assertEquals(listOf("a", "b"), shared.keys)
        assertEquals(listOf(listOf("a", "b"), listOf("a"), listOf("b")), shared.keysOf())
        assertEquals(listOf("a", "b", "c"), TaskContext.ofKeys(rows, shared = false).keys)
    }
}
