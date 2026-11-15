package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.AuthorAnswer
import io.github.vlsergey.recommend4me.api.model.AuthorMergedBy
import io.github.vlsergey.recommend4me.api.model.SiteAuthorKey
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource

/**
 * An author's page listing their page on another site as a place of theirs makes the two one author, marked as merged
 * by it — once the other is known — and never two the user told apart; a merge of the user's is marked as theirs.
 */
@TestPropertySource(properties = ["recommend4me.data-dir=build/test-data/author-places"])
class AuthorPlacesTest : TwoSitesTestBase() {

    @Test
    fun `a place an author's page lists merges the author there, marked by it, never one taken out`() {
        alpha("q1|Lorem|hay|Lorem ipsum dolor||")
        stores.source("alpha")!!.items.setFacet("q1", Books.AUTHOR.key, listOf(FacetValue("ann", "Ann")))
        val dictionary = stores.type("books")!!.authors
        val ann = waitForValue { dictionary.bindingsOf("alpha")["ann"] }
        // Her page lists her page on beta, of no work known yet, and an address of no site
        contexts
            .of("alpha")
            .authorPlaces("ann", listOf("https://beta.example/author/bea", "https://elsewhere.example/ann"))
        assertEquals(1, authors.getAuthor("books", ann).body!!.sites.size)
        // A work of hers on beta: she is known there, and one with Ann
        beta("q2|Ipsum|hay|Sit amet consectetur")
        stores.source("beta")!!.items.setFacet("q2", Books.AUTHOR.key, listOf(FacetValue("bea", "Bea")))
        assertTrue(waitFor { dictionary.bindingsOf("beta")["bea"] == ann })
        val card = authors.getAuthor("books", ann).body!!
        assertNull(card.sites.single { it.source == "alpha" }.mergedBy)
        val bea = card.sites.single { it.source == "beta" }.mergedBy!!
        assertEquals(AuthorMergedBy.By.PLACE, bea.by)
        assertEquals("alpha" to "Ann", bea.owner!!.source to bea.owner!!.name)

        // Taken out by the user: told apart, the page read again merges them no more
        authors.takeOutSiteAuthor(ann, "beta", "bea")
        contexts.of("alpha").authorPlaces("ann", listOf("https://beta.example/author/bea"))
        assertNotEquals(ann, dictionary.bindingsOf("beta")["bea"])
        assertNull(authors.getAuthor("books", dictionary.bindingsOf("beta")["bea"]!!).body!!.sites.single().mergedBy)

        // Merged by the user: marked as theirs
        authors.answerAuthors(
            AuthorAnswer(SiteAuthorKey("alpha", "ann"), SiteAuthorKey("beta", "bea"), AuthorAnswer.Verdict.NONE)
        )
        authors.answerAuthors(
            AuthorAnswer(SiteAuthorKey("alpha", "ann"), SiteAuthorKey("beta", "bea"), AuthorAnswer.Verdict.SAME)
        )
        val merged = authors.getAuthor("books", dictionary.bindingsOf("alpha")["ann"]!!).body!!
        assertEquals(listOf(null, AuthorMergedBy.By.USER), merged.sites.sortedBy { it.source }.map { it.mergedBy?.by })
    }
}
