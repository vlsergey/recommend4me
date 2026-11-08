package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.AuthorAnswer
import io.github.vlsergey.recommend4me.api.model.AuthorKind
import io.github.vlsergey.recommend4me.api.model.AuthorOffer
import io.github.vlsergey.recommend4me.api.model.AuthorSaid
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.ItemQuery
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.api.model.ItemSort
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.SiteAuthorKey
import io.github.vlsergey.recommend4me.api.model.SizeNumber
import io.github.vlsergey.recommend4me.author.AuthorRepository
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.workload.inBackground
import java.time.Instant
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.i18n.LocaleContextHolder

/**
 * Two sites of one content type: what spans sources — a work on both sites and its authors, what each site says of it,
 * the duplicates, a search.
 */
class TwoSitesTest : TwoSitesTestBase() {

    @Test
    fun `a number the site states shown as it is, the other worked out by the words per character the texts teach`() {
        alpha("z1|The Long Road|river|A road along a river||")
        beta("z2|The Short Road|river|A road along a brook")
        val type = stores.type("books")!!
        val a = stores.source("alpha")!!
        val b = stores.source("beta")!!
        a.items.setNumbers("z1", mapOf("words" to 900.0))
        b.items.setNumbers("z2", mapOf("chars" to 6000.0))
        // Words of five letters and a space: one word to six characters
        a.parts.save("z1", listOf(PartData("1", 0, null, "слово ".repeat(1000))))
        workSizes.fit(type)
        assertEquals(1000, a.parts.counted().getValue("z1").first.toInt())
        val first = workSizes.ofItems(type, a, listOf("z1"), a.items.numbersOf(listOf("z1")), emptyMap()).getValue("z1")
        assertEquals(SizeNumber(900.0, true), first.words)
        assertEquals(false, first.chars!!.stated)
        assertTrue(first.chars!!.value in 5300.0..5500.0, first.chars.toString())
        val second =
            workSizes.ofItems(type, b, listOf("z2"), b.items.numbersOf(listOf("z2")), emptyMap()).getValue("z2")
        assertEquals(SizeNumber(6000.0, true), second.chars)
        assertEquals(false, second.words!!.stated)
        // Of the one example the model has, its ratio
        assertTrue(second.words!!.value in 950.0..1050.0, second.words.toString())
        // A text changed is counted anew
        a.parts.save("z1", listOf(PartData("1", 0, null, "слово")))
        assertNull(a.parts.counted()["z1"])
    }

    @Test
    fun `the cover of a work is its own item's, else another site's`() {
        alpha("c1|The Cover|sea|A work with a cover on one site|cover.png|")
        beta("c2|The Cover|sea|A work with a cover on one site")
        assertNull(items.getItem("beta", "c2").body!!.summary.cover)
        corrections.linkItem("alpha", "c1", ItemRef("beta", "c2"))
        assertEquals(ItemRef("alpha", "c1"), items.getItem("alpha", "c1").body!!.summary.cover)
        assertEquals(ItemRef("alpha", "c1"), items.getItem("beta", "c2").body!!.summary.cover)
        assertEquals(
            0,
            items.getItem("beta", "c2").body!!.summary.pictureCount,
            "the pictures of the site's page stay its own",
        )
        corrections.unlinkItem("alpha", "c1", "beta", "c2")
    }

    @Test
    fun `one work on two sites is one work — a grade or a mark of either is of both`() {
        alpha("a1|The Lighthouse|sea|A keeper of a lighthouse waits for the storm||")
        beta("b9|The Lighthouse|ocean|A keeper of a lighthouse waits for the storm")
        corrections.linkItem("alpha", "a1", ItemRef("beta", "b9"))
        gradesController.rateItem("alpha", "a1", RatingRequest(4))
        assertEquals(4, items.getItem("beta", "b9").body!!.summary.grade)
        // The link taken back: each keeps its grade, and is its own after
        corrections.unlinkItem("alpha", "a1", "beta", "b9")
        gradesController.rateItem("beta", "b9", RatingRequest(2))
        assertEquals(4, items.getItem("alpha", "a1").body!!.summary.grade)
        gradesController.unrateItem("alpha", "a1", null)
        gradesController.unrateItem("beta", "b9", null)
    }

