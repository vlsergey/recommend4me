package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.matrix.CardSource
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.universe.CharactersRead
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.laps.Laps
import org.springframework.beans.factory.ObjectProvider
import java.util.stream.IntStream

/** [WorkChances] as they are made, a facet after another. */
internal typealias MadeChances = HashMap<String, HashMap<String, Map<String, Chance>>>

/**
 * THE CHAIN OF THE FACETS: the chances of works made in memory by the models kept, a facet after
 * another, in the order of [facetsOf] — each reading as its context the values the site and the user
 * give of every other facet and the values the model gives of the facets before it, made in the same
 * pass; of every facet what a work is shown with ([keep]). Nothing waits for a refit, nothing is
 * written of a work (user, 2026-10-15).
 */
internal class FacetChain(
    private val stores: Stores,
    private val plugins: Plugins,
    models: ObjectProvider<FacetModel>,
    /** The card, when the launch asks for it: the inputs of a pass of every work put there for its facets ([WorksRead]). */
    private val cards: ObjectProvider<CardSource>,
    private val vocabularies: FacetVocabularies,
) {
    /** The facets worked out by models of their own ([FacetModel]): no suggester of them fitted, their chances theirs. */
    private val ownModels: List<FacetModel> by lazy { models.orderedStream().toList() }

    /**
     * The facets the model works on, in the order of the chain they are worked out in: those it
     * gives values of first — their values are the context of the others, the characters are of the
     * universes given before them, the pairings of the characters — in the order of the schema.
     */
    fun facetsOf(store: SourceStore): List<FacetDef> =
        store.schema.facets.filter { (it.suggest || it.infer) && ownModels.none { m -> m.facet == it.key } }.sortedBy { !it.infer }

    /**
     * Everything the suggesters of the facet learn from, read now: what a comparison of them fits
     * every one on ([FacetComparisons]); null when the source has no such facet or nothing to read with.
     */
    fun task(store: SourceStore, facetKey: String): SuggestionTask? {
        val facets = facetsOf(store)
        val k = facets.indexOfFirst { it.key == facetKey }.takeIf { it >= 0 } ?: return null
        val encoder = plugins.textEncoder() ?: return null
        val read = worksRead(store, encoder, null)
        val made = chain(store, encoder, read, k)
        return load(store, facets[k], encoder, vocabularies.vocabularyOf(store, facets[k], null, made, read.characters, read.answers, site = read.site), null, made, null, read).task
    }

    /**
     * THE CHANCES OF THE WORKS [ids] — of every work of the source when null — made now in memory
     * by the models kept, down the chain of the facets; of the facets up to [through] alone when
     * given (none when the source works out no such facet). Of every facet the values shown: had,
     * rejected, given as examples, likely, and the likeliest others offered; the drafts of the layer
     * among them ([Chance.draftedFrom]). A facet never fitted has no chances, its drafts alone.
     * Nothing is written, nothing waited for: never in the queue of the computations — the marking
     * of a work waits for nothing (user, 2026-10-15).
     */
    fun chances(store: SourceStore, ids: Collection<String>?, through: String? = null, laps: Laps = Laps(), every: EveryItemFacets? = null): WorkChances {
        val facets = facetsOf(store)
        val upTo = if (through == null) facets.size else facets.indexOfFirst { it.key == through } + 1
        return chain(store, ids, upTo, laps, every)
    }

    /**
     * The model's chance of ONE value [key] of the [facet] for every work of [ids], whatever else it
     * gives them and whether or not the work may have it ([Vocabulary.allowed]) — the works of a
     * pairing's universes ranked by it. Made now as [chances] are, none kept, nothing waited for; a
     * work the facet's model does not score, or a value it does not know, has none.
     */
    fun chancesOfValue(store: SourceStore, ids: Collection<String>, facet: String, key: String): Map<String, Double> {
        if (ids.isEmpty()) return emptyMap()
        val facets = facetsOf(store)
        val k = facets.indexOfFirst { it.key == facet }.takeIf { it >= 0 } ?: return emptyMap()
        val suggester = plugins.suggester(store.id, facet) ?: return emptyMap()
        val encoder = plugins.textEncoder() ?: return emptyMap()
        val read = worksRead(store, encoder, reading(store, ids, k + 1))
        val made = chain(store, encoder, read, k)
        val scored = scored(store, facets[k], suggester, encoder, read, made) ?: return emptyMap()
        val v = scored.data.values.indexOf(key).takeIf { it >= 0 } ?: return emptyMap()
        val out = HashMap<String, Double>()
        val asked = ids.toHashSet()
        scored.data.ids.indices.filter { scored.data.ids[it] in asked }.chunked(CHUNK).forEach { chunk ->
            val rows = scored.scores.of(chunk.toIntArray())
            chunk.forEachIndexed { j, i -> out[scored.data.ids[i]] = rows[j][v].toDouble() }
        }
        return out
    }

    /**
     * The model's weight of every value of the [facet] over the works as a whole ([FacetSuggester.weights]):
     * of the scoring kept alone, no work read — the values the model knows, the likelier the larger.
     * Null when the facet was never fitted, or its suggester tells none.
     */
    fun weights(store: SourceStore, facet: String): Map<String, Double>? {
        val kept = store.suggestions.scoring(facet) ?: return null
        val suggester = plugins.suggester(store.id, facet)?.takeIf { it.id == kept.suggester } ?: return null
        val weights = suggester.weights(kept.bytes)?.takeIf { it.size == kept.values.size } ?: return null
        return kept.values.indices.associate { kept.values[it] to weights[it] }
    }

    /**
     * [chances] of the [wanted] facets alone — those on a card, those the model of the grades reads: the
     * chain down to the last of them. [laps]: the time of every step of it. [every]: of every work
     * ([ids] null), their facets and answers read already by the reading it serves: not read again.
     */
    fun chancesOf(store: SourceStore, ids: Collection<String>?, wanted: Collection<String>, laps: Laps = Laps(), every: EveryItemFacets? = null): WorkChances {
        val given = every?.takeIf { ids == null }
        return facetsOf(store).lastOrNull { it.key in wanted }?.let { chances(store, ids, it.key, laps, given) }
            ?: seeded(store, ids, every = given)
    }

    /** The chances of the works [ids] (every work when null) of the first [upTo] facets of the chain. */
    fun chain(store: SourceStore, ids: Collection<String>?, upTo: Int, laps: Laps = Laps(), every: EveryItemFacets? = null): MadeChances {
        val given = every?.takeIf { ids == null }
        val encoder = plugins.textEncoder() ?: return seeded(store, ids, every = given)
        if (ids != null && ids.isEmpty()) return seeded(store, ids)
        return worksRead(store, encoder, reading(store, ids, upTo), given, card = true).use { chain(store, encoder, it, upTo, laps) }
    }

    /** The chain over the works of [read]: what the facets read alike read once, for all of them. */
    fun chain(store: SourceStore, encoder: TextEncoder, read: WorksRead, upTo: Int, laps: Laps = Laps()): MadeChances {
        val drafts = read.drafts
        laps.lap("drafts", drafts.size)
        val made = seeded(store, read.only, drafts, read.every)
        laps.lap("models of their own", made.size)
        facetsOf(store).take(upTo).forEach { facet ->
            val suggester = plugins.suggester(store.id, facet.key) ?: return@forEach
            val own = laps.within(facet.key)
            val scored = scored(store, facet, suggester, encoder, read, made, own) ?: return@forEach
            scored.scores.use { keep(made, facet, scored.data, it, laps = own) }
            own.lap("kept", scored.data.ids.size)
            read.given(store, facet.key, made)
            own.lap("context")
            laps.restart()
        }
        return made
    }

    /**
     * The works to read for the chances of the works [ids] (every work when null) of the first [upTo]
     * facets of the chain: every facet's suggester says what it reads ([FacetSuggester.reads]), the
     * works read for one facet the context of the next.
     */
    fun reading(store: SourceStore, ids: Collection<String>?, upTo: Int): Collection<String>? =
        facetsOf(store).take(upTo).fold(ids) { asked, facet ->
            val suggester = plugins.suggester(store.id, facet.key)
            if (asked == null || suggester == null) asked else suggester.reads(asked)
        }

    /**
     * What the facets of a pass over the works [ids] (every work when null) read alike. [card]: the
     * pass ends with the operation, and whatever reads its inputs ends with it — of every work its
     * inputs put on the card for its facets, freed as it is closed; never of a task handed out.
     */
    fun worksRead(
        store: SourceStore, encoder: TextEncoder, ids: Collection<String>?, every: EveryItemFacets? = null, characters: CharactersRead = charactersRead(store),
        card: Boolean = false,
    ) = WorksRead(
        store, encoder, plugins.imageEncoder()?.id, stores.typeOf(store.id).embeddings.ids(), ids, characters, every?.takeIf { ids == null },
        if (card && ids == null) cards.getIfAvailable()?.cublas() else null,
    )

    /** The characters of the dictionary of the [store]'s type, for one operation to read once ([CharactersRead]). */
    fun charactersRead(store: SourceStore) = CharactersRead(stores.typeOf(store.id).universes)

    /** The chances of a facet's works as the model scores them: the task they are of, its scores, the vocabulary read. */
    class Scored(val data: FacetData, val scores: SuggestionScores, val vocabulary: Vocabulary?)

    /**
     * THE FACET'S CHANCES OF THE WORKS OF [read], by the model kept. By its scoring
     * kept apart ([KeptScoring]) where there is one: the works' own features read, the values
     * those of the scoring — no dictionary, no name of any value (user, 2026-10-16): a page of twenty
     * works read every character's name and the model whole, and a few of them at once ran the heap
     * out. Else — of a suggester without one, of a model fitted before scorings were kept, till the
     * next refresh keeps it — on the whole task. Null when the facet was never fitted.
     */
    fun scored(
        store: SourceStore, facet: FacetDef, suggester: FacetSuggester, encoder: TextEncoder, read: WorksRead, made: WorkChances,
        laps: Laps = Laps(),
    ): Scored? {
        val ids = read.only
        store.suggestions.scoring(facet.key)?.takeIf { it.suggester == suggester.id }?.let { kept ->
            laps.lap("scoring kept read", kept.bytes.size / 1024)
            val vocabulary = vocabularies.vocabularyOf(store, facet, ids, made, read.characters, read.answers, light = true, site = read.site)
            laps.lap("vocabulary")
            val data = FacetData.load(
                store, facet, encoder, read, modelled = made, allowed = vocabulary?.allowed, barred = vocabulary?.barred,
                examples = vocabulary?.examples, kept = kept.values, laps = laps,
            )
            if (data.values.isEmpty()) return null
            return suggester.scoring(kept.bytes, data.task)?.let { Scored(data, it, vocabulary) }.also { laps.lap("scored", data.values.size) }
        }
        val stored = store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id } ?: return null
        val vocabulary = vocabularies.vocabularyOf(store, facet, ids, made, read.characters, read.answers, site = read.site)
        val data = load(store, facet, encoder, vocabulary, ids, made, stored.values, read)
        if (data.values.isEmpty()) return null
        laps.lap("whole task read, no scoring kept")
        val fitted = suggester.unpack(stored.content, data.task) ?: return null
        return Scored(data, fitted.on(data.task), vocabulary).also { laps.lap("scored", data.values.size) }
    }

    /** The drafts of the layer of the works [ids] (every work when null) as their chances: 1, marked so, till the model gives its own. */
    fun seeded(
        store: SourceStore, ids: Collection<String>?, drafts: Map<String, List<LayerDraft>> = if (ids == null) store.drafts.all() else store.drafts.ofItems(ids),
        every: EveryItemFacets? = null,
    ): MadeChances {
        val made = MadeChances()
        drafts.forEach { (id, list) ->
            list.groupBy { it.facet }.forEach { (facet, drafts) ->
                made.getOrPut(id) { HashMap() }[facet] =
                    FewChances(drafts.associateBy { it.key }.values.map { d -> Chance(id, facet, d.key, had = false, chance = 1.0, draftedFrom = d.from) }.toTypedArray())
            }
        }
        // The facets worked out by models of their own: their chances as theirs, before the chain
        ownModels.filter { store.schema.facet(it.facet) != null }.forEach { model ->
            model.chances(store, ids, every).forEach { (id, chances) -> made.getOrPut(id) { HashMap() }[model.facet] = FewChances(chances.values.toTypedArray()) }
        }
        return made
    }

    /**
     * The chances of the [facet] of every work of [data] put among [made] — over the drafts, which
     * keep the model's chance — and, when [compare], the values the model alone gives each (no draft
     * among them): what a refit compares; none made of the chain, which compares nothing.
     *
     * OF EVERY WORK THE SCORES OF THE VALUES IT IS SHOWN WITH ALONE: of those it is told of — its own,
     * the site's examples, the rejected ([SuggestionScores.scores]) — and of the others of those it may
     * have ([SuggestionTask.allowed], one array for every work of the same universes) the few chosen of
     * its row as the suggester hands it over, a row at a time ([SuggestionScores.scores] with a reader),
     * their chances made of them. Every chance of every character of a facet of seven thousand,
     * of every one of tens of thousands of works, was six seconds of a reading.
     */
    fun keep(
        made: MadeChances, facet: FacetDef, data: FacetData, scores: SuggestionScores, compare: Boolean = false, laps: Laps? = null,
    ): Map<String, Set<String>> {
        val given = HashMap<String, Set<String>>()
        val task = data.task
        // The time of its parts: what each work is told of, what it is shown with besides — chosen by the
        // suggester of what it may have — the scores of what it is told of, the chances made, the putting.
        // EVERY WORK AT ONCE: what grows with the works and the values — a row of every value — is the
        // suggester's, a row at a time or on the card; a call of it a chunk of works was 38 of fanfics.me
        // a facet, each sending and taking back
        var at = System.nanoTime()
        val rows = IntArray(data.ids.size) { it }
        val told = Array(rows.size) { toldOf(data, rows[it]) }
        // Neither what it is told of nor what it may not have after all is offered besides
        val closed = rows.mapIndexed { r, i -> (told[r] + task.barred[i]).also { it.sort() } }
        val telling = System.nanoTime() - at
        at = System.nanoTime()
        // What each work is shown with besides, chosen of its row as the suggester hands it over — a row of
        // every value of every work never kept, of the tags thousands of values a work: the likely, and the
        // likeliest of the rest, likely or not, to offer the work at least so many the site does not give
        // ([ModelValues.offered]), chosen of the values by their places. Of a work all named by the user's word, none
        val columns = rows.map { i -> if (data.complete[i]) NONE else task.allowed[i] }
        val open = scores.shown(rows, columns, closed, ModelValues.OFFERED)
        val choosing = System.nanoTime() - at
        at = System.nanoTime()
        val own = scores.scores(rows, told.asList())
        val scoringOwn = System.nanoTime() - at
        at = System.nanoTime()
        // The chances of each work made side by side on every core; put among the chances after, one work after another
        val of = arrayOfNulls<FewChances>(rows.size)
        IntStream.range(0, rows.size).parallel().forEach { r ->
            val i = rows[r]
            of[r] = chancesOf(data.ids[i], facet.key, scores, open[r], told[r], own[r], data, i)
        }
        val making = System.nanoTime() - at
        at = System.nanoTime()
        rows.forEachIndexed { r, i ->
            val id = data.ids[i]
            val chances = of[r]!!
            if (compare && facet.infer) given[id] = ModelValues.given(chances).toSet()
            val byFacet = made.getOrPut(id) { HashMap() }
            val drafts = byFacet[facet.key]?.values?.filter { it.drafted }.orEmpty()
            byFacet[facet.key] = if (drafts.isEmpty()) chances else {
                val out = LinkedHashMap(chances)
                drafts.forEach { d -> out[d.key] = out[d.key]?.copy(draftedFrom = d.draftedFrom) ?: d }
                FewChances(out.values.toTypedArray())
            }
        }
        val putting = System.nanoTime() - at
        laps?.took("told", telling / 1_000_000)
        laps?.took("chosen", choosing / 1_000_000)
        laps?.took("scores of the told", scoringOwn / 1_000_000)
        laps?.took("made", making / 1_000_000)
        laps?.took("put", putting / 1_000_000)
        return given
    }

    /**
     * The values item [i] is told of, each once, in their order: of an item all named by the user's word
     * its own alone; else its own, the site's examples, the values the user rejected.
     */
    private fun toldOf(data: FacetData, i: Int): IntArray {
        val own = data.held[i]
        val out = if (data.complete[i]) own.copyOf() else own + data.task.examples[i] + data.task.rejected[i]
        out.sort()
        return out.distinct().toIntArray()
    }

    /**
     * What the model of the facet reads — of the works [ids] alone, when given, judged among the
     * values the model kept chooses from ([valueKeys]) — with the values the model gives of the
     * [context] facets, [made] before it in the chain.
     */
    fun load(
        store: SourceStore, facet: FacetDef, encoder: TextEncoder, vocabulary: Vocabulary?, ids: Collection<String>?,
        made: WorkChances, valueKeys: List<String>?, read: WorksRead,
    ) = FacetData.load(
        store, facet, encoder, read, vocabulary?.values.orEmpty(),
        modelled = made, valueKeys = valueKeys,
        allowed = vocabulary?.allowed, barred = vocabulary?.barred, groupings = vocabulary?.groupings, examples = vocabulary?.examples,
        lackWeight = vocabulary?.lackWeight ?: 1.0,
        hintNames = vocabulary?.hintNames.orEmpty(), hintAmong = vocabulary?.hintAmong ?: { emptyList() },
    )

    /**
     * What is made of item [i]'s chances: of every value it has, of every value the user rejected
     * (shown crossed out, in its place by its chance), every one the site gives it as an example —
     * offered to answer on whatever its chance, shown with it — and of the values it lacks those it
     * more likely has than not and the likeliest others offered besides; of a facet that says which
     * values a work may have ([allowed]: a work's characters are of its universes, its pairings of
     * its characters) of those alone. Not every value it may have: of a work of a large universe
     * thousands of characters — the user choosing among them gets every one at the asking ([candidates]).
     *
     * Of the [scores]: [told] — the values it is told of ([toldOf]) — scored [own]; [open] what it is
     * shown with besides, of the values it may have ([SuggestionTask.allowed], every value when it says
     * none) — their places among them and their scores.
     */
    private fun chancesOf(
        id: String, facet: String, scores: SuggestionScores, open: Shown, told: IntArray, own: FloatArray, data: FacetData, i: Int,
    ): FewChances {
        val has = data.held[i]
        fun chance(v: Int, score: Float) = Chance(id, facet, data.values[v], v in has, scores.chance(score).toDouble())
        // All named by the user's word: the item has its own values and is offered no other.
        // The site's own are shown to answer on, unlikely as they may be; a value the user rejected keeps its
        // chance: it is shown crossed out, in its place by it
        if (data.complete[i]) return FewChances(Array(told.size) { chance(told[it], own[it]) })
        val allowed = data.task.allowed[i]
        val places = open.places
        // In the order of the values: those told, then the open ones among them
        val values = IntArray(told.size + places.size)
        val of = FloatArray(values.size)
        told.forEachIndexed { k, v ->
            values[k] = v
            of[k] = own[k]
        }
        places.forEachIndexed { k, p ->
            values[told.size + k] = allowed?.get(p) ?: p
            of[told.size + k] = open.scores[k]
        }
        val order = values.indices.sortedBy { values[it] }
        return FewChances(Array(values.size) { chance(values[order[it]], of[order[it]]) })
    }

    companion object {
        /** Items scored at once: memory, not meaning. */
        private const val CHUNK = 2_000

        /** No value asked: of an item all named, the scores of none but its own. */
        private val NONE = IntArray(0)
    }
}
