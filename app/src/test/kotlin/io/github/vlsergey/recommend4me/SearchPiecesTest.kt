package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.database.Databases
import io.github.vlsergey.recommend4me.database.model.tables.references.PIECE_PROJECTION
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.part.Paragraphs
import io.github.vlsergey.recommend4me.piece.PieceProjections
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.search.Search
import io.github.vlsergey.recommend4me.search.SearchPieces
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.task.inBackground
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The pieces of the texts, end to end (docs/search.md, "The pieces"): the projection learnt of the type's chapters, the
 * chapters' pieces made by the stage of the chapters as it is set at nought by it, and a search reading them — the work
 * whose chapter says the line's words together above the one that says them apart, the place of them found. The
 * projection taken back after: the other classes' searches have none.
 */
class SearchPiecesTest : SiteTestBase() {
    @Autowired lateinit var projections: PieceProjections

    @Autowired lateinit var search: Search

    @Autowired lateinit var plugins: Plugins

    @Autowired lateinit var databases: Databases

    private val ids = (1..5).map { "spc$it" }

    /**
     * The words of the chapters, every one of five letters of two bytes, as every other class's chapters are: the words
     * to a character the type's model of sizes learns of them stay what the others learn.
     */
    private val filler =
        ("время ветер берег огонь камни дождь лодка город птица трава волна ночью песок месяц тучей полем рекой " +
                "домой хлеба сосна")
            .split(" ")

    /** A chapter of 210 words drawn of the [filler], the [words] put at the places they are given. */
    private fun chapter(seed: Int, words: Map<Int, String> = emptyMap()): String {
        val draw = Random(seed.toLong())
        return List(210) { words[it] ?: filler[draw.nextInt(filler.size)] }.joinToString(" ")
    }

    @AfterEach
    fun takenBack() {
        databases.model("books").deleteFrom(PIECE_PROJECTION).execute()
    }

    @Test
    fun `the pieces of the chapters made by the projection learnt, and the place of a line found by them`() {
        val store = stores.source(site)!!
        ids.forEach { capture("$it|The voyage $it|space|A work of chapters||") }
        ids.forEachIndexed { k, id ->
            store.parts.save(
                id,
                (1..12).map { n ->
                    val words =
                        when {
                            id == "spc1" && n == 3 -> mapOf(100 to "маяки", 101 to "рифов", 102 to "бухта")
                            id == "spc2" && n == 3 -> mapOf(20 to "маяки", 120 to "рифов", 200 to "бухта")
                            else -> emptyMap()
                        }
                    PartData("c$n", n, "Chapter $n", chapter(k * 100 + n, words), null)
                },
            )
        }
        refresh(store)
        inBackground { projections.fit("books") }
        assertNotNull(projections.of(stores.type("books")!!), "the projection learnt")
        // The chapters made by the projection of now: of a type with none at the start, learnt again by the background
        background.run()
        val fitted = projections.of(stores.type("books")!!)!!

        val encoder = plugins.textEncoder()!!
        assertTrue(
            store.parts.windows
                .waitingOf(ids, encoder.windowsId, Paragraphs.keyOf(encoder.id), fitted.projection.key)
                .isEmpty(),
            "every chapter's pieces made",
        )
        val codes = store.parts.pieces.of("spc1", listOf("c3"))["c3"]
        assertEquals(210 * fitted.codebook.bytes, codes?.size, "a code of every word")

        val found = search.search("books", "маяки рифов бухта", "ru", rerank = false, learnt = false)!!
        val together = ItemKey(site, "spc1")
        val apart = ItemKey(site, "spc2")
        fun piecesOf(key: ItemKey) = found.features[key]?.get(SearchPieces.PIECES) ?: 0.0
        assertTrue(piecesOf(together) > piecesOf(apart), "${piecesOf(together)} against ${piecesOf(apart)}")
        val place = assertNotNull(found.places[together])
        assertEquals("part:c3", place.field)
        assertEquals(listOf(100, 101, 102), place.marked)
    }
}