    @Test
    fun `the form of a work is one through either of its items — what every site says stands above the field`() {
        alpha("f1|The Harbour|harbour-one|A harbour at dusk||")
        beta("f2|The Harbour|harbour-two|A harbour at dusk")
        corrections.linkItem("alpha", "f1", ItemRef("beta", "f2"))
        fun field(source: String, item: String) =
            items.getItem(source, item).body!!.allFacets.single { it.facet == Books.TAGS.key }
        val one = field("alpha", "f1")
        val other = field("beta", "f2")
        assertEquals(listOf("harbour-one"), one.said.orEmpty().single { it.source == "alpha" }.words.map { it.name })
        assertEquals(listOf("harbour-two"), one.said.orEmpty().single { it.source == "beta" }.words.map { it.name })
        assertEquals(one.said, other.said)
        assertEquals(
            one.propertyValues.map { it.key to it.fromSite }.toSet(),
            other.propertyValues.map { it.key to it.fromSite }.toSet(),
        )
        corrections.unlinkItem("alpha", "f1", "beta", "f2")
    }

    @Test
    fun `a word on a pair of duplicates is carried across one work — said of one of two works one, of the other`() {
        alpha("m1|The Mill|river|A miller and his river||")
        beta("m2|The Mill|river|A miller and his river")
        beta("m3|The Mill, translated|river|A miller and his river, in another tongue")
        alpha("m4|The Mill Again|river|The miller's son||")
        val links = stores.type("books")!!.links
        fun key(source: String, id: String) = ItemKey(source, id)
        fun answer(a: ItemKey, b: ItemKey, verdict: io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict) =
            duplicates.answerDuplicate(
                "books",
                io.github.vlsergey.recommend4me.api.model.DuplicateAnswer(
                    ItemRef(a.source, a.id),
                    ItemRef(b.source, b.id),
                    verdict,
                ),
            )
        fun relation(a: ItemKey, b: ItemKey) = links.relations()[links.ordered(a, b)]
        fun different(a: ItemKey, b: ItemKey) = links.ordered(a, b) in links.distincts()
        val m1 = key("alpha", "m1")
        val m2 = key("beta", "m2")
        val m3 = key("beta", "m3")
        val m4 = key("alpha", "m4")

        answer(m1, m2, io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.SAME)
        // Of one of two works one, a translation: of the other too
        answer(m1, m3, io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.TRANSLATION)
        assertEquals("TRANSLATION", relation(m2, m3))
        // The window of the work tells it, of whichever item, and what each site says
        val window = items.getItem("beta", "m2").body!!
        assertEquals(listOf("beta/m3"), window.relations.map { "${it.source}/${it.item}" })
        assertEquals(setOf("alpha/m1", "beta/m2"), window.sites.map { "${it.source}/${it.item}" }.toSet())
        assertEquals(listOf("alpha/m4"), items.getItem("alpha", "m4").body!!.sites.map { "${it.source}/${it.item}" })
        assertEquals(404, duplicates.getItemDuplicates("alpha", "nothing").statusCode.value())
        // Taken back of one pair: of every pair of the two works' groups
        duplicates.unanswerDuplicate("books", "beta", "m2", "beta", "m3")
        assertEquals(null, relation(m1, m3))
        assertEquals(null, relation(m2, m3))

        // A word said before the works were one: carried to the work that joins them
        answer(m4, m3, io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.DIFFERENT)
        answer(m4, m1, io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.DIFFERENT)
        assertTrue(different(m4, m2), "m4 is different from m1, so from m2, one with it")
        answer(m3, m2, io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.SAME)
        // m3 joins m1 and m2: m4, different from them, different from m3 as it was; a word of the group's own two taken
        // away
        assertTrue(different(m4, m3))
        assertEquals(null, relation(m1, m3))

        for ((a, b) in listOf(m1 to m2, m2 to m3, m4 to m1, m4 to m3)) duplicates.unanswerDuplicate(
            "books",
            a.source,
            a.id,
            b.source,
            b.id,
        )
    }

