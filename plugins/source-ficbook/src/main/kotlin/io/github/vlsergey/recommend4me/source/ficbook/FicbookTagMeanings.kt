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
     * A tag's own page: its heading names it — «Метка «Hurt/Comfort» - фанфики и ориджиналы» — the box under it says
     * what it means, and the line under that the other names the site files under it — «Синонимы: Хёрт/комфорт, Х/К,
     * H/C» — each a tag of its own of the same thing. The tag is known by its name as the works write it.
     */
    fun tagPage(doc: Document) {
        val name =
            doc.selectFirst("h1.heading")
                ?.text()
                ?.let { TAG_HEADING.find(it)?.groupValues?.get(1) }
                ?.trim()
                ?.ifEmpty { null } ?: return
        val description = doc.selectFirst(".primary-box p.text-t3")?.let { PageText.of(it) }?.trim()?.ifEmpty { null }
        val synonyms =
            doc.select(".primary-box .text-muted")
                .map { it.text().trim() }
                .firstOrNull { it.startsWith(SYNONYMS) }
                ?.removePrefix(SYNONYMS)
                ?.split(',')
                .orEmpty()
        items.nameFacetValues(TAG, mapOf(name.lowercase() to name))
        description?.let { items.describeFacetValues(TAG, mapOf(name.lowercase() to it)) }
        items.setFacetValueSynonyms(TAG, mapOf(name.lowercase() to synonymsOf(name, synonyms)))
    }

    /**
     * The answer of the site's search of tags the browser made for the application ([Ficbook.describing]), JSON: the
     * tags found, each with what it means and its synonyms. The tag asked — its name in the address — is described by
     * the tag of its name, or by the one it is a synonym of: the site files a synonym under its tag. A tag the site
     * does not describe is left without a description.
     */
    fun tagSearch(page: CapturedPage) {
        val asked = asked(page.url)
        val found = asked?.let { runCatching { JSON.readTree(page.html) }.getOrNull()?.path("data")?.path("tags") }
        fun named(t: JsonNode) = t.path("title").asString("").trim().lowercase()
        val tag =
            found?.firstOrNull { named(it) == asked }
                ?: found?.firstOrNull { t -> t.path("synonyms").any { named(it) == asked } }
        if (asked == null || tag == null) return
        // The tag and the other names the site files under it, each a tag of its own of the same thing
        val name = tag.path("title").asString("").trim()
        if (name.isNotEmpty()) {
            items.nameFacetValues(TAG, mapOf(name.lowercase() to name))
            items.setFacetValueSynonyms(
                TAG,
                mapOf(
                    name.lowercase() to
                        synonymsOf(
                            name,
                            buildList { tag.path("synonyms").forEach { add(it.path("title").asString("")) } },
                        )
                ),
            )
        }
        tag.path("description")
            .asString("")
            .trim()
            .ifEmpty { null }
            ?.let {
                items.describeFacetValues(TAG, mapOf(asked to it))
            }
    }

    private companion object {
        val JSON = JsonMapper.builder().build()

        /** The heading of a tag's page: the name between the outer quotes, whatever quotes it holds itself. */
        val TAG_HEADING = Regex("^Метка «(.*)»\\s*-\\s*фанфики")

        /** What the line of a tag's other names begins with, on its page. */
        const val SYNONYMS = "Синонимы:"

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

        /**
         * The synonyms of the tag [name] by their keys, as the works would write them: none empty, none the tag itself.
         */
        fun synonymsOf(name: String, synonyms: List<String>): Map<String, String> =
            synonyms.map { it.trim() }.filter { it.isNotEmpty() }.associateBy { it.lowercase() } - name.lowercase()
    }
}
