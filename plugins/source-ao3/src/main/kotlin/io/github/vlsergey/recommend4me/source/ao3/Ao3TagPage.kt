package io.github.vlsergey.recommend4me.source.ao3

import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.FANDOM
import io.github.vlsergey.recommend4me.source.ao3.Ao3.Companion.TAG
import org.jsoup.nodes.Document

/**
 * THE PAGE OF A TAG, "/tags/<name>" (owner, 2026-11-23), of a fandom, of the additional tags and of the warnings — the
 * site's tags of a work —: its heading names it as the works do. A canonical tag lists the tags with the same meaning,
 * the synonyms its wranglers merged into it — its three hundred most used —, the metatags right above it and the
 * subtags right under it; a synonym's page names the tag it was merged into. Each is the site's word on the values of
 * the facet of the tag's category: synonyms added to those the tag had, the tags above it all of them, the subtags each
 * under it besides what else. The page of a fandom tells nothing of what it is of: it is described blank, read. The
 * page of another category — a character, a relationship —, or one without the heading, changes nothing.
 */
internal class Ao3TagPage(private val items: ItemStore) {

    fun read(doc: Document) {
        val name =
            doc.selectFirst("div.tag.home .primary.header h2.heading")?.text()?.trim()?.ifEmpty { null } ?: return
        val category =
            doc.select("div.tag.home > p").firstNotNullOfOrNull { CATEGORY.find(it.text())?.groupValues?.get(1) }
        val facet = FACETS[category] ?: return
        // A fandom is keyed by its name, a tag by its name in lower case, as the works give them
        fun key(written: String) = if (facet == TAG) written.lowercase() else written
        fun names(query: String) = doc.select(query).map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val tag = key(name)
        items.describeFacetValues(facet, mapOf(tag to ""))
        val synonyms = names("div.synonym > ul > li > a.tag").filter { key(it) != tag }
        if (synonyms.isNotEmpty())
            items.setFacetValueSynonyms(facet, mapOf(tag to synonyms.associateBy(::key)), whole = false)
        names("div.merger p a.tag").firstOrNull()?.let { merger ->
            items.setFacetValueSynonyms(facet, mapOf(key(merger) to mapOf(tag to name)), whole = false)
            items.nameFacetValues(facet, mapOf(key(merger) to merger))
        }
        // Right above and right under it alone: those further are nested in the trees, of the tags between
        val metatags = names("div.meta > ul > li > a.tag")
        val subtags = names("div.sub > ul > li > a.tag")
        items.nameFacetValues(facet, (metatags + subtags).associateBy(::key))
        items.setFacetValueParents(facet, mapOf(tag to metatags.map(::key)))
        if (subtags.isNotEmpty())
            items.setFacetValueParents(facet, subtags.associate { key(it) to listOf(tag) }, whole = false)
    }

    private companion object {
        /** The line of every tag's page: "This tag belongs to the Fandom Category." */
        val CATEGORY = Regex("This tag belongs to the (.+?) Category\\.")

        /** The facet of every category of the site's tags of a work; the characters and relationships none. */
        val FACETS = mapOf("Fandom" to FANDOM, "Additional Tags" to TAG, "Archive Warning" to TAG)
    }
}
