package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.AuthorAnswer
import io.github.vlsergey.recommend4me.api.model.AuthorKind
import io.github.vlsergey.recommend4me.api.model.AuthorOffer
import io.github.vlsergey.recommend4me.api.model.AuthorSaid
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.api.model.SiteAuthorKey
import io.github.vlsergey.recommend4me.author.AuthorRepository
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.source.FacetValue
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The authors of two sites: merged on the evidence of their works, never of their names. */
// More than a few seconds: in CI alone — the local build's tests take a minute at most together (user, 2026-11-18)
@Tag("ci")
class TwoSitesAuthorsTest : TwoSitesTestBase() {

    private companion object {
        /** A page of the authors holding every author of the application of the tests. */
        const val ALL_AUTHORS = 500
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
        alphaItems.setFacet(idOf("alpha", "w1"), Books.AUTHOR.key, listOf(FacetValue("ann", "Ann")))
        alphaItems.setFacet(idOf("alpha", "w3"), Books.AUTHOR.key, listOf(FacetValue("ann", "Ann")))
        betaItems.setFacet(idOf("beta", "w2"), Books.AUTHOR.key, listOf(FacetValue("anna-b", "Anna B")))
        betaItems.setFacet(idOf("beta", "w4"), Books.AUTHOR.key, listOf(FacetValue("ann", "Ann")))
        val dictionary = stores.type("books")!!.authors
        val ann = madeInBackground { dictionary.bindingsOf("alpha")["ann"] }
        val annaB = madeInBackground { dictionary.bindingsOf("beta")["anna-b"] }
        val namesake = madeInBackground { dictionary.bindingsOf("beta")["ann"] }
        assertTrue(ann != namesake, "one name on two sites is no one author")
        offeredByTheirWorks(ann, annaB)
        toldApartOrPartlyOne(ann, annaB)
        authorsListed(ann, annaB, namesake)
        oneWriter(ann, namesake)
    }

    /** The offers of two authors to be one, of those [said] so. */
    private fun offers(said: AuthorSaid = AuthorSaid.OFFERED) =
        authors.getAuthorOffers("books", said, null, null, null, 0, 50).body!!.offers

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
        // Their works one work: offered once the authors' duplicates are looked for — by the user's pressing, as the
        // works' are —, the pair shown, the rest of the works apart
        corrections.linkItem("alpha", idOf("alpha", "w1"), ItemRef("beta", idOf("beta", "w2")))
        assertEquals(null, of(ann, annaB), "offered by a search alone")
        assertEquals(202, authors.searchAuthorDuplicates("books").statusCode.value())
        background.run()
        val offer = of(ann, annaB)!!
        // The sites of their works asked for of either author, refused of neither — in the database
        fun on(onSite: List<String>?, offSite: List<String>?) =
            authors.getAuthorOffers("books", AuthorSaid.OFFERED, ann, onSite, offSite, 0, 50).body!!.offers.any {
                setOf(it.first.id, it.second.id) == setOf(ann, annaB)
            }
        assertTrue(on(listOf("alpha", "beta"), null), "both sites of either")
        assertTrue(!on(null, listOf("beta")), "a site of neither")
        assertTrue(!on(listOf("gamma"), null), "a site of either")
        assertEquals(1, offer.matching)
        assertEquals(
            listOf(idOf("alpha", "w1") to idOf("beta", "w2")),
            offer.pairs.map { it.first.item to it.second.item },
        )
        assertEquals(listOf(idOf("alpha", "w3")), offer.first.rest.map { it.item })
        // An author is shown with their page on the site, when it has one; a work with its other authors
        assertEquals("https://beta.example/author/anna-b", offer.second.sites.single().url)
        assertEquals(null, offer.first.sites.single().url)
        betaItems.setFacet(
            idOf("beta", "w2"),
            Books.AUTHOR.key,
            listOf(FacetValue("anna-b", "Anna B"), FacetValue("bob", "Bob")),
        )
        val bob = madeInBackground { dictionary.bindingsOf("beta")["bob"] }
        val written = of(ann, annaB)!!.pairs.single().second
        assertEquals(listOf(bob to "Bob"), written.others.map { it.id to it.name })
        assertEquals("https://beta.example/author/bob", written.others.single().url)
        assertEquals(emptyList(), of(ann, annaB)!!.pairs.single().first.others)
        betaItems.setFacet(idOf("beta", "w2"), Books.AUTHOR.key, listOf(FacetValue("anna-b", "Anna B")))
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
            authors.getAuthorOffers("books", AuthorSaid.ALL, annaB, null, null, 0, 50).body!!.offers.single().said,
        )
        authors.answerAuthors(AuthorAnswer(alphaAnn, betaAnna, AuthorAnswer.Verdict.NONE))
    }

    /** Every author with works listed, the most works first, found by a name of theirs. */
    private fun authorsListed(ann: Long, annaB: Long, namesake: Long) {
        // Every author with works listed, the most works first, by a name of theirs — of the other classes' authors
        // too, in the one application of the tests: of these three, Ann of the most works first
        val all = authors.listAuthors("books", AuthorKind.ALL, null, 0, ALL_AUTHORS).body!!.authors.map { it.id }
        val own = all.filter { it in setOf(ann, annaB, namesake) }
        assertEquals(ann, own.first(), "authors $all")
        assertEquals(setOf(annaB, namesake), own.drop(1).toSet(), "authors $all")
        assertEquals(all.first(), authors.listAuthors("books", AuthorKind.ALL, null, 0, 1).body!!.authors.single().id)
        assertEquals(
            listOf(annaB),
            authors.listAuthors("books", AuthorKind.ALL, "NA b", 0, 50).body!!.authors.map { it.id },
        )
        assertEquals(
            setOf(idOf("alpha", "w1"), idOf("alpha", "w3")),
            authors.getAuthor("books", ann).body!!.rest.map { it.item }.toSet(),
        )
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
        assertEquals(true, stores.type("books")!!.authors.distinct.told()[AuthorRepository.pair(ann, namesake)]?.not())
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
        corrections.unlinkItem("alpha", idOf("alpha", "w1"), "beta", idOf("beta", "w2"))
    }
}
