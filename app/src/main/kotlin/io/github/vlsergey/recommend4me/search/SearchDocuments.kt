package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.EveryItemFacets
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
import io.github.vlsergey.recommend4me.vector.Vectors
import org.springframework.stereotype.Component

/**
 * THE DOCUMENTS OF THE WORKS the search plugin indexes and reads: their corrected titles, facets and texts, their
 * chapters, with the vectors of the texts.
 */
@Component
class SearchDocuments(private val plugins: Plugins, private val suggestions: FacetSuggestions) {

    /**
     * Of every item of a type, what its document is made of ([SearchDocument.fingerprint]), its chapters and the
     * vectors not read: of every source its items' heads, values, answers and texts in one pass each, and of its texts
     * and chapters their hashes and how their vectors were made. With them, the values the model gives the works of
     * every source, made of every work in one pass: the documents of those whose fingerprint changed are made of the
     * same ([documentsOf]).
     */
    fun printsOf(type: TypeStore): TypePrints {
        val prints = HashMap<ItemKey, Long>()
        val chances = HashMap<String, WorkChances>()
        for (s in type.sources) {
            val made = suggestions.chances(s, null)
            chances[s.id] = made
            val read = Read(s, null, true, made, s.hierarchies())
            read.heads.forEach { (id, head) -> prints[ItemKey(s.id, id)] = read.printOf(id, head) }
        }
        return TypePrints(prints, chances)
    }

    /**
     * The documents of the items [keys] of a type, streamed: read a chunk at a time, of the values the model gives the
     * works of every source ([chances]), each source's tree of tags read once.
     */
    fun documentsOf(
        type: TypeStore,
        keys: Collection<ItemKey>,
        chances: Map<String, WorkChances>,
    ): Sequence<SearchDocument> = sequence {
        for ((source, list) in keys.groupBy { it.source }) {
            val s = type.sources.firstOrNull { it.id == source } ?: continue
            val hierarchies = s.hierarchies()
            for (chunk in list.map { it.id }.chunked(CHUNK)) yieldAll(
                documents(s, chunk, chances = chances[source], hierarchies = hierarchies).values
            )
        }
    }

    /**
     * What the documents of the items [ids] of a source are made of ([SearchDocument.fingerprint]), their chapters and
     * vectors not read, with the values the model gives them: the documents of those whose fingerprint changed are made
     * of the same ([documents]).
     */
    fun printsOf(s: SourceStore, ids: List<String>, hierarchies: Map<String, TagHierarchy>): SourcePrints {
        val made = suggestions.chances(s, ids)
        val read = Read(s, ids, true, made, hierarchies)
        return SourcePrints(read.heads.map { (id, head) -> ItemKey(s.id, id) to read.printOf(id, head) }.toMap(), made)
    }

    /** What the documents of some items of a source are made of ([prints]) and the values the model gives them. */
    class SourcePrints(val prints: Map<ItemKey, Long>, val chances: WorkChances)

    /**
     * What the documents of every item of a type are made of ([prints]) and the values the model gives the works of
     * every source ([chances]) they were made with.
     */
    class TypePrints(val prints: Map<ItemKey, Long>, val chances: Map<String, WorkChances>)

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

