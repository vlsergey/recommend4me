package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.AuthorAnswer
import io.github.vlsergey.recommend4me.api.model.AuthorMergedBy
import io.github.vlsergey.recommend4me.api.model.SiteAuthorKey
import io.github.vlsergey.recommend4me.author.AuthorBinding
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * An author's page listing their page on another site as a place of theirs makes the two one author, marked as merged
 * by it — at once, a work of the other known or not — and never two the user told apart; a merge of the user's is
 * marked as theirs.
 */
class AuthorPlacesTest : TwoSitesTestBase() {
    @Autowired lateinit var binding: AuthorBinding

    @Test
    fun `a place an author's page lists merges the author there, marked by it, never one taken out`() {
        alpha("ap1|Lorem|hay|Lorem ipsum dolor||")
        stores.source("alpha")!!.items.setFacet("ap1", Books.AUTHOR.key, listOf(FacetValue("ap-ann", "Ann")))
        val dictionary = stores.type("books")!!.authors
        val ann = madeInBackground { dictionary.bindingsOf("alpha")["ap-ann"] }
        // Her page lists her page on beta, of no work known yet, and an address of no site
        contexts
            .of("alpha")
            .authorPlaces("ap-ann", listOf("https://beta.example/author/ap-bea", "https://elsewhere.example/ap-ann"))
        // One with Ann at once, of no work known on beta
        assertEquals(ann, dictionary.bindingsOf("beta")["ap-bea"])
        assertEquals(0, authors.getAuthor("books", ann).body!!.sites.single { it.source == "beta" }.works)
        // A work of hers on beta: still one with Ann
        beta("ap2|Ipsum|hay|Sit amet consectetur")
        stores.source("beta")!!.items.setFacet("ap2", Books.AUTHOR.key, listOf(FacetValue("ap-bea", "Bea")))
        background.run()
        assertEquals(1, authors.getAuthor("books", ann).body!!.sites.single { it.source == "beta" }.works)
        assertEquals(ann, dictionary.bindingsOf("beta")["ap-bea"])
        val card = authors.getAuthor("books", ann).body!!
        assertNull(card.sites.single { it.source == "alpha" }.mergedBy)
        val bea = card.sites.single { it.source == "beta" }.mergedBy!!
        assertEquals(AuthorMergedBy.By.PLACE, bea.by)
        assertEquals("alpha" to "Ann", bea.owner!!.source to bea.owner!!.name)

        // Taken out by the user: told apart, the page read again merges them no more
        authors.takeOutSiteAuthor(ann, "beta", "ap-bea")
        contexts.of("alpha").authorPlaces("ap-ann", listOf("https://beta.example/author/ap-bea"))
        assertNotEquals(ann, dictionary.bindingsOf("beta")["ap-bea"])
        assertNull(authors.getAuthor("books", dictionary.bindingsOf("beta")["ap-bea"]!!).body!!.sites.single().mergedBy)

        // Merged by the user: marked as theirs
        authors.answerAuthors(
            AuthorAnswer(SiteAuthorKey("alpha", "ap-ann"), SiteAuthorKey("beta", "ap-bea"), AuthorAnswer.Verdict.NONE)
        )
        authors.answerAuthors(
            AuthorAnswer(SiteAuthorKey("alpha", "ap-ann"), SiteAuthorKey("beta", "ap-bea"), AuthorAnswer.Verdict.SAME)
        )
        val merged = authors.getAuthor("books", dictionary.bindingsOf("alpha")["ap-ann"]!!).body!!
        assertEquals(listOf(null, AuthorMergedBy.By.USER), merged.sites.sortedBy { it.source }.map { it.mergedBy?.by })
    }

    @Test
    fun `an author named twice, as the place of two, is bound once`() {
        // At the start the places of every author are read: one listed by two is named twice, not bound yet — it was
        // bound twice, the start failed on ficbook's (2026-11-16)
        binding.bind(stores.source("beta")!!, listOf("twice", "twice"))
        assertTrue(stores.type("books")!!.authors.bindingsOf("beta").containsKey("twice"))
    }
}