    @Test
    fun `every word on a pair is in the journal, a word undone gives the pair the word before`() {
        alpha("j1|The Well|water|A well of water||")
        beta("j2|The Well|water|A well of water")
        val links = stores.type("books")!!.links
        val (j1, j2) = ItemKey("alpha", "j1") to ItemKey("beta", "j2")
        fun answer(verdict: io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict) =
            duplicates.answerDuplicate(
                "books",
                io.github.vlsergey.recommend4me.api.model.DuplicateAnswer(
                    ItemRef(j1.source, j1.id),
                    ItemRef(j2.source, j2.id),
                    verdict,
                ),
            )
        fun journal() =
            duplicates.getDuplicateJournal("books", 0, 200).body!!.words.filter {
                it.first.item == "j1" && it.second.item == "j2"
            }
        fun word() = links.word(j1, j2, Instant.now())

        answer(io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.SAME)
        answer(io.github.vlsergey.recommend4me.api.model.DuplicateAnswer.Verdict.DIFFERENT)
        assertEquals(
            listOf("DIFFERENT" to "SAME", "SAME" to null),
            journal().map { it.answer?.name to it.before?.name },
        )
        assertEquals(listOf(true, false), journal().map { it.undoable })
        assertEquals("The Well", journal().first().first.title)

        // Undone, the pair is one work again — a word of the journal itself; an older word is not undone
        val older = journal().last().id
        duplicates.undoDuplicateWord("books", journal().first().id)
        assertEquals("SAME", word())
        assertEquals("SAME" to "DIFFERENT", journal().first().let { it.answer?.name to it.before?.name })
        assertFailsWith<Refused> { duplicates.undoDuplicateWord("books", older) }
        // Taken back and undone: the word before stands again
        duplicates.unanswerDuplicate("books", j1.source, j1.id, j2.source, j2.id)
        assertEquals(null, word())
        duplicates.undoDuplicateWord("books", journal().first().id)
        assertEquals("SAME", word())
        duplicates.unanswerDuplicate("books", j1.source, j1.id, j2.source, j2.id)
    }

    @Test
    fun `authors are merged on the evidence of their works, never of their names`() {
        alpha("w1|The Barn|hay|A barn in the hay||")
        beta("w2|The Barn|hay|A barn in the hay")
        alpha("w3|The Field|hay|A field of hay||")
        beta("w4|The Pond|hay|A pond by the hay")
        val alphaItems = stores.source("alpha")!!.items
        val betaItems = stores.source("beta")!!.items
        // One name on two sites, and one writer under two names of a third work
        alphaItems.setFacet("w1", Books.AUTHOR.key, listOf(FacetValue("ann", "Ann")))
        alphaItems.setFacet("w3", Books.AUTHOR.key, listOf(FacetValue("ann", "Ann")))
        betaItems.setFacet("w2", Books.AUTHOR.key, listOf(FacetValue("anna-b", "Anna B")))
        betaItems.setFacet("w4", Books.AUTHOR.key, listOf(FacetValue("ann", "Ann")))
        val dictionary = stores.type("books")!!.authors
        val ann = waitForValue { dictionary.bindingsOf("alpha")["ann"] }
        val annaB = waitForValue { dictionary.bindingsOf("beta")["anna-b"] }
        val namesake = waitForValue { dictionary.bindingsOf("beta")["ann"] }
        assertTrue(ann != namesake, "one name on two sites is no one author")
        offeredByTheirWorks(ann, annaB)
        toldApartOrPartlyOne(ann, annaB)
        authorsListed(ann, annaB, namesake)
        oneWriter(ann, namesake)
    }

    /** The offers of two authors to be one, of those [said] so. */
    private fun offers(said: AuthorSaid = AuthorSaid.OFFERED) =
        authors.getAuthorOffers("books", said, null, 0, 50).body!!.offers

    /** The offer of the authors [a] and [b] to be one; none when they are not offered. */
    private fun of(a: Long, b: Long) = offers().firstOrNull { setOf(it.first.id, it.second.id) == setOf(a, b) }

    /** An answer names the authors of the sites: Ann of alpha, Anna B of beta, and Ann of beta, her namesake. */
    private val alphaAnn = SiteAuthorKey("alpha", "ann")
    private val betaAnna = SiteAuthorKey("beta", "anna-b")
    private val betaAnn = SiteAuthorKey("beta", "ann")

    /**
     * Two authors offered to be one once their works are one: the pair shown, the rest of the works apart, each author
     * with their page on the site, a work with its other authors.
     */
    private fun offeredByTheirWorks(ann: Long, annaB: Long) {
        val betaItems = stores.source("beta")!!.items
        val dictionary = stores.type("books")!!.authors
        assertEquals(null, of(ann, annaB), "no work of theirs is known to match")
        // Their works one work: offered, the pair shown, the rest of the works apart
        corrections.linkItem("alpha", "w1", ItemRef("beta", "w2"))
        val offer = of(ann, annaB)!!
        assertEquals(1, offer.matching)
        assertEquals(listOf("w1" to "w2"), offer.pairs.map { it.first.item to it.second.item })
        assertEquals(listOf("w3"), offer.first.rest.map { it.item })
        // An author is shown with their page on the site, when it has one; a work with its other authors
        assertEquals("https://beta.example/author/anna-b", offer.second.sites.single().url)
        assertEquals(null, offer.first.sites.single().url)
        betaItems.setFacet("w2", Books.AUTHOR.key, listOf(FacetValue("anna-b", "Anna B"), FacetValue("bob", "Bob")))
        val bob = waitForValue { dictionary.bindingsOf("beta")["bob"] }
        val written = of(ann, annaB)!!.pairs.single().second
        assertEquals(listOf(bob to "Bob"), written.others.map { it.id to it.name })
        assertEquals("https://beta.example/author/bob", written.others.single().url)
        assertEquals(emptyList(), of(ann, annaB)!!.pairs.single().first.others)
        betaItems.setFacet("w2", Books.AUTHOR.key, listOf(FacetValue("anna-b", "Anna B")))
    }

