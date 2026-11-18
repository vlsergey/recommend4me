package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.AuthorMergedBy
import io.github.vlsergey.recommend4me.author.UnusedAuthors
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A site's author no work of the site has any more, whose name another key of the site with works has — the key a dump
 * gave by the name alone, the site's page giving the work the author's own key since — is merged into the other's
 * normalized author, marked as merged by the name; a key of no works whose name no other key has stays.
 */
class UnusedAuthorsTest : TwoSitesTestBase() {
    @Autowired lateinit var unused: UnusedAuthors

    @Test
    fun `the key of a name no work has any more merged into the key of the same name that works have`() {
        alpha("ua1|Lorem|hay|Lorem ipsum dolor||")
        alpha("ua2|Ipsum|hay|Sit amet consectetur||")
        val alpha = stores.source("alpha")!!
        // A dump gave the works their authors by the names alone
        alpha.items.setFacet("ua1", Books.AUTHOR.key, listOf(FacetValue("name:Ulla", "Ulla")))
        alpha.items.setFacet("ua2", Books.AUTHOR.key, listOf(FacetValue("name:Ugo", "Ugo")))
        val dictionary = stores.type("books")!!.authors
        val byName = waitForValue { dictionary.bindingsOf("alpha")["name:Ulla"] }
        waitForValue { dictionary.bindingsOf("alpha")["name:Ugo"] }
        // The site's page gives the first work her own key; nothing gives Ugo another
        alpha.items.setFacet("ua1", Books.AUTHOR.key, listOf(FacetValue("ua-ann", "Ulla")))
        alpha.items.setFacet("ua2", Books.AUTHOR.key, emptyList())
        val ann = waitForValue { dictionary.bindingsOf("alpha")["ua-ann"] }
        assertNotEquals(byName, ann)

        assertEquals(1, unused.merge(alpha))
        assertEquals(ann, dictionary.bindingsOf("alpha")["name:Ulla"])
        val merged = authors.getAuthor("books", ann).body!!.sites.single { it.author == "name:Ulla" }.mergedBy!!
        assertEquals(AuthorMergedBy.By.NAME, merged.by)
        // Ugo's name has no other key: his stays as it was
        assertNotEquals(ann, dictionary.bindingsOf("alpha")["name:Ugo"])
        assertEquals(0, unused.merge(alpha))
    }
}
