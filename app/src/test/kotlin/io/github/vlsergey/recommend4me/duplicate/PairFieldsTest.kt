package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicateValue
import io.github.vlsergey.recommend4me.api.model.DuplicateWork
import io.github.vlsergey.recommend4me.api.model.PairMeasure.Kind
import io.github.vlsergey.recommend4me.api.model.PairRow
import io.github.vlsergey.recommend4me.item.ItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The window of a pair as the server lays it out (owner, 2026-11-20, of the sites' universes and characters shown "not
 * compared" though the search compares them): a field the search compares is compared, measured or not.
 */
class PairFieldsTest {

    private fun work(
        source: String,
        line: String?,
        tags: List<String>,
        siteTags: List<String>,
        compared: Boolean = true,
    ) =
        Described(
            DuplicateWork(source = source, item = "1", title = "Title", url = ""),
            emptySet(),
            ShownFields(
                authors = listOf("Author"),
                fandoms = listOf("Middle-earth"),
                description = "A tale",
                universeLine = line,
                siteTags = siteTags.map { DuplicateValue(it, null) },
                facets =
                    listOf(
                        ShownFacet("tags", "standard:tags", tags.map { DuplicateValue(it, null) }, compared = compared)
                    ),
                words = null,
                chars = null,
            ),
        )

    private val a = work("alpha", "Sauron/Thranduil", listOf("angst"), listOf("Angst"))
    private val b = work("beta", "Sauron/Thranduil Oropherion", listOf("angst", "drama"), listOf("Drama"))

    private fun layers(letters: Map<LetteredText, Float>) =
        DuplicateCandidate(ItemKey("alpha", "1"), ItemKey("beta", "1"), 1f, 0.9f, null, emptyMap(), letters = letters)

    private fun rows(layers: DuplicateCandidate?) =
        PairFields(a, b, layers, sameAuthor = false, kin = null).rows().associateBy { it.key }

    @Test
    fun `a text the search compares, of a pair it measured before it compared it, is compared and not measured`() {
        val rows = rows(layers(emptyMap()))
        listOf("siteFandoms", "universeLine").forEach { key ->
            assertEquals(PairRow.Group.COMPARED, rows.getValue(key).group, key)
            assertEquals(listOf(Kind.NOT_MEASURED), rows.getValue(key).measures.map { it.kind }, key)
        }
        // The description letter by letter not measured, by meaning measured
        assertEquals(listOf(Kind.NOT_MEASURED, Kind.MEANING), rows.getValue("description").measures.map { it.kind })
    }

    @Test
    fun `a text measured letter by letter shows its measure`() {
        val rows = rows(layers(mapOf(LetteredText.CHARACTERS to 0.8f, LetteredText.FANDOMS to 0.1f)))
        assertEquals(0.8, rows.getValue("universeLine").measures.single().value!!.toDouble(), 1e-6)
        assertEquals(Kind.LETTERS, rows.getValue("siteFandoms").measures.single().kind)
    }

    @Test
    fun `a facet the search compares, not among the layers of a pair it measured, is compared and not measured`() {
        val rows = rows(layers(emptyMap()))
        assertEquals(PairRow.Group.COMPARED, rows.getValue("tags").group)
        assertEquals(listOf(Kind.NOT_MEASURED), rows.getValue("tags").measures.map { it.kind })
        // The sites' own tags never compared, after the tags
        assertEquals(PairRow.Group.NOT_COMPARED, rows.getValue("siteTags").group)
        val order = PairFields(a, b, layers(emptyMap()), sameAuthor = false, kin = null).rows().map { it.key }
        assertEquals(order.indexOf("tags") + 1, order.indexOf("siteTags"))
    }

    @Test
    fun `a facet the search compares not stands below, the sites' tags in place of the tags`() {
        val x = work("alpha", null, listOf("angst"), listOf("Angst"), compared = false)
        val y = work("beta", null, listOf("angst"), listOf("Drama"), compared = false)
        val rows = PairFields(x, y, layers(emptyMap()), sameAuthor = false, kin = null).rows().associateBy { it.key }
        assertNull(rows["tags"])
        assertEquals(PairRow.Group.NOT_COMPARED, rows.getValue("siteTags").group)
        // Of a pair never measured, the tags compared by what they share
        val never = PairFields(a, b, null, sameAuthor = false, kin = null).rows()
        assertEquals(listOf(Kind.VALUES), never.single { it.key == "tags" }.measures.map { it.kind })
    }

    @Test
    fun `of a pair never measured, the texts the same word for word, else not measured`() {
        val rows = rows(null)
        assertEquals(listOf(Kind.SAME_TEXT), rows.getValue("title").measures.map { it.kind })
        assertEquals(listOf(Kind.SAME_TEXT), rows.getValue("siteFandoms").measures.map { it.kind })
        assertEquals(listOf(Kind.NOT_MEASURED), rows.getValue("universeLine").measures.map { it.kind })
    }
}
