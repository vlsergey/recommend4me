package io.github.vlsergey.recommend4me.source.fanficsme

import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PageText
import io.github.vlsergey.recommend4me.source.fanficsme.FanficsMe.Companion.TAG
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * What the site's tags mean: its genres and warnings by its help, a fandom's events by the fandom's encyclopedia —
 * every tag named and described.
 */
internal class FanficsMeTagMeanings(private val items: ItemStore) {

    /**
     * The site's help on its genres and its warnings: every one a paragraph opening with its name in bold —
     * "<b>Ангст</b> - это сильные переживания…" — the paragraph what the tag of the name means.
     */
    fun help(doc: Document) {
        val table = doc.selectFirst("div.ContentTable:has(> b)") ?: return
        val described =
            table.html().split(PARAGRAPHS).mapNotNull { chunk ->
                val paragraph = Jsoup.parseBodyFragment(chunk).body()
                val name =
                    paragraph
                        .selectFirst("b")
                        ?.let { clean(it.text()) }
                        ?.takeIf { it.isNotEmpty() && !it.endsWith(":") } ?: return@mapNotNull null
                val text =
                    PageText.of(paragraph).ifBlank {
                        return@mapNotNull null
                    }
                name to text
            }
        describe(described)
    }

    /**
     * A fandom's events in its encyclopedia: every one a block with its name — a link to its works — and what it means
     * under it.
     */
    fun keywords(doc: Document) =
        describe(
            doc.select("div.hero_item:has(a[href*=keyword=])").mapNotNull { item ->
                val name =
                    item.selectFirst("a[href*=keyword=]")?.let { clean(it.text()) }?.ifEmpty { null }
                        ?: return@mapNotNull null
                val text =
                    item
                        .select("p.text")
                        .drop(1)
                        .joinToString("\n\n") { PageText.of(it) }
                        .trim()
                        .ifEmpty {
                            return@mapNotNull null
                        }
                name to text
            }
        )

    private fun describe(described: List<Pair<String, String>>) {
        if (described.isEmpty()) return
        items.nameFacetValues(TAG, described.associate { it.first.lowercase() to it.first })
        items.describeFacetValues(TAG, described.associate { it.first.lowercase() to it.second })
    }

    private companion object {
        /** The paragraphs of the site's help: two line breaks between. */
        val PARAGRAPHS = Regex("<br\\s*/?>\\s*<br\\s*/?>")
    }
}
