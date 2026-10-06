package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * Every text of a work its characters are counted in: its texts as the user corrected them — the
 * description, the notes, the site's line of its characters — the names of the values the site
 * gives it (a tag "гарри поттер" names Harry), a line each, and every chapter read.
 */
fun SourceStore.workTexts(itemId: String): Sequence<String> {
    val chapters = ArrayList<String>()
    parts.forEachContent(itemId) { chapters += it }
    val values = items.facets(itemId).flatMap { (facet, keys) -> items.facetNames(facet, keys).values }
    return Corrected.texts(items.texts(itemId), corrections.fieldsOf(itemId)).values.asSequence() +
        values.asSequence() + chapters.asSequence()
}
