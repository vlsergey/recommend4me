package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.textvector.PhraseVectors

/**
 * THE VECTORS OF THE NAMES OF THE TAGS of [scope] by [encoder], of one making: of every name of a tag, its sites' tags'
 * — those the sources keep of short texts ([phrases]), the ones not kept encoded now — and its entries', every
 * language's, encoded here. Kept nowhere: [TagVectors] keeps what it gives.
 */
class TagNameVectors(
    private val scope: TagScope,
    private val encoder: TextEncoder,
    private val normalization: TagNormalization,
    private val phrases: PhraseVectors,
) {

    /** The vectors of every name of the tags [missing], by tag; a tag of no name has none. */
    fun of(missing: Set<Long>): Map<Long, List<FloatArray>> {
        val ofTag = HashMap<Long, MutableList<FloatArray>>()
        ofSites(missing, ofTag)
        ofEntries(missing, ofTag)
        return ofTag
    }

    /** The names of the sites' tags of every tag [missing] a vector, into [ofTag], one read of each source. */
    private fun ofSites(missing: Set<Long>, ofTag: MutableMap<Long, MutableList<FloatArray>>) {
        val sites = scope.tags.bindings().filterValues { it in missing }
        scope.sources.forEach { s ->
            val siteFacet = normalization.siteFacetOf(s, scope.facet)
            val own = sites.filterKeys { it.source == s.id }
            if (siteFacet == null || own.isEmpty()) return@forEach
            val names = s.names(siteFacet)
            val written = own.keys.associateWith { (names[it.tag] ?: it.tag).trim() }.filterValues { it.isNotEmpty() }
            val vectors = phrases.of(s, written.values.distinct())
            written.forEach { (site, name) ->
                vectors[name]?.let { ofTag.getOrPut(own.getValue(site)) { ArrayList() } += it }
            }
        }
    }

    /**
     * The names of the entries the tags [missing] a vector are bound to, every language's, into [ofTag]: no site's,
     * encoded here.
     */
    private fun ofEntries(missing: Set<Long>, ofTag: MutableMap<Long, MutableList<FloatArray>>) {
        val bound = scope.tags.entries.entriesOfTags().filterKeys { it in missing }
        val kept = scope.tags.entries.keptEntries(bound.values.flatten().toSet())
        val entryNames = bound.mapValues { (_, refs) ->
            refs.flatMap { kept[it]?.let { e -> e.labels.values + e.aliases.values.flatten() }.orEmpty() }.distinct()
        }
        val encoded = encode(entryNames.values.flatten().distinct())
        entryNames.forEach { (tag, names) ->
            names.forEach { name -> encoded[name]?.let { ofTag.getOrPut(tag) { ArrayList() } += it } }
        }
    }

    private fun encode(texts: List<String>): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        texts.chunked(ENCODED_AT_ONCE).forEach { chunk ->
            chunk.zip(encoder.encode(chunk)).forEach { (text, v) -> v?.let { out[text] = it } }
        }
        return out
    }

    private companion object {
        /** Names encoded at once. */
        const val ENCODED_AT_ONCE = 500
    }
}
