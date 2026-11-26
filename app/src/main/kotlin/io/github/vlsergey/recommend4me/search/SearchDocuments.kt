package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.ItemKey
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
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.ListModelValues
import org.springframework.stereotype.Component

/**
 * THE DOCUMENTS OF THE WORKS the search plugin indexes and reads: their corrected titles, facets and texts, their
 * chapters: what they are found by by their words.
 */
@Component
class SearchDocuments(private val suggestions: FacetSuggestions, private val lists: ListIndexes) {

    /**
     * The documents of the works [keys] of the [type] as a card of the search and the reranker read them: with the
     * values the list keeps the model gave them ([ListModelValues]) — no model run, the language of a text the model
     * says among them —; of the chapters those of [chapters] read, every other named alone.
     */
    fun shown(
        type: TypeStore,
        keys: Collection<ItemKey>,
        chapters: Map<ItemKey, Set<String>>,
    ): Map<ItemKey, SearchDocument> =
        keys
            .groupBy { it.source }
            .flatMap { (source, list) ->
                val s = type.source(source) ?: return@flatMap emptyList()
                val ids = list.map { it.id }
                val given = lists.of(type.id).searching { ListModelValues.of(it, source, ids) }
                val read = ids.associateWith { chapters[ItemKey(source, it)].orEmpty() }
                documents(s, ids, models = false, given = given, chapters = read).entries
            }
            .associate { it.key to it.value }

    /**
     * What the documents of the items [ids] of a source are made of ([SearchDocument.fingerprint]) in the index of the
     * search — the values the site and the user give them alone —, their chapters not read: the documents of those
     * whose fingerprint changed are made of the same ([documents] of no [models]).
     */
    fun printsOf(s: SourceStore, ids: List<Long>, hierarchies: Map<String, TagHierarchy>): Map<ItemKey, Long> {
        val read = Read(s, ids, null, hierarchies, null)
        return read.heads.map { (id, head) -> ItemKey(s.id, id) to read.printOf(id, head) }.toMap()
    }

    /**
     * The documents of the items [ids] of a source, read in one batch per table: their fields — the chapters read among
     * them; what is searched by meaning is not in them, the full-text vectors of the windows ([FullTextNearest]
     * [io.github.vlsergey.recommend4me.fulltext.FullTextNearest]). Of [models], the values the models give them too, of
     * their [chances], made now of them when not given; else the [given] values the list keeps the model gave them
     * ([io.github.vlsergey.recommend4me.worklist.ListModelValues]), when the caller read them; else the site's and the
     * user's alone: the documents of the index of the search, which no refit of the models touches (user, 2026-11-18).
     * The source's tree of tags [hierarchies] read now when not given. Of [chapters], of every item the chapters whose
     * texts are read, every other one named alone ([SearchField.read]) — a card shows one place of a work, and its
     * chapters are megabytes; every chapter read when null.
     *
     * THE WORK'S OWN WORDS ([SearchField.counted]) — every text of it, every chapter, every value its site gives it —
     * are all in it, those not searched of weight 0: a name written in them is the work naming it
     * ([SearchProvider.mentions]). The values the user or the model gave it are a field of their own beside the site's,
     * searched, never counted: the model would count its own guesses. And the language its description is written in
     * ([descriptionLanguage]); of every text the language it is in as its values say ([SearchField.language]).
     */
    fun documents(
        s: SourceStore,
        ids: List<Long>,
        chances: WorkChances? = null,
        hierarchies: Map<String, TagHierarchy> = s.hierarchies(),
        models: Boolean = true,
        given: Map<Long, Map<String, List<String>>>? = null,
        chapters: Map<Long, Set<String>>? = null,
    ): Map<ItemKey, SearchDocument> {
        val made = if (models && given == null) chances ?: suggestions.chances(s, ids) else null
        val read = Read(s, ids, made, hierarchies, given)
        return read.heads
            .map { (id, head) ->
                ItemKey(s.id, id) to read.documentOf(id, head, chapters?.let { it[id].orEmpty() })
            }
            .toMap()
    }

