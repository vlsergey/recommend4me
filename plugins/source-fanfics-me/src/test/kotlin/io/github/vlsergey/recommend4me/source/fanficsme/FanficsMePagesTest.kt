package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.contenttype.AgeRating
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.MemorySourceContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FanficsMePagesTest {

    private val source = FanficsMe()
    private val now = Instant.parse("2026-10-28T15:00:00Z")

    private fun page(name: String) = javaClass.getResource("/pages/$name")!!.readText()

    private fun capture(context: MemorySourceContext, url: String, name: String): List<String> {
        assertTrue(source.capturePatterns.any { it.matches(url) }, "$url is wanted")
        return source.capture(CapturedPage(url, page(name), now), context)
    }

    @Test
    fun `the cards of the search give the works with all their header says`() {
        val context = MemorySourceContext()
        assertEquals(listOf("251745", "251243", "218574", "252329", "247092"), capture(context, "https://fanfics.me/find?reit1=0&page=1", "find.html"))
        val work = "247092"
        assertEquals("Когда моя бабушка умрёт?", context.heads.getValue(work).title)
        // "изменено 28.10.2026", in Moscow time
        assertEquals(Instant.parse("2026-10-27T21:00:00Z"), context.heads.getValue(work).updatedAt)
        val facets = context.facets.getValue(work)
        assertEquals(listOf("1068781"), facets["author"])
        assertEquals(listOf("50"), facets["fandom"])
        assertEquals(listOf("fanfiction"), facets["kind"])
        assertEquals(listOf("het"), facets["direction"])
        assertEquals(listOf("nc-17"), facets[AgeRating.SITE_KEY])
        assertEquals(listOf("nc-17"), facets[AgeRating.KEY])
        assertEquals(listOf("in-progress"), facets["status"])
        assertEquals(listOf("макси"), facets["size"])
        // The genres, the warnings and the events: the site's tags
        val tags = facets.getValue("tag")
        listOf("детектив", "нецензурная лексика", "т/и / л/и", "дом дракона", "фик об оригинальных героях").forEach { assertTrue(it in tags, "$it in $tags") }
        // The characters by their ids of the site's encyclopedia, the names as written
        assertEquals("109000", facets.getValue("pairing").first())
        assertEquals("Эймонд Таргариен", context.facetNames.getValue("pairing")["109000"])
        assertTrue(context.texts.getValue(work).getValue(FanficsMe.PAIRINGS_LINE).startsWith("Эймонд Таргариен, Деймон Таргариен (Порочный принц), "))
        assertTrue(context.texts.getValue(work).getValue(FanficsMe.ANNOTATION).startsWith("Писательница без книг."))
        val numbers = context.numbers.getValue(work)
        assertEquals(148239.0, numbers["chars"])
        assertEquals(2.0, numbers["readers"])
        assertTrue((numbers["views"] ?: 0.0) > 0)
        assertEquals(listOf("https://fanfics.me/images/fanart/2026/07/07/1783406848483_200_300.jpg"), context.pictures[work])
    }

    @Test
    fun `a card's pairings by the ids of their characters, a translation by its translator, an original by its fandom`() {
        val context = MemorySourceContext()
        capture(context, "https://fanfics.me/find?reit1=0&page=1", "find.html")
        val pairings = context.facets.getValue("218574").getValue("pairing")
        assertTrue("72/98" in pairings, "$pairings")
        assertTrue("1" in pairings, "$pairings")
        // A translation's card names its translator alone, its author here: the writer of the original is on its page
        val translation = context.facets.getValue("251745")
        assertEquals(listOf("52961"), translation["author"])
        assertNull(translation["originalAuthor"])
        assertEquals(listOf("original"), context.facets.getValue("252329")["kind"])
        assertEquals(6, context.facets.getValue("251243").getValue("fandom").size)
    }

    @Test
    fun `a work's page gives its header, its notes and its table of contents`() {
        val context = MemorySourceContext()
        val work = "252474"
        assertEquals(listOf(work), capture(context, "https://fanfics.me/fic$work", "fic.html"))
        assertEquals("Гарри Поттер и Миррина из Слизерина", context.heads.getValue(work).title)
        val facets = context.facets.getValue(work)
        assertEquals(listOf("1029842"), facets["author"])
        assertEquals(listOf("2"), facets["fandom"])
        assertEquals(listOf("het"), facets["direction"])
        // "General" is the scale's G
        assertEquals(listOf("g"), facets[AgeRating.SITE_KEY])
        assertEquals(listOf("finished"), facets["status"])
        assertEquals("1/2/3", facets.getValue("pairing").first())
        assertEquals("Гарри Поттер/Гермиона Грейнджер/Рон Уизли", context.facetNames.getValue("pairing")["1/2/3"])
        val tags = facets.getValue("tag")
        listOf("приключения", "попаданцы", "распределение в другие факультеты").forEach { assertTrue(it in tags, "$it in $tags") }
        // Not a translation: nothing of an original
        assertEquals(emptyList(), facets["originalLanguage"])
        val numbers = context.numbers.getValue(work)
        assertEquals(6423727.0, numbers["chars"])
        assertEquals(897390.0, numbers["words"])
        assertEquals(2612.0, numbers["views"])
        assertEquals(27.0, numbers["readers"])
        assertEquals(0.0, numbers["comments"])
        assertEquals(0.0, numbers["recommendations"])
        // The page lists the first three chapters, the rest hidden, and the last three: the hidden left out of the page kept here
        assertEquals(6.0, numbers["parts"])
        val texts = context.texts.getValue(work)
        assertTrue(texts.getValue(FanficsMe.ANNOTATION).startsWith("Продолжение серии книг и фильмов о Гарри Поттере"))
        assertTrue(texts.getValue(FanficsMe.NOTES).startsWith("Сиквел изначально задумывался"))
        assertTrue("Свернуть" !in texts.getValue(FanficsMe.NOTES))
        val parts = context.parts.getValue(work)
        assertEquals(listOf("0", "1", "2", "104", "105", "106"), parts.keys.toList())
        assertEquals("Сюжет 1 серии: Ожившее прошлое Дж. К. Роулинг", parts.getValue("0").title)
        assertEquals(Instant.parse("2026-10-24T21:00:00Z"), parts.getValue("0").publishedAt)
        assertNull(parts.getValue("0").content)
        assertEquals(listOf("https://fanfics.me/images/fanart/2026/10/25/1792907630596_200_300.jpg"), context.pictures[work])
    }

    @Test
    fun `a translation's page names the author of the original, its title, address and language`() {
        val context = MemorySourceContext()
        val work = "251745"
        capture(context, "https://fanfics.me/fic$work", "fic-translation.html")
        val facets = context.facets.getValue(work)
        // Its author is who put it on the site, the translator
        assertEquals(listOf("52961"), facets["author"])
        assertEquals(listOf("anorc"), facets["originalAuthor"])
        assertEquals(listOf("английский"), facets["originalLanguage"])
        // The work's own status, not its original's
        assertEquals(listOf("in-progress"), facets["status"])
        assertEquals(listOf("4940"), facets["series"])
        assertEquals(listOf("nc-17"), facets[AgeRating.SITE_KEY])
        assertTrue("рейтинг за секс" in facets.getValue("tag"))
        val texts = context.texts.getValue(work)
        assertEquals("Not with a Half-blood, redux", texts[FanficsMe.ORIGINAL_TITLE])
        assertEquals("https://archiveofourown.org/works/63705664", texts[FanficsMe.ORIGINAL_LINK])
    }

    @Test
    fun `the reader gives the text of every chapter it shows`() {
        val context = MemorySourceContext()
        val work = "252474"
        capture(context, "https://fanfics.me/fic$work", "fic.html")
        assertEquals(listOf(work), capture(context, "https://fanfics.me/read.php?id=$work", "read-whole.html"))
        val parts = context.parts.getValue(work)
        assertEquals("Сюжет 2 серии: На пути к Подземному Замку", parts.getValue("1").title)
        assertTrue(parts.getValue("0").content!!.isNotBlank())
        assertTrue(parts.getValue("1").content!!.isNotBlank())
        assertEquals(Instant.parse("2026-10-24T21:00:00Z"), parts.getValue("1").publishedAt)
    }

    @Test
    fun `a work of one chapter is read whole`() {
        val context = MemorySourceContext()
        val work = "93089"
        capture(context, "https://fanfics.me/fic$work", "fic-mini.html")
        assertEquals(1.0, context.numbers.getValue(work)["parts"])
        assertEquals(setOf(FanficsMePages.ONLY_CHAPTER), context.parts.getValue(work).keys)
        capture(context, "https://fanfics.me/read.php?id=$work", "read-mini.html")
        val part = context.parts.getValue(work).getValue(FanficsMePages.ONLY_CHAPTER)
        assertTrue(part.content!!.startsWith("Вокруг — никого."))
        assertNull(part.title)
    }

    @Test
    fun `the comments of a work's page are its reviews, the quotes and the one answered left out`() {
        val context = MemorySourceContext()
        val work = "226660"
        capture(context, "https://fanfics.me/fic$work", "fic-comments.html")
        val reviews = context.reviews.getValue(work)
        assertEquals(setOf("2691976", "2691985", "2691986"), reviews.keys)
        val first = reviews.getValue("2691976")
        assertEquals("Да, по кастингу Люциус и Драко белые. Непорядок))", first.content)
        assertEquals("Элли Драйвер", first.author)
        // "сегодня в 17:19", of the day the page was seen, in Moscow time
        assertEquals(Instant.parse("2026-10-28T14:19:00Z"), first.postedAt)
    }

    @Test
    fun `the site's help and a fandom's events say what the tags mean`() {
        val context = MemorySourceContext()
        capture(context, "https://fanfics.me/html?article=about_warnings", "help-warnings.html")
        val described = context.facetDescriptions.getValue("tag")
        assertTrue(described.getValue("au").startsWith("AU - альтернативная реальность"))
        assertTrue(described.getValue("рейтинг за секс").startsWith("Рейтинг за секс - фанфик содержит"))
        assertTrue(described.keys.none { it.endsWith(":") }, "${described.keys}")
        val warnings = described.size
        capture(context, "https://fanfics.me/fandom2/keywords", "fandom-keywords.html")
        // Every event of the fandom's encyclopedia, by its name
        assertEquals(warnings + 5, context.facetDescriptions.getValue("tag").size)
        assertTrue(context.facetDescriptions.getValue("tag").values.none { it.isBlank() })
    }

    @Test
    fun `the addresses of a work's pages name the work, a cover's original comes first`() {
        assertEquals("252474", source.itemIdOf("https://fanfics.me/fic252474"))
        assertEquals("252474", source.itemIdOf("https://fanfics.me/fic252474/comments"))
        assertEquals("252474", source.itemIdOf("https://fanfics.me/read.php?id=252474&chapter=3#start_read"))
        assertEquals("252474", source.itemIdOf("https://fanfics.me/read.php?chapter=3&id=252474"))
        assertNull(source.itemIdOf("https://fanfics.me/fandom2"))
        assertEquals(
            listOf("https://fanfics.me/images/fanart/2026/10/25/1792907630596.jpg", "https://fanfics.me/images/fanart/2026/10/25/1792907630596_200_300.jpg"),
            source.pictureAddresses("https://fanfics.me/images/fanart/2026/10/25/1792907630596_200_300.jpg"),
        )
    }
}
