package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.TwoSitesTestBase
import io.github.vlsergey.recommend4me.api.model.ItemPageVersion
import io.github.vlsergey.recommend4me.api.model.ItemQuery
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.json.JsonMapper

/** The first page of a search, answered in versions as the search makes them (`listItemVersions`). */
class SearchVersionsTest : TwoSitesTestBase() {
    @Autowired lateinit var json: JsonMapper

    @Test
    fun `a search's page comes in versions, every one but the last telling what the search still does`() {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        // Words no other scenario's works say
        alpha("v1|Lanterns|lantern|A lamplighter mends the lanterns of the harbour||")
        alpha("v2|Harbour|harbour|Boats wait in the harbour for the tide||")
        background.run()
        val query = ItemQuery(search = "lamplighter lanterns")
        val response = MockHttpServletResponse()
        items.listItemVersions("books", query, response)
        assertEquals(MediaType.APPLICATION_NDJSON_VALUE, response.contentType?.substringBefore(';'))
        val versions =
            response.contentAsString
                .lines()
                .filter { it.isNotBlank() }
                .map { json.readValue(it, ItemPageVersion::class.java) }
        assertTrue(versions.size >= 2, "versions ${response.contentAsString}")
        // Every version but the last says the step at work after it; the last is the page itself, as asked whole
        versions.dropLast(1).forEach { assertTrue(it.refining != null, "a version before the last without a step") }
        assertNull(versions.last().refining)
        assertEquals(
            items.listItems("books", query).body!!.items.map { it.item },
            versions.last().items.map { it.item },
        )
        assertTrue(
            versions.first().items.any { it.item == idOf("alpha", "v1") },
            "first ${versions.first().items.map { it.item }}",
        )
    }
}