    /**
     * What the documents of the items [ids] of [s] are made of, read in one batch per table. The chapters' texts read
     * only for a document made ([documentOf]); the fingerprint ([printOf]) reads their hashes alone. The values the
     * model gives them of their [chances], else the [given] ones; null of both — none: the site's and the user's alone.
     */
    private inner class Read(
        private val s: SourceStore,
        private val ids: List<Long>,
        chances: WorkChances?,
        hierarchies: Map<String, TagHierarchy>,
        given: Map<Long, Map<String, List<String>>>?,
    ) {
        private val schema = s.schema
        val heads: Map<Long, StoredItem> = s.items.heads(ids)
        private val site = s.items.facetsOf(ids)
        private val facets =
            when {
                chances != null -> site.mapValues { (id, values) -> ModelValues.of(schema, values, chances[id]) }
                given != null -> ids.associateWith { id -> withGiven(site[id].orEmpty(), given[id].orEmpty()) }
                else -> site
            }

        private val facetCorrections = s.corrections.facetsOf(ids)
        private val fields = s.corrections.fieldsOf(ids)
        private val texts = s.items.textsOf(ids)

        /** The values of every item as the user says, the user's own among them: what is indexed — and named. */
        private val corrected = ids.associateWith { id ->
            TagHierarchy.corrected(
                facets[id].orEmpty(),
                facetCorrections[id].orEmpty(),
                hierarchies,
                s.singleFacets,
            )
        }
        /**
         * The names of the values of the items, the source's read in one pass over its dictionary; and the names the
         * user gave them, read by the values — a value the user named on one work is named so on every work.
         */
        private val names: Map<String, Map<String, String>> = run {
            val wanted =
                schema.facets.associate { def ->
                    def.key to
                        ids.flatMapTo(LinkedHashSet()) {
                            corrected[it]?.get(def.key).orEmpty() + site[it]?.get(def.key).orEmpty()
                        }
                }
            s.names(wanted).mapValues { (facet, named) ->
                val keys = wanted[facet].orEmpty()
                if (keys.isEmpty()) named
                else named + s.corrections.facetsWith(facet, keys, named = true).associate { it.key to it.name!! }
            }
        }
        /** Every chapter of every item with its text: of a document of the index. */
        private val parts by lazy { s.parts.contentsOf(ids) }

        /** Every chapter of every item, no text read: of a document of a card. */
        private val titles by lazy { s.parts.titlesOf(ids) }

        /** Of the chapters, what they are made of, no text read. */
        private val partMarks by lazy { s.parts.marks.of(ids) }

        /** A word in a chapter says the least of what the work is about: its weight the least of the texts'. */
        private val partWeight = schema.texts.minOfOrNull { it.searchWeight } ?: 0f

        /**
         * The fields of the item [id] of the [head] but its chapters — its title, its facets, its texts — and the
         * language its description is written in.
         */
        private fun ownFields(id: Long, head: StoredItem): Pair<List<SearchField>, String?> {
            val own = fields[id].orEmpty()
            val itemFacets = corrected[id].orEmpty()
            val itemTexts = Corrected.texts(texts[id].orEmpty(), own)
            val docFields = ArrayList<SearchField>()
            // The fields' names are what the reranker reads them as, plain words; where a match is the
            // interface is told by the field's key ([Search.places])
            docFields += SearchField(TITLE, TITLE, 1.0f, listOf(Corrected.title(head.title, own)), naming = true)
            schema.facets.forEach { def -> docFields += facetFields(id, def, itemFacets[def.key].orEmpty()) }
            val language = descriptionLanguage(schema, itemFacets)
            schema.texts.forEach { def ->
                itemTexts[def.key]?.let {
                    docFields +=
                        SearchField(def.key, def.key, def.searchWeight, listOf(it), counted = true, language = language)
                }
            }
            return docFields to language
        }

        /** The language the chapters of the item [id] are written in: the work's one, as its values say; else none. */
        private fun textLanguage(id: Long): String? =
            schema
                .facet(StandardLanguages.KEY)
                ?.takeIf { it.single }
                ?.let { corrected[id]?.get(it.key)?.singleOrNull() }

        /** What the document of the item [id] of the [head] is made of ([SearchDocument.fingerprint]). */
        fun printOf(id: Long, head: StoredItem): Long =
            ownFields(id, head).let { (own, language) -> printOf(id, own, language) }

        /**
         * What the document of the item [id] is made of: the fields but its chapters ([own]) whole, the [language]; of
         * its chapters, their titles and their texts' hashes; and the version of how documents are made ([DOCUMENTS]).
         */
        private fun printOf(id: Long, own: List<SearchField>, language: String?): Long {
            val text = StringBuilder()
            fun put(value: Any?) {
                text.append(value).append('\u0000')
            }
            put(DOCUMENTS)
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
            partMarks[id]?.forEach { p ->
                put(p.partId)
                put(p.title)
                put(p.hash)
            }
            return Vectors.keyOf(text.toString())
        }

        /**
         * The document of the item [id] of the [head]: of its chapters the texts of [read] alone, the others named; of
         * null, every chapter's text.
         */
        fun documentOf(id: Long, head: StoredItem, read: Set<String>?): SearchDocument {
            val (own, language) = ownFields(id, head)
            val docFields = ArrayList(own)
            if (read == null) chapters(id, docFields) else named(id, read, docFields)
            return SearchDocument(ItemKey(s.id, id), docFields, language, printOf(id, own, language))
        }

        /**
         * The fields of the facet [def] of the item [id] of the values [keys]: the site's own, as the user left them;
         * the rest the user's and the model's.
         */
        private fun facetFields(id: Long, def: FacetDef, keys: List<String>): List<SearchField> {
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

        /** The chapters read of the item [id]: searched by their words. */
        private fun chapters(id: Long, docFields: MutableList<SearchField>) {
            val language = textLanguage(id)
            parts[id].orEmpty().forEachIndexed { n, (partId, title, content) ->
                docFields += chapter(partId, title, n, listOf(content), language)
            }
        }

        /** The chapters of the item [id], the texts of [read] alone read: of a card, one place of the work shown. */
        private fun named(id: Long, read: Set<String>, docFields: MutableList<SearchField>) {
            val language = textLanguage(id)
            val listed = titles[id].orEmpty()
            val texts = s.parts.textsOf(id, listed.map { it.first }.filter { it in read })
            listed.forEachIndexed { n, (partId, title) ->
                docFields += chapter(partId, title, n, listOfNotNull(texts[partId]), language, partId in texts)
            }
        }

        /** The field of the [n]th chapter [partId] of its [title] and its text's [values], none when not [read]. */
        private fun chapter(
            partId: String,
            title: String?,
            n: Int,
            values: List<String>,
            language: String?,
            read: Boolean = true,
        ) =
            SearchField(
                PART + partId,
                title?.takeIf { it.isNotBlank() } ?: "part ${n + 1}",
                partWeight,
                values,
                group = PARTS,
                counted = true,
                language = language,
                read = read,
            )
    }

    companion object {
        /** The values of a work of its [site] and the model's [given], facet by facet. */
        private fun withGiven(
            site: Map<String, List<String>>,
            given: Map<String, List<String>>,
        ): Map<String, List<String>> =
            (site.keys + given.keys).associateWith { f -> (site[f].orEmpty() + given[f].orEmpty()).distinct() }

        /** The items read and indexed at once: memory, not meaning. */
        private const val CHUNK = 500

        /**
         * The version of how a document is made of what it is made of, in its fingerprint ([Read.printOf]): a change of
         * the making makes every document again.
         */
        private const val DOCUMENTS = 2

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
