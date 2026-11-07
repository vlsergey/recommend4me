package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.StoredItem
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.suggestion.WorkChances
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import org.springframework.stereotype.Component

/**
 * THE DOCUMENTS OF THE WORKS the search plugin indexes and reads: their corrected titles, facets and texts, their
 * chapters, with the vectors of the texts.
 */
@Component
class SearchDocuments(private val plugins: Plugins, private val suggestions: FacetSuggestions) {

    /**
     * Every document of a type, streamed: the items read a chunk at a time; the values the model gives them made of
     * every work of a source in one pass first, and its tree of tags read once, held while its chunks are read.
     */
    fun documentsOf(type: TypeStore): Sequence<SearchDocument> = sequence {
        for (s in type.sources) {
            val ids = s.items.keys().map { it.id }
            val chances = suggestions.chances(s, null)
            val hierarchies = s.hierarchies()
            for (chunk in ids.chunked(CHUNK)) yieldAll(
                documents(s, chunk, chances = chances, hierarchies = hierarchies).values
            )
        }
    }

    /**
     * The documents of the items [ids] of a source, read in one batch per table: their fields — the chapters read among
     * them — and, with [passages], the passages of the texts searched by meaning and of the chapters: every window of a
     * text longer than one with its own vector, a shorter one whole with the text's. The values the model gives them of
     * their [chances], made now of them when not given; the source's tree of tags [hierarchies] read now when not
     * given.
     *
     * THE WORK'S OWN WORDS ([SearchField.counted]) — every text of it, every chapter, every value its site gives it —
     * are all in it, those not searched of weight 0: a name written in them is the work naming it
     * ([SearchProvider.mentions]). The values the user or the model gave it are a field of their own beside the site's,
     * searched, never counted: the model would count its own guesses. And the language its description is written in
     * ([descriptionLanguage]).
     */
    fun documents(
        s: SourceStore,
        ids: List<String>,
        passages: Boolean = true,
        chances: WorkChances? = null,
        hierarchies: Map<String, TagHierarchy> = s.hierarchies(),
    ): Map<ItemKey, SearchDocument> {
        val read = Read(s, ids, passages, chances ?: suggestions.chances(s, ids), hierarchies)
        return read.heads.map { (id, head) -> ItemKey(s.id, id) to read.documentOf(id, head) }.toMap()
    }

