package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.search.SearchDocuments.Companion.FACET
import io.github.vlsergey.recommend4me.search.SearchDocuments.Companion.PART
import io.github.vlsergey.recommend4me.search.SearchDocuments.Companion.TITLE
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.Quote
import io.github.vlsergey.recommend4me.universe.Quotes
import io.github.vlsergey.recommend4me.work.TextPlace
import org.springframework.stereotype.Service

/**
 * The search over the works of a content type, by the search plugin: the documents of the items made here
 * ([SearchDocuments]), the index the plugin's ([SearchIndex]); what was found read against the line by the reranker
 * ([SearchReranking]), and where in a work its matches and the names it says are.
 */
@Service
class Search(
    private val stores: Stores,
    private val plugins: Plugins,
    private val textVectors: TextVectors,
    private val documents: SearchDocuments,
    private val reranking: SearchReranking,
) {

    /** What the plugin is given to search with: the line's vector moved by the correction [shift] learnt, if any. */
    private fun context(shift: QueryShift? = null) =
        object : SearchContext {
            override fun queryVector(line: String): FloatArray? = textVectors.query(line)?.let { shift?.of(it) ?: it }

            override fun documents(keys: Collection<ItemKey>): Map<ItemKey, SearchDocument> =
                keys
                    .groupBy { it.source }
                    .flatMap { (source, list) ->
                        stores
                            .source(source)
                            ?.let { documents.documents(it, list.map { k -> k.id }, passages = false).entries }
                            .orEmpty()
                    }
                    .associate { it.key to it.value }
        }

    /**
     * The works matching [line], read as the [language] (ISO 639-1), most relevant first; null without a search plugin.
     * Found by the plugin — by meaning and by words — and then, with a reranker ([rerank]), every one of them read
     * together with the line ([SearchReranking]). With what was learnt of the user's judgments ([learnt]) — [with],
     * else what is kept ([kept]) — searched by meaning with the closeness corrected ([QueryShift]) and put in the order
     * learnt ([SearchWeights]); of none learnt, in the order of what the reranker said.
     */
    fun search(
        typeId: String,
        line: String,
        language: String,
        rerank: Boolean = true,
        learnt: Boolean = rerank,
        with: SearchLearnt? = null,
    ): SearchResult? {
        val provider = plugins.search
        val type = stores.type(typeId)
        if (provider == null || type == null) return null
        val own = if (learnt) with ?: kept(type) else null
        // The index as it is: the works written since are indexed in the background, a request never waits for it
        val found = provider.search(typeId, line, language, context(own?.shift))
        val read = if (rerank) reranking.reranked(type, found) else found
        return own?.weights?.ordered(read) ?: read
    }

    /** What is kept learnt of the user's judgments of the [type]: of the text encoder of now, its correction. */
    fun kept(type: TypeStore): SearchLearnt =
        SearchLearnt(
            plugins.textEncoder()?.let { type.searchShift.read(it.id)?.first },
            type.searchWeights.read()?.first,
        )

    fun matches(typeId: String, result: SearchResult, keys: Collection<ItemKey>): Map<ItemKey, SearchMatch> {
        val provider = plugins.search
        val type = stores.type(typeId)
        return if (provider == null || type == null) emptyMap() else provider.matches(typeId, result, keys, context())
    }

    /**
     * Of the works [found] that have a snippet [judged], the user's word on the snippet each is shown with now
     * ([SearchSnippet.judgedShown]): a work shown with another snippet than the one judged is not judged. The snippets
     * of those works alone made.
     */
    fun judgedShown(
        typeId: String,
        found: SearchResult,
        judged: Map<ItemKey, Map<SearchSnippet, SearchFit>>,
    ): Map<ItemKey, SearchFit> =
        SearchSnippet.judgedShown(
            judged,
            matches(typeId, found, found.keys.filter { it in judged }).mapValues { SearchSnippet.of(it.value) },
        )

    /**
     * Where in the work every match is, by the key of the field it was found in ([SearchMatch.field]): the title, a
     * facet, a text by the keys of their labels; a chapter by its title, or by its number among the chapters read when
     * it has none — the chapters of the matched items read in one go per source.
     */
    fun places(matches: Map<ItemKey, SearchMatch>): Map<ItemKey, TextPlace> {
        val out = HashMap<ItemKey, TextPlace>()
        matches.entries
            .groupBy { it.key.source }
            .forEach { (source, list) ->
                val s = stores.source(source) ?: return@forEach
                val inParts = list.filter { it.value.field.startsWith(PART) }.map { it.key.id }
                val chapters = if (inParts.isEmpty()) emptyMap() else s.parts.titlesOf(inParts)
                list.forEach { (key, match) -> out[key] = placeOf(s, match.field, chapters[key.id].orEmpty()) }
            }
        return out
    }

    /**
     * Where in the work the field [field] of its document is: its title, a facet, a text by the keys of their labels; a
     * chapter by its title among its [chapters], or by its number.
     */
    private fun placeOf(s: SourceStore, field: String, chapters: List<Pair<String, String?>>): TextPlace =
        when {
            field == TITLE -> TextPlace(labelKey = TextVectors.TITLE_LABEL)
            field.startsWith(FACET) ->
                field.removePrefix(FACET).let { f ->
                    TextPlace(
                        labelKey =
                            s.schema.facet(f)?.let { I18nKeys.facetLabel(s.id, it) }
                                ?: I18nKeys.qualified(s.id, I18nKeys.facet(f))
                    )
                }
            field.startsWith(PART) -> {
                val partId = field.removePrefix(PART)
                val at = chapters.indexOfFirst { it.first == partId }
                chapters.getOrNull(at)?.second?.takeIf { it.isNotBlank() }?.let { TextPlace(title = it) }
                    ?: TextPlace(part = if (at >= 0) at + 1 else null)
            }
            else -> TextPlace(labelKey = I18nKeys.textLabel(s.id, field))
        }

    /**
     * The passages of the work [itemId] that name any of the values [of] among [names], as its mentions are counted of
     * the index ([SearchProvider.mentions]): its own words read again as they were put in it — what is counted is what
     * is shown — each a paragraph, or the part of a long one around the names. None without a search plugin.
     */
    fun quotes(s: SourceStore, itemId: String, names: Map<String, List<String>>, of: Collection<String>): List<Quote> =
        quoted(s, itemId) { provider, doc -> provider.places(doc, names, of) }

    /**
     * The passages of the work [itemId] that name the characters [a] and [b] in one paragraph, as a pairing is counted
     * of the index ([SearchProvider.together]): both marked.
     */
    fun quotesTogether(
        s: SourceStore,
        itemId: String,
        names: Map<String, List<String>>,
        a: String,
        b: String,
    ): List<Quote> = quoted(s, itemId) { provider, doc -> provider.placesTogether(doc, names, a, b) }

    /** The [places] of the work's document as quotes: by where in the work each is, a paragraph each. */
    private fun quoted(
        s: SourceStore,
        itemId: String,
        places: (SearchProvider, SearchDocument) -> List<NamePlace>,
    ): List<Quote> {
        val provider = plugins.search
        val doc = provider?.let { documents.documents(s, listOf(itemId), passages = false)[ItemKey(s.id, itemId)] }
        if (provider == null || doc == null) return emptyList()
        val found = places(provider, doc)
        val chapters =
            if (found.any { it.field.startsWith(PART) }) s.parts.titlesOf(listOf(itemId))[itemId].orEmpty()
            else emptyList()
        return found.flatMap { p -> Quotes.of(placeOf(s, p.field, chapters), p.text, p.ranges) }
    }
}