    /** Two authors told apart, or partly one: offered no more, till the word is taken back. */
    private fun toldApartOrPartlyOne(ann: Long, annaB: Long) {
        // Told apart: offered no more, till the word is taken back
        authors.answerAuthors(AuthorAnswer(alphaAnn, betaAnna, AuthorAnswer.Verdict.DIFFERENT))
        assertEquals(null, of(ann, annaB))
        assertEquals(listOf(ann to annaB), offers(AuthorSaid.DIFFERENT).map { it.first.id to it.second.id })
        authors.answerAuthors(AuthorAnswer(alphaAnn, betaAnna, AuthorAnswer.Verdict.NONE))

        // Partly one: offered no more, kept apart from the different; of the author's own pairs with the word
        authors.answerAuthors(AuthorAnswer(alphaAnn, betaAnna, AuthorAnswer.Verdict.PARTIAL))
        assertEquals(null, of(ann, annaB))
        assertEquals(emptyList(), offers(AuthorSaid.DIFFERENT))
        assertEquals(listOf(ann to annaB), offers(AuthorSaid.PARTIAL).map { it.first.id to it.second.id })
        assertEquals(
            AuthorOffer.Said.PARTIAL,
            authors.getAuthorOffers("books", AuthorSaid.ALL, annaB, 0, 50).body!!.offers.single().said,
        )
        authors.answerAuthors(AuthorAnswer(alphaAnn, betaAnna, AuthorAnswer.Verdict.NONE))
    }

    /** Every author with works listed, the most works first, found by a name of theirs. */
    private fun authorsListed(ann: Long, annaB: Long, namesake: Long) {
        // Every author with works listed, the most works first, by a name of theirs
        assertEquals(
            listOf(ann, annaB, namesake),
            authors
                .listAuthors("books", AuthorKind.ALL, null, 0, 50)
                .body!!
                .authors
                .map { it.id }
                .sortedWith(compareBy { if (it == ann) 0L else it }),
        )
        assertEquals(ann, authors.listAuthors("books", AuthorKind.ALL, null, 0, 1).body!!.authors.single().id)
        assertEquals(
            listOf(annaB),
            authors.listAuthors("books", AuthorKind.ALL, "NA b", 0, 50).body!!.authors.map { it.id },
        )
        assertEquals(setOf("w1", "w3"), authors.getAuthor("books", ann).body!!.rest.map { it.item }.toSet())
    }

    /**
     * One writer: merged, offered no more; a word of two who are one by now needs nothing or is refused; a site's
     * author taken out is told apart.
     */
    private fun oneWriter(ann: Long, namesake: Long) {
        val dictionary = stores.type("books")!!.authors
        // One writer: merged, offered no more; a site's author taken out is told apart
        authors.answerAuthors(AuthorAnswer(alphaAnn, betaAnna, AuthorAnswer.Verdict.SAME))
        assertEquals(ann, dictionary.bindingsOf("beta")["anna-b"])
        assertEquals(null, offers().firstOrNull { it.first.id == ann && it.second.id == ann })
        // A row read before the merge stays good: the author merged away answered on by their site's name
        assertEquals(
            204,
            authors.answerAuthors(AuthorAnswer(betaAnna, betaAnn, AuthorAnswer.Verdict.DIFFERENT)).statusCode.value(),
        )
        assertEquals(true, stores.type("books")!!.authors.told()[AuthorRepository.pair(ann, namesake)]?.not())
        authors.answerAuthors(AuthorAnswer(betaAnna, betaAnn, AuthorAnswer.Verdict.NONE))
        // Of two who are one by now: one writer needs nothing, different is refused
        assertEquals(
            204,
            authors.answerAuthors(AuthorAnswer(alphaAnn, betaAnna, AuthorAnswer.Verdict.SAME)).statusCode.value(),
        )
        assertEquals(
            Refusal.AUTHORS_ONE,
            assertThrows<Refused> {
                    authors.answerAuthors(AuthorAnswer(alphaAnn, betaAnna, AuthorAnswer.Verdict.DIFFERENT))
                }
                .refusal,
        )
        assertEquals(
            404,
            authors
                .answerAuthors(AuthorAnswer(alphaAnn, SiteAuthorKey("beta", "nobody"), AuthorAnswer.Verdict.DIFFERENT))
                .statusCode
                .value(),
        )
        assertEquals(
            setOf("ann", "anna-b"),
            authors
                .listAuthors("books", AuthorKind.MERGED, null, 0, 50)
                .body!!
                .authors
                .single { it.id == ann }
                .sites
                .map { it.author }
                .toSet(),
        )
        authors.takeOutSiteAuthor(ann, "beta", "anna-b")
        val apart = dictionary.bindingsOf("beta").getValue("anna-b")
        assertTrue(apart != ann)
        assertEquals(null, of(ann, apart))
        authors.answerAuthors(AuthorAnswer(alphaAnn, betaAnna, AuthorAnswer.Verdict.NONE))
        corrections.unlinkItem("alpha", "w1", "beta", "w2")
    }

