package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * Everything searchable of an item: its fields of text — the title, the facets' names, the texts —
 * each with the weight of a match in it, and the vectors of its texts for the search by meaning.
 */
class SearchDocument(
    val key: ItemKey,
    val fields: List<SearchField>,
    val vectors: List<SearchVector>,
)

/**
 * A field of a document. [list]: several short values (tags), shown joined by commas; otherwise
 * one or more texts. [name] is what the interface shows of a match in it.
 */
class SearchField(
    val key: String,
    val name: String,
    val weight: Float,
    val values: List<String>,
    val list: Boolean = false,
    /** The title, the author: the whole query standing there is what was looked for. */
    val naming: Boolean = false,
)

/** The vector of the text [field] (a [SearchField.key]) by the text encoder. */
class SearchVector(val field: String, val vector: FloatArray)

/** What a search found: the items, most relevant first, and what the provider needs to explain them. */
class SearchResult(val line: String, val keys: List<ItemKey>, val state: Any?)

/** The piece of an item's text a search found it by, with the words found marked ([highlights], start until end). */
class SearchMatch(val field: String, val byMeaning: Boolean, val text: String, val highlights: List<Pair<Int, Int>>)

/** What the application gives a search provider. */
interface SearchContext {
    /** The vector of a search line as a query, by the text encoder; null without one. */
    fun queryVector(line: String): FloatArray?

    /** The vectors of short texts as passages, kept once made. */
    fun phraseVectors(texts: Collection<String>): Map<String, FloatArray>

    /** The documents of the items, read now. */
    fun documents(keys: Collection<ItemKey>): Map<ItemKey, SearchDocument>
}

/**
 * A search over the items of a content type. The index is the provider's: made from every
 * document on start ([rebuild], in the background), kept in step by [update].
 */
interface SearchProvider {
    val id: String

    fun rebuild(contentType: String, documents: Sequence<SearchDocument>)

    fun update(contentType: String, documents: List<SearchDocument>, removed: Collection<ItemKey>)

    fun search(contentType: String, line: String, context: SearchContext): SearchResult

    /** What to show of each of [keys] found by [result]. */
    fun matches(contentType: String, result: SearchResult, keys: Collection<ItemKey>, context: SearchContext): Map<ItemKey, SearchMatch>
}
