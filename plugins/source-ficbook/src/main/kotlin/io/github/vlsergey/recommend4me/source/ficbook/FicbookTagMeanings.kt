package io.github.vlsergey.recommend4me.source.ficbook

import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.ficbook.Ficbook.Companion.TAG
import java.net.URI
import java.net.URLDecoder
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What the site's tags mean and the other names it files under them: of a tag's own page, and of the answer of the
 * site's search of tags.
 */
internal class FicbookTagMeanings(private val items: ItemStore) {

    /**
     * A tag's own page, "/tags/1682" — the tag [id]: its heading names it — «Метка «Hurt/Comfort» - фанфики и
     * ориджиналы» — and the box under it says what it means. The other names the line under that gives are written with
     * no ids, the site's search of tags giving them ([tagSearch]).
     */
    fun tagPage(doc: Document, id: String) {
        val name =
            doc.selectFirst("h1.heading")
                ?.text()
                ?.let { TAG_HEADING.find(it)?.groupValues?.get(1) }
                ?.trim()
                ?.ifEmpty { null } ?: return
        items.nameFacetValues(TAG, mapOf(id to name))
        doc.selectFirst(".primary-box p.text-t3")
            ?.let { PageText.of(it) }
            ?.trim()
            ?.ifEmpty { null }
            ?.let { items.describeFacetValues(TAG, mapOf(id to it)) }
    }

    /**
     * The answer of the site's search of tags the browser made for the application ([Ficbook.describing]), JSON: the
     * tags found, each with its id, what it means and its synonyms with their ids. The tag asked — its name in the
     * address — is the tag of its name, or the one it is a synonym of: the site files a synonym under its tag. Every
     * one by its id; a tag the site does not describe is left without a description.
     */
    fun tagSearch(page: CapturedPage) {
        val asked = asked(page.url)
        val found = asked?.let { runCatching { JSON.readTree(page.html) }.getOrNull()?.path("data")?.path("tags") }
        fun named(t: JsonNode) = t.path("title").asString("").trim().lowercase()
        val tag =
            found?.firstOrNull { named(it) == asked }
                ?: found?.firstOrNull { t -> t.path("synonyms").any { named(it) == asked } }
        val id = tag?.let(::idOf) ?: return
        // The tag and the other names the site files under it, each a tag of its own of the same thing
        val synonyms =
            tag.path("synonyms").mapNotNull { s -> idOf(s)?.let { it to s.path("title").asString("").trim() } }
        items.nameFacetValues(TAG, (synonyms + (id to tag.path("title").asString("").trim())).toMap())
        items.setFacetValueSynonyms(
            TAG,
            mapOf(id to synonyms.filter { it.second.isNotEmpty() && it.first != id }.toMap()),
        )
        tag.path("description")
            .asString("")
            .trim()
            .ifEmpty { null }
            ?.let { items.describeFacetValues(TAG, mapOf(id to it)) }
    }

    private companion object {
        val JSON = JsonMapper.builder().build()

        /** The heading of a tag's page: the name between the outer quotes, whatever quotes it holds itself. */
        val TAG_HEADING = Regex("^Метка «(.*)»\\s*-\\s*фанфики")

        /** The name of the tag the search at [url] asked for — its `title` — in lower case; null when it asked none. */
        fun asked(url: String): String? = runCatching {
            URI(url).rawQuery
        }
            .getOrNull()
            .orEmpty()
            .split('&')
            .firstNotNullOfOrNull { p ->
                p.split('=', limit = 2)
                    .takeIf { it.size == 2 && it[0] == "title" }
                    ?.let { URLDecoder.decode(it[1], Charsets.UTF_8) }
            }
            ?.trim()
            ?.lowercase()
            ?.ifEmpty { null }

        /** The id of a tag of the answer, as the key of a tag; null of one without. */
        fun idOf(tag: JsonNode): String? = tag.path("id").asLong(0L).takeIf { it > 0 }?.toString()
    }
}