    @Test
    fun `one work written at once by several writers — page sent, page downloaded — whole by each, no key broken`() {
        val context = contexts.of("alpha")
        val threads = 8
        repeat(30) { round ->
            val id = "race$round"
            val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
            val start = java.util.concurrent.CountDownLatch(1)
            val done =
                (1..threads).map { k ->
                    pool.submit<Unit> {
                        start.await()
                        context.items.upsert(
                            ItemHead(id, "https://alpha.example/work/$id", "Race $k", version = "v$k"),
                            Instant.now(),
                        )
                        context.items.setFacet(id, Books.TAG.key, listOf(FacetValue("tag$k", "Tag $k")))
                        context.items.setTexts(id, mapOf("annotation" to "Written by $k"))
                        context.items.setNumbers(id, mapOf("likes" to k.toDouble()))
                    }
                }
            start.countDown()
            done.forEach { it.get() }
            pool.shutdown()
            val head = assertNotNull(context.items.find(id))
            assertTrue(head.title.startsWith("Race "))
            assertEquals(1, context.items.facets(id)[Books.TAG.key]?.size, "one writer's tags whole")
        }
    }

    @Test
    fun `a tag the user gave a work finds it at once`() {
        // The line read as the interface's language, English: the works are of English
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        alpha("q3|Deep water|water|Waves over the reef||")
        // Indexed as the site wrote it first
        assertTrue(
            waitFor(60) { items.listItems("books", ItemQuery(search = "water")).body!!.items.any { it.item == "q3" } }
        )
        corrections.correctFacet("alpha", "q3", FacetCorrection(facet = "tags", added = true, name = "Mermaids"))
        assertTrue(
            waitFor(60) {
                items
                    .listItems("books", ItemQuery(search = "mermaids", sort = ItemSort.SCORE))
                    .body!!
                    .items
                    .firstOrNull()
                    ?.item == "q3"
            },
            "document ${
                searchDocuments
                    .documents(stores.source("alpha")!!, listOf("q3"), passages = false)
                    .values
                    .single()
                    .fields
                    .map { it.key to it.values }
            }",
        )
    }

    @Test
    fun `a work's fingerprint read of every work is its document's, made a few works at a time`() {
        alpha("f1|Prints|print|A keeper of prints||")
        alpha("f2|Other prints|print|Prints of another kind||")
        val type = stores.type("books")!!
        // Of the works as they are now: what the background changes meanwhile is read again
        var told = ""
        val alike =
            waitFor(60) {
                inBackground {
                    val read = searchDocuments.printsOf(type)
                    val keys = read.prints.keys.filter { it.source == "alpha" }
                    // Each document made apart, as an update makes a few: what one reads of the others is not in it
                    val made = keys.associateWith { k ->
                        searchDocuments.documentsOf(type, listOf(k), read.chances).single().fingerprint
                    }
                    told = "read ${keys.associateWith { read.prints[it] }}, made $made"
                    keys.isNotEmpty() && made == keys.associateWith { read.prints[it] }
                }
            }
        assertTrue(alike, told)
    }

    @Test
    fun `a search finds the works by their words, the likest first`() {
        alpha("q1|Night watch|watch|A lighthouse keeper watches the night sea||")
        alpha("q2|Gardens|garden|Roses and tulips in a spring garden||")
        val found =
            waitFor(60) {
                items
                    .listItems("books", ItemQuery(search = "lighthouse keeper", sort = ItemSort.SCORE))
                    .body!!
                    .items
                    .firstOrNull()
                    ?.item == "q1"
            }
        assertTrue(
            found,
            "found ${items.listItems("books", ItemQuery(search = "lighthouse keeper")).body!!.items.map { it.item }}",
        )
    }
}
