package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.AuthorMergedBy
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.source.FacetValue
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * An author a site gave by the name alone — a dump, under the prefix the source says of such keys — is one with the
 * author of that name the site's page gives a key of: merged into its normalized author as soon as the page comes,
 * whatever other works the name still has, marked as merged by the name; a name no key has stays.
 */
class NamedAuthorsTest : TwoSitesTestBase() {

    @Test
    fun `the key of a name merged into the key of the same name a page gives`() {
        alpha("na1|Lorem|hay|Lorem ipsum dolor||")
        alpha("na2|Ipsum|hay|Sit amet consectetur||")
        alpha("na3|Dolor|hay|Adipiscing elit||")
        val alpha = stores.source("alpha")!!
        // A dump gave the works their authors by the names alone: Nell of two works, Ned of one
        alpha.items.setFacet("na1", Books.AUTHOR.key, listOf(FacetValue("name:Nell", "Nell")))
        alpha.items.setFacet("na2", Books.AUTHOR.key, listOf(FacetValue("name:Nell", "Nell")))
        alpha.items.setFacet("na3", Books.AUTHOR.key, listOf(FacetValue("name:Ned", "Ned")))
        val dictionary = stores.type("books")!!.authors
        val byName = dictionary.bindingsOf("alpha").getValue("name:Nell")

        // The site's page gives the first work her own key; the second keeps the name
        alpha.items.setFacet("na1", Books.AUTHOR.key, listOf(FacetValue("na-nell", "Nell")))
        val nell = dictionary.bindingsOf("alpha").getValue("na-nell")
        assertNotEquals(byName, nell)
        assertEquals(nell, dictionary.bindingsOf("alpha")["name:Nell"])
        val merged = authors.getAuthor("books", nell).body!!.sites.single { it.author == "name:Nell" }.mergedBy!!
        assertEquals(AuthorMergedBy.By.NAME, merged.by)

        // Ned's name has no key of the site: his stays as it was
        assertNotEquals(nell, dictionary.bindingsOf("alpha")["name:Ned"])
    }
}
