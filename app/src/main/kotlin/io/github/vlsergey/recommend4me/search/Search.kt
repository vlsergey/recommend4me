package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.fulltext.FullTextNearest
import io.github.vlsergey.recommend4me.fulltext.NearWindow
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.piece.PieceProjections
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

/** The step of a search at work after a version of what it found was told ([SearchProgress]). */
enum class SearchStep {
    /** The works found read against the line by the reranker, a portion of their windows at a time. */
    RERANKING,

    /** The texts of the first of the works found read by their pieces ([SearchPieces]). */
    PIECES,
}

/**
 * What a search tells as it goes ([Search.search]): the works [found] so far, in their order so far, and the step at
 * work after them ([next]). Told on the thread of the search: what it does is waited for, and what it throws ends the
 * search — the one who asked gone.
 */
fun interface SearchProgress {
    fun found(found: SearchResult, next: SearchStep)
}

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
    private val pieces: SearchPieces,
    private val projections: PieceProjections,
    private val meaning: FullTextNearest,
) {

    /**
     * What the plugin is given to search the [type] with: the items nearest by meaning to the line's vector moved by
     * the correction [shift] learnt, if any, by the full-text vectors of the windows ([FullTextNearest]); the windows
     * it found kept for the reranker of this search ([windows]). The documents as a card reads them
     * ([SearchDocuments.shown]), of every work the [chapters] named read with it, those [read] already given as read;
     * the text of any other chapter read when the plugin asks for it.
     */
    private inner class Context(
        private val type: TypeStore,
        private val shift: QueryShift? = null,
        private val read: Map<ItemKey, SearchDocument> = emptyMap(),
        private val chapters: Map<ItemKey, Set<String>> = emptyMap(),
    ) : SearchContext {
        /** The windows nearest to the line the plugin asked for, the nearest first. */
        var windows: List<NearWindow> = emptyList()
            private set

        override fun nearest(line: String): Map<ItemKey, SearchNearest> =
            textVectors
                .query(line)
                ?.let { meaning.nearest(type, shift?.of(it) ?: it) }
                ?.also { windows = it.windows }
                ?.items
                .orEmpty()

        override fun documents(keys: Collection<ItemKey>): Map<ItemKey, SearchDocument> =
            read.filterKeys { it in keys } + documents.shown(type, keys.filter { it !in read }, chapters)

        override fun texts(key: ItemKey, fields: Collection<String>): Map<String, String> {
            val parts = fields.filter { it.startsWith(PART) }.map { it.removePrefix(PART) }
            return type.source(key.source)?.parts?.textsOf(key.id, parts).orEmpty().mapKeys { PART + it.key }
        }
    }

    /**
     * The works matching [line], read as the [language] (ISO 639-1), most relevant first; null without a search plugin.
     * Found by the plugin — by meaning and by words —, then, with a reranker ([rerank]), every one of them read
     * together with the line ([SearchReranking]), and last the texts of the first of them read by their pieces
     * ([SearchPieces]): the reranker reorders the works, the pieces only tell where in a text the line is said, so the
     * order the user waits for comes first. With what was learnt of the user's judgments ([learnt]) — [with], else what
     * is kept ([kept]) — searched by meaning with the closeness corrected ([QueryShift]) and put in the order learnt
     * ([SearchWeights]), of every feature, at the end; of none learnt, in the order of what the reranker said.
     *
     * Every version of the order before the last — of the plugin, of every portion of the reranker's, of the reranker's
     * whole — is told to the [progress] as it is made, with the step that follows.
     */
    fun search(
        typeId: String,
        line: String,
        language: String,
        rerank: Boolean = true,
        learnt: Boolean = rerank,
        with: SearchLearnt? = null,
        progress: SearchProgress? = null,
    ): SearchResult? {
        val provider = plugins.search
        val type = stores.type(typeId)
        if (provider == null || type == null) return null
        val own = if (learnt) with ?: kept(type) else null
        // The index as it is: the works written since are indexed in the background, a request never waits for it
        val context = Context(type, own?.shift)
        val found = provider.search(typeId, line, language, context)
        val reranks = rerank && plugins.reranker() != null
        progress?.found(found, if (reranks) SearchStep.RERANKING else SearchStep.PIECES)
        val read =
            if (!reranks) found
            else
                reranking
                    .reranked(type, found, context.windows) { progress?.found(it, SearchStep.RERANKING) }
                    .also {
                        progress?.found(it, SearchStep.PIECES)
                    }
        val pieced = pieces.read(type, read)
        return own?.weights?.ordered(pieced) ?: pieced
    }

    /** What is kept learnt of the user's judgments of the [type]: of the text encoder of now, its correction. */
    fun kept(type: TypeStore): SearchLearnt =
        SearchLearnt(
            plugins.textEncoder()?.let { type.searchShift.read(it.id)?.first },
            type.searchWeights.read()?.first,
        )

    /**
     * What is shown of every work of [keys] found by [result]: where its words stand together, as the plugin finds it;
     * else the place of the line its pieces found ([SearchResult.places]), the pieces that matched the line's best
     * marked; else what the plugin shows — the passage nearest by meaning, whole.
     */
    fun matches(typeId: String, result: SearchResult, keys: Collection<ItemKey>): Map<ItemKey, SearchMatch> {
        val provider = plugins.search
        val type = stores.type(typeId)
        if (provider == null || type == null) return emptyMap()
        // The documents of the works read once, for the plugin and for the places of the pieces alike: of their
        // chapters, those of the passage nearest and of the place of the pieces alone
        val chapters = keys.associateWith { key ->
            listOfNotNull(result.nearest[key]?.field, result.places[key]?.field)
                .filter { it.startsWith(PART) }
                .mapTo(HashSet()) { it.removePrefix(PART) }
        }
        val docs = documents.shown(type, keys, chapters)
        val found = provider.matches(typeId, result, keys, Context(type, read = docs, chapters = chapters))
        // Of a work whose words stand together, they are shown; else its place by the pieces, when it has one
        val placed = keys.filter { it in result.places && found[it]?.byMeaning != false }
        return found + placesShown(result, placed, docs)
    }

    /** What is shown of the works [placed] of the places of the line their pieces found in their texts. */
    private fun placesShown(
        result: SearchResult,
        placed: List<ItemKey>,
        docs: Map<ItemKey, SearchDocument>,
    ): Map<ItemKey, SearchMatch> {
        val encoder = projections.encoder()
        if (placed.isEmpty() || encoder == null) return emptyMap()
        return placed
            .mapNotNull { key ->
                val place = result.places.getValue(key)
                val text = docs[key]?.fields?.firstOrNull { it.key == place.field }?.text
                text?.let { PlaceShown.of(place, it, encoder.pieceSpans(it)) }?.let { key to it }
            }
            .toMap()
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
        val doc = provider?.let { documents.documents(s, listOf(itemId))[ItemKey(s.id, itemId)] }
        if (provider == null || doc == null) return emptyList()
        val found = places(provider, doc)
        val chapters =
            if (found.any { it.field.startsWith(PART) }) s.parts.titlesOf(listOf(itemId))[itemId].orEmpty()
            else emptyList()
        return found.flatMap { p -> Quotes.of(placeOf(s, p.field, chapters), p.text, p.ranges) }
    }
}