    /** What the documents of the items [ids] of [s] are made of, read in one batch per table. */
    private inner class Read(
        private val s: SourceStore,
        ids: List<String>,
        passages: Boolean,
        chances: WorkChances,
        hierarchies: Map<String, TagHierarchy>,
    ) {
        private val schema = s.schema
        val heads = s.items.heads(ids)
        private val site = s.items.facetsOf(ids)
        private val facets = site.mapValues { (id, values) -> ModelValues.of(schema, values, chances[id]) }
        private val facetCorrections = s.corrections.facetsOf(ids)
        private val fields = s.corrections.fieldsOf(ids)
        private val texts = s.items.textsOf(ids)

        /** The values of every item as the user says, the user's own among them: what is indexed — and named. */
        private val corrected = ids.associateWith { id ->
            TagHierarchy.corrected(facets[id].orEmpty(), facetCorrections[id].orEmpty(), hierarchies, s.singleFacets)
        }
        private val names =
            schema.facets.associate { def ->
                def.key to
                    s.names(
                        def.key,
                        ids.flatMap { corrected[it]?.get(def.key).orEmpty() + site[it]?.get(def.key).orEmpty() }
                            .distinct(),
                    ) +
                        facetCorrections.values
                            .flatten()
                            .filter { it.facet == def.key && it.name != null }
                            .associate { it.key to it.name!! }
            }
        private val encoder = plugins.textEncoder()?.takeIf { passages }
        private val vectors = encoder?.let { e -> s.textVectors.ofMany(ids, e.id) }.orEmpty()
        private val windows = encoder?.let { e -> s.textVectors.windows.windowsOf(ids, e.id) }.orEmpty()
        private val parts = s.parts.contentsOf(ids)
        private val partWindows = encoder?.let { e -> s.parts.windows.windowsByParts(ids, e.id) }.orEmpty()

        /** A word in a chapter says the least of what the work is about: its weight the least of the texts'. */
        private val partWeight = schema.texts.minOfOrNull { it.searchWeight } ?: 0f
        private val meaningful = schema.texts.filter { it.searchByMeaning }.map { it.key }.toSet()

        /** The document of the item [id] of the [head]. */
        fun documentOf(id: String, head: StoredItem): SearchDocument {
            val own = fields[id].orEmpty()
            val itemFacets = corrected[id].orEmpty()
            val itemTexts = Corrected.texts(texts[id].orEmpty(), own)
            val docFields = ArrayList<SearchField>()
            // The fields' names are what the reranker reads them as, plain words; where a match is the
            // interface is told by the field's key ([Search.places])
            docFields += SearchField(TITLE, TITLE, 1.0f, listOf(Corrected.title(head.title, own)), naming = true)
            schema.facets.forEach { def -> docFields += facetFields(id, def, itemFacets[def.key].orEmpty()) }
            schema.texts.forEach { def ->
                itemTexts[def.key]?.let {
                    docFields += SearchField(def.key, def.key, def.searchWeight, listOf(it), counted = true)
                }
            }
            val docPassages = ArrayList<SearchPassage>()
            docFields
                .filter { it.key in meaningful }
                .forEach { f -> docPassages += passagesOf(f, vectors[id]?.get(f.key), windows[id]?.get(f.key)) }
            chapters(id, docFields, docPassages)
            return SearchDocument(ItemKey(s.id, id), docFields, docPassages, descriptionLanguage(schema, itemFacets))
        }

        /**
         * The fields of the facet [def] of the item [id] of the values [keys]: the site's own, as the user left them;
         * the rest the user's and the model's.
         */
        private fun facetFields(id: String, def: FacetDef, keys: List<String>): List<SearchField> {
            val removed =
                facetCorrections[id].orEmpty().filter { it.facet == def.key && !it.added }.map { it.key }.toSet()
            val own = site[id]?.get(def.key).orEmpty().filter { it !in removed }.distinct()
            val given = keys.filter { it !in own }
            fun named(list: List<String>) = list.map { names[def.key]?.get(it) ?: it }
            return listOfNotNull(
                if (own.isEmpty()) null
                else
                    SearchField(
                        FACET + def.key,
                        def.key,
                        def.searchWeight,
                        named(own),
                        list = true,
                        naming = def.names,
                        counted = true,
                    ),
                if (given.isNotEmpty() && def.searchWeight > 0)
                    SearchField(
                        FACET + def.key,
                        def.key,
                        def.searchWeight,
                        named(given),
                        list = true,
                        naming = def.names,
                        group = FACET + def.key + GIVEN,
                    )
                else null,
            )
        }

        /** The chapters read of the item [id]: searched by their words and by the meaning of every window of them. */
        private fun chapters(id: String, docFields: MutableList<SearchField>, docPassages: MutableList<SearchPassage>) {
            parts[id].orEmpty().forEachIndexed { n, (partId, title, content) ->
                val field =
                    SearchField(
                        PART + partId,
                        title?.takeIf { it.isNotBlank() } ?: "part ${n + 1}",
                        partWeight,
                        listOf(content),
                        group = PARTS,
                        counted = true,
                    )
                docFields += field
                val w = partWindows[id]?.get(partId)
                docPassages += passagesOf(field, w?.singleOrNull(), w)
            }
        }

        /**
         * The passages of a field: its windows with their vectors when it has more than one, else the whole of it with
         * [whole].
         */
        private fun passagesOf(
            field: SearchField,
            whole: FloatArray?,
            windowVectors: List<FloatArray>?,
        ): List<SearchPassage> {
            val text = field.text
            if (encoder != null && windowVectors != null && windowVectors.size > 1) {
                val spans = encoder.windowSpans(text)
                // The windows of the text as it is now: of another text, the whole is its one passage
                if (spans.size == windowVectors.size)
                    return spans.zip(windowVectors).map { (r, v) -> SearchPassage(field.key, r.first, r.last + 1, v) }
            }
            return listOfNotNull(whole?.let { SearchPassage(field.key, 0, text.length, it) })
        }
    }

    companion object {
        /** The items read and indexed at once: memory, not meaning. */
        private const val CHUNK = 500

        /** The field of the title. */
        const val TITLE = "title"

        /** The fields of the facets: "facet.<facet key>". */
        const val FACET = "facet."

        /** The fields of the chapters: "part:<part id>", searched as one field of the index, [PARTS]. */
        const val PART = "part:"
        private const val PARTS = "parts"

        /** The field of the values of a facet the user and the model gave a work, beside the site's own. */
        private const val GIVEN = "+given"

        /**
         * The language a work's description is written in: its own facet of it ([StandardLanguages.DESCRIPTION]); else,
         * of a work of one language — a book — that one; else none known.
         */
        fun descriptionLanguage(schema: SourceSchema, facets: Map<String, List<String>>): String? =
            facets[StandardLanguages.DESCRIPTION_KEY]?.firstOrNull()
                ?: schema.facet(StandardLanguages.KEY)?.takeIf { it.single }?.let { facets[it.key]?.singleOrNull() }
    }
}
