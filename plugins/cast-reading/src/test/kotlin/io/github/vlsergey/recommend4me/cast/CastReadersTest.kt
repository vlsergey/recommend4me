package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.suggestion.InputBlock
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** The ways the sites' lines of characters and pairings are given to the models. */
class CastReadersTest {
    /** Two works: ficbook's "Гарри Поттер/Драко Малфой, Гермиона Грейнджер", and one whose site has no line. */
    private val casts =
        listOf(
            SaidCast.of(listOf("Гарри Поттер/Драко Малфой", "Гермиона Грейнджер"), '/'),
            SaidCast.NONE,
        )

    /** The characters of the first work's universe by their names; the second of no universe known. */
    private val characters =
        listOf(
            mapOf(
                "hp:harry" to listOf("Гарри Поттер", "Harry Potter"),
                "hp:draco" to listOf("Драко Малфой"),
                "hp:hermione" to listOf("Гермиона Грейнджер", "Hermione"),
                // A namesake: one name, two characters
                "hp:harry2" to listOf("гарри поттер"),
            ),
            null,
        )

    private val line = InputBlock.of("text:pairingsLine", Matrix(2, 2), booleanArrayOf(true, false))

    private fun works(vectors: Map<String, FloatArray> = emptyMap(), paired: Boolean = false) =
        CastWorks(
            casts,
            line,
            listOf(listOf("pairing\u0001гарри поттер/драко малфой"), emptyList()),
            vectors,
            characters,
            paired,
        )

    @Test
    fun `the cast of a line split by the site's habit`() {
        val cast = casts[0]
        assertEquals(listOf("Гарри Поттер", "Драко Малфой", "Гермиона Грейнджер"), cast.characters)
        assertEquals(listOf(listOf("Гарри Поттер", "Драко Малфой")), cast.pairings)
        // A slash alone, an empty name, a trailing space: no pairing of one name
        val odd = SaidCast.of(listOf(" Все /", "ОЖП "), '/')
        assertEquals(listOf("Все", "ОЖП"), odd.characters)
        assertTrue(odd.pairings.isEmpty())
    }

    @Test
    fun `the line whole is the line's block and the site's entries, as before`() {
        val read = WholeLine().read(works())
        assertSame(line, read.inputs.single())
        assertEquals(works().entries, read.context)
        assertTrue(read.named.all { it.isEmpty() })
    }

    @Test
    fun `every name a vector, the mean of the work's names of unit length`() {
        val reader = NameVectors()
        assertEquals(
            listOf("Гарри Поттер", "Драко Малфой", "Гермиона Грейнджер", "Гарри Поттер / Драко Малфой"),
            reader.encoded(casts[0]),
        )
        val vectors =
            mapOf(
                "Гарри Поттер" to floatArrayOf(1f, 0f),
                "Драко Малфой" to floatArrayOf(0f, 1f),
                // Not encoded yet: left out of the mean
                "Гарри Поттер / Драко Малфой" to floatArrayOf(3f, 4f),
            )
        val blocks = reader.read(works(vectors)).inputs.associateBy { it.name }
        val names = blocks.getValue(NameVectors.CHARACTERS)
        // The first work alone has names encoded
        assertEquals(listOf(0), names.rows.toList())
        val half = (1 / sqrt(2.0)).toFloat()
        assertEquals(half, names.vectors[0, 0], 1e-3f)
        assertEquals(half, names.vectors[0, 1], 1e-3f)
        val pairings = blocks.getValue(NameVectors.PAIRINGS)
        assertEquals(0.6f, pairings.vectors[0, 0], 1e-3f)
        assertEquals(0.8f, pairings.vectors[0, 1], 1e-3f)
        // Nothing encoded: no block
        assertTrue(reader.read(works()).inputs.isEmpty())
    }

    @Test
    fun `every name and every pairing a column of its own, by its form`() {
        val read = NameFeatures().read(works())
        assertEquals(
            listOf(
                "cast.name\u0001гарри поттер",
                "cast.name\u0001драко малфой",
                "cast.name\u0001гермиона грейнджер",
                "cast.pairing\u0001гарри поттер|драко малфой",
            ),
            read.context[0],
        )
        assertTrue(read.context[1].isEmpty())
        assertTrue(read.inputs.isEmpty())
        assertEquals("гарри поттер", NameForm.of("  Гарри   ПОТТЕР "))
        assertEquals("артем", NameForm.of("Артём"))
    }

    @Test
    fun `the names found in the dictionary name its characters, every namesake, and their pairings`() {
        val reader = DictionaryNames()
        val ofCharacters = reader.read(works()).named
        assertEquals(setOf("hp:harry", "hp:harry2", "hp:draco", "hp:hermione"), ofCharacters[0].toSet())
        assertTrue(ofCharacters[1].isEmpty())
        val ofPairings = reader.read(works(paired = true)).named
        assertEquals(
            setOf(UniverseFacets.pairing("hp:harry", "hp:draco"), UniverseFacets.pairing("hp:harry2", "hp:draco")),
            ofPairings[0].toSet(),
        )
    }

    @Test
    fun `every way at once is the line, the vectors, the features and the dictionary side by side`() {
        val vectors = mapOf("Гарри Поттер" to floatArrayOf(1f, 0f))
        val read = EveryWay().read(works(vectors))
        assertEquals(listOf("text:pairingsLine", NameVectors.CHARACTERS), read.inputs.map { it.name })
        assertEquals(works().entries[0] + NameFeatures.columns(casts[0]), read.context[0])
        assertEquals(DictionaryNames.named(works()), read.named)
        assertNull(EveryWay().read(works()).inputs.firstOrNull { it.name == NameVectors.CHARACTERS })
    }
}