    /**
     * What the documents of the items [ids] of [s] — of every item when null — are made of, read in one batch per table
     * (of every item, one pass over each table). The chapters' texts and the vectors read only for a document made
     * ([documentOf]); the fingerprint ([printOf]) reads their hashes alone.
     */
    private inner class Read(
        private val s: SourceStore,
        ids: List<String>?,
        passages: Boolean,
        chances: WorkChances,
        hierarchies: Map<String, TagHierarchy>,
    ) {
        private val schema = s.schema
        val heads: Map<String, StoredItem> =
            ids?.let { s.items.heads(it) }
                ?: HashMap<String, StoredItem>().also { all -> s.items.forEachHead { all[it.id] = it } }
        private val ids: List<String> = ids ?: heads.keys.toList()
        private val every = ids == null
        private val site = if (every) EveryItemFacets.siteOf(s) else s.items.facetsOf(this.ids)
        private val facets = site.mapValues { (id, values) -> ModelValues.of(schema, values, chances[id]) }
        /**
         * Every answer of the source: they are few. The names the user gave values are read of all of them — a value
         * the user named on one work is named so on every work — whether every work is read or a few.
         */
        private val answers = s.corrections.allFacets()
        private val facetCorrections = if (every) answers.groupBy { it.itemId } else s.corrections.facetsOf(this.ids)
        private val fields = if (every) s.corrections.allFields() else s.corrections.fieldsOf(this.ids)
        private val texts = if (every) everyText() else s.items.textsOf(this.ids)

        /** The values of every item as the user says, the user's own among them: what is indexed — and named. */
        private val corrected =
            this.ids.associateWith { id ->
                TagHierarchy.corrected(
                    facets[id].orEmpty(),
                    facetCorrections[id].orEmpty(),
                    hierarchies,
                    s.singleFacets,
                )
            }
        private val names =
            schema.facets.associate { def ->
                def.key to
                    s.names(
                        def.key,
                        this.ids.flatMapTo(LinkedHashSet()) {
                            corrected[it]?.get(def.key).orEmpty() + site[it]?.get(def.key).orEmpty()
                        },
                    ) + answers.filter { it.facet == def.key && it.name != null }.associate { it.key to it.name!! }
            }
        private val meaningful = schema.texts.filter { it.searchByMeaning }.map { it.key }.toSet()
        private val encoder = plugins.textEncoder()?.takeIf { passages }

        // The texts a work is found by meaning by alone: the title's vector is read by no passage
        private val vectors by lazy { encoder?.let { e -> s.textVectors.ofMany(this.ids, e.id, meaningful) }.orEmpty() }
        private val windows by lazy {
            encoder?.let { e -> s.textVectors.windows.windowsOf(this.ids, e.id, e.windowsId) }.orEmpty()
        }
        private val parts by lazy { s.parts.contentsOf(this.ids) }
        private val partWindows by lazy {
            encoder?.let { e -> s.parts.windows.windowsByParts(this.ids, e.windowsId) }.orEmpty()
        }

        /** Of the texts searched by meaning and of the chapters, what their passages are made of, no vector read. */
        private val textMarks by lazy {
            encoder?.let { e -> s.textVectors.marksOf(if (every) null else this.ids, e.id, meaningful) }.orEmpty()
        }
        private val partMarks by lazy { s.parts.marks.of(if (every) null else this.ids) }

        /** A word in a chapter says the least of what the work is about: its weight the least of the texts'. */
        private val partWeight = schema.texts.minOfOrNull { it.searchWeight } ?: 0f

        /** Every text of every item, of the keys of the schema: one pass over the table. */
        private fun everyText(): Map<String, Map<String, String>> {
            val keys = schema.texts.map { it.key }
            val out = HashMap<String, Map<String, String>>()
            if (keys.isNotEmpty()) s.items.forEachTexts(keys) { id, texts -> out[id] = texts }
            return out
        }

        /**
         * The fields of the item [id] of the [head] but its chapters — its title, its facets, its texts — and the
         * language its description is written in.
         */
        private fun ownFields(id: String, head: StoredItem): Pair<List<SearchField>, String?> {
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
            return docFields to descriptionLanguage(schema, itemFacets)
        }

        /** What the document of the item [id] of the [head] is made of ([SearchDocument.fingerprint]). */
        fun printOf(id: String, head: StoredItem): Long =
            ownFields(id, head).let { (own, language) -> printOf(id, own, language) }

        /**
         * What the document of the item [id] is made of: the fields but its chapters ([own]) whole, the [language]; of
         * its texts searched by meaning and of its chapters, their hashes and how their vectors were made — the same
         * text and the same vectors make the same passages — and the encoder and the version of how documents are made
         * ([DOCUMENTS]).
         */
        private fun printOf(id: String, own: List<SearchField>, language: String?): Long {
            val text = StringBuilder()
            fun put(value: Any?) {
                text.append(value).append('\u0000')
            }
            put(DOCUMENTS)
            put(encoder?.id)
            put(language)
            own.forEach { f ->
                put(f.key)
                put(f.name)
                put(f.group)
                put(f.weight)
                put("" + f.list + f.naming + f.counted)
                // In order: the values of every work read at once come in the table's, those of a few by their places
                f.values.sorted().forEach(::put)
            }
            textMarks[id]?.entries?.sortedBy { it.key }?.forEach { (key, mark) -> put("$key=$mark") }
            partMarks[id]?.forEach { p ->
                put(p.partId)
                put(p.title)
                put(p.hash)
                put(if (encoder != null && p.encoder == encoder.windowsId) p.encodedHash else null)
            }
            return Vectors.keyOf(text.toString())
        }

        /** The document of the item [id] of the [head]. */
        fun documentOf(id: String, head: StoredItem): SearchDocument {
            val (own, language) = ownFields(id, head)
            val docFields = ArrayList(own)
            val docPassages = ArrayList<SearchPassage>()
            docFields
                .filter { it.key in meaningful }
                .forEach { f -> docPassages += passagesOf(f, vectors[id]?.get(f.key), windows[id]?.get(f.key)) }
            chapters(id, docFields, docPassages)
            return SearchDocument(ItemKey(s.id, id), docFields, docPassages, language, printOf(id, own, language))
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

        /**
         * The version of how a document is made of what it is made of, in its fingerprint ([Read.printOf]): a change of
         * the making makes every document again.
         */
        private const val DOCUMENTS = 1

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
