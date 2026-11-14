package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.author.SiteAuthorRef
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.correction.TypeLinkRepository
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.manner.MannerGroup
import io.github.vlsergey.recommend4me.manner.Manners
import io.github.vlsergey.recommend4me.manner.TypeManners
import io.github.vlsergey.recommend4me.series.TypeSeries
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.work.Works
import java.time.Instant
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.TestPropertySource

/**
 * The manner of the works' texts by the frequencies of their words: of a work, of its normalized authors — a work of no
 * text of its own has theirs —, made again when a text changes, the words fitted again when the texts are more.
 */
@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/two-sites-manners"])
class TwoSitesMannersTest : TwoSitesTestBase() {
    @Autowired lateinit var manners: Manners
    @Autowired lateinit var works: Works

    /** A work of the site alpha by the [author], of the [texts] as its chapters, of none when empty. */
    private fun work(id: String, author: String, vararg texts: String) {
        val context = contexts.of("alpha")
        context.items.upsert(ItemHead(id, "https://alpha.example/work/$id", "Work $id"), Instant.now())
        context.items.setFacet(id, Books.AUTHOR.key, listOf(FacetValue(author, author)))
        if (texts.isNotEmpty())
            context.items.saveParts(id, texts.mapIndexed { k, t -> PartData("p$k", k, "Chapter $k", t) })
    }

    /** The two manners alike within the digits of half precision, in which they are kept. */
    private fun assertNear(expected: FloatArray, actual: FloatArray?) {
        assertNotNull(actual)
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals(expected[it], actual[it], HALF * (1 + abs(expected[it])), "at $it") }
    }

    private fun mean(vararg vectors: FloatArray) =
        FloatArray(vectors[0].size) { k -> vectors.map { it[k] }.average().toFloat() }

    private fun read(): TypeManners {
        val type = stores.type("books")!!
        return TypeManners.read(type, works.of(type), TypeSeries.read(type))
    }

    @Test
    fun `a work has its manner, its author's of all their works, and one of no text its author's alone`() {
        work("m1", "ann", "Она шла словно во сне, и вдруг остановилась. Потом она снова пошла.")
        work("m2", "ann", "Она смотрела словно сквозь него и молчала. Потом она снова улыбнулась.")
        work("m3", "ann")
        work("m4", "bob", "He walked to the door and the door was open, and he went in.")
        val type = stores.type("books")!!
        manners.refresh(type)
        val words = assertNotNull(type.mannerWords.find())
        assertEquals(3, words.texts)

        val all = read()
        val one = all.blocks(ItemKey("alpha", "m1"))
        val two = all.blocks(ItemKey("alpha", "m2"))
        val own = assertNotNull(one[TypeManners.WORK])
        assertNotEquals(own.toList(), two.getValue(TypeManners.WORK).toList())
        // The author's: the mean of both their texts, the same of every work of theirs
        val ann = mean(own, two.getValue(TypeManners.WORK))
        assertNear(ann, one[TypeManners.AUTHOR])
        // Kept of the normalized author
        val annId = type.authors.of(SiteAuthorRef("alpha", "ann"))!!
        val bobId = type.authors.of(SiteAuthorRef("alpha", "bob"))!!
        assertEquals(setOf("$annId", "$bobId"), type.groupManners.made(MannerGroup.AUTHOR).keys)
        assertNear(ann, type.groupManners.all(MannerGroup.AUTHOR, words.id)["$annId"])
        val none = all.blocks(ItemKey("alpha", "m3"))
        assertNull(none[TypeManners.WORK], "a work of no text has no manner of its own")
        assertNear(ann, none[TypeManners.AUTHOR])
        assertNotEquals(ann.toList(), all.blocks(ItemKey("alpha", "m4")).getValue(TypeManners.AUTHOR).toList())
        // A request reads the same of the works asked for alone
        val asked = TypeManners.of(type, works.of(type), listOf(ItemKey("alpha", "m3")))
        assertNear(ann, asked.blocks(ItemKey("alpha", "m3"))[TypeManners.AUTHOR])

        // A text changed: its manner made again by the words kept, the others' as they were
        val m4 = type.source("alpha")!!.manner.made().getValue("m4")
        contexts.of("alpha").items.saveParts("m1", listOf(PartData("p0", 0, "Chapter 0", "Она бежала и бежала.")))
        manners.refresh(type)
        assertEquals(words.id, type.mannerWords.id())
        val again = read()
        assertNotEquals(own.toList(), again.blocks(ItemKey("alpha", "m1")).getValue(TypeManners.WORK).toList())
        assertEquals(m4, type.source("alpha")!!.manner.made().getValue("m4"))

        // A text more: the words fitted again, every manner made by them
        work("m5", "bob", "The night was long and the road was longer.")
        manners.refresh(type)
        val refitted = assertNotNull(type.mannerWords.find())
        assertEquals(4, refitted.texts)
        assertNotEquals(words.id, refitted.id)
        assertEquals(setOf(refitted.id), type.source("alpha")!!.manner.made().values.map { it.wordsId }.toSet())

        // Two works said to be one series: the series' manner kept, the mean of theirs; a work of no series none
        val now = Instant.now()
        type.links.relate(ItemKey("alpha", "m1"), ItemKey("alpha", "m4"), TypeLinkRepository.SERIES, now)
        manners.refresh(type)
        val series = read()
        val first = series.blocks(ItemKey("alpha", "m1")).getValue(TypeManners.WORK)
        val fourth = series.blocks(ItemKey("alpha", "m4")).getValue(TypeManners.WORK)
        assertNear(mean(first, fourth), series.blocks(ItemKey("alpha", "m1"))[TypeManners.SERIES])
        assertNear(mean(first, fourth), series.blocks(ItemKey("alpha", "m4"))[TypeManners.SERIES])
        assertNull(series.blocks(ItemKey("alpha", "m2"))[TypeManners.SERIES])
        assertEquals(1, type.groupManners.made(MannerGroup.SERIES).size)

        // Two authors merged: one manner of all their works, the other's gone
        type.authors.merge(maxOf(annId, bobId), minOf(annId, bobId), now)
        manners.refresh(type)
        val merged = read()
        val texts =
            listOf("m1", "m2", "m4", "m5").map { merged.blocks(ItemKey("alpha", it)).getValue(TypeManners.WORK) }
        assertNear(mean(*texts.toTypedArray()), merged.blocks(ItemKey("alpha", "m3"))[TypeManners.AUTHOR])
        assertEquals(setOf("${minOf(annId, bobId)}"), type.groupManners.made(MannerGroup.AUTHOR).keys)
    }

    private companion object {
        /** The relative error of a number kept in half precision. */
        const val HALF = 2e-3f
    }
}
