package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.encoder.TextKind
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.vector.Vectors
import java.util.IdentityHashMap
import java.util.stream.IntStream

/**
 * Everything of one facet of a source a suggester is handed, read in one pass per table: every item with its texts in
 * every view and its values, every value with the vector of its name, the values of the item's other facets as its
 * context.
 */
internal class FacetData(
    val ids: List<String>,
    val values: List<String>,
    val task: SuggestionTask,
    /**
     * The values each item has of the facet itself — the site's, the user's answers over them: not the examples the
     * model learns from besides.
     */
    val held: List<IntArray>,
    /** Whether the user said each item's values of the facet are all named: it is given no other. */
    val complete: BooleanArray,
    /** The fingerprint of the whole facet: of every item's; [NO_BASIS] of the task of some works read alone. */
    val basis: Long,
) {
    companion object {
        /**
         * The rule of what is made of an item's chances and read of the others: 2 — the likeliest others offered
         * besides the likely ones; 3 — the rejected ones too; 4 — the site's examples too; 5 — of a facet of limited
         * values, not every value the work may have: the likely and the offered alone; 6 — the chain: a facet reads the
         * values the model gives of the facets before it alone, made in the same pass; 7 — no counts of the values'
         * names in the works' texts: the models of the characters and the pairings fitted again without them.
         */
        private const val KEPT = 7

        /**
         * How much a drafted value the user has not confirmed weighs in what the model learns, against 1 of a value the
         * site or the user gives ([SuggestionTask.draftWeight]): a draft is the dictionary matched to the site's tags —
         * likely, but no one's word.
         */
        const val DRAFT_WEIGHT = 0.5

        /** The basis of the task of one item: none — it is never the basis the weights were fitted on. */
        const val NO_BASIS = 0L

        /** Up to this many values their names' vectors are read by their keys; more, the facet's whole at once. */
        private const val FEW_NAMES = 3000

        /**
         * What an item's chances are made of: every value of it, the user's answers — the facets said to be all named
         * ([complete]) among them — its texts' hashes, its chapters.
         */
        fun fingerprint(
            site: Map<String, List<String>>,
            corrections: List<FacetCorrection>,
            textHashes: Collection<String>,
            windows: Int,
            complete: Collection<String>,
            drafts: Collection<LayerDraft>,
        ): Long =
            Vectors.keyOf(
                site.entries
                    .sortedBy { it.key }
                    .joinToString("\u0001") { (f, keys) -> "$f=" + keys.sorted().joinToString(",") } +
                    "\u0002" +
                    corrections.sortedWith(compareBy({ it.facet }, { it.key })).joinToString("\u0001") {
                        "${it.facet}:${it.key}=${it.added}"
                    } +
                    "\u0002" +
                    textHashes.sorted().joinToString("\u0001") +
                    "\u0002" +
                    windows +
                    // Only when said, only when made: the fingerprints of the items without stay as they were
                    (if (complete.isEmpty()) "" else "\u0002complete=" + complete.sorted().joinToString(",")) +
                    (if (drafts.isEmpty()) ""
                    else "\u0002drafts=" + drafts.map { "${it.facet}:${it.key}" }.sorted().joinToString(","))
            )

        /**
         * [known]: values of the facet the application knows besides the items' — the universes and characters of the
         * dictionary — with the text their names are encoded from. [examples]: the values the site gives an item as
         * examples of the facet, the item's own as far as the model learns ([FacetDef.examplesFrom]); a value it lacks
         * weighs [lackWeight].
         *
         * [read]: the works' own, read once for every facet of a pass. Of the works [WorksRead.only]: THE TASK OF THOSE
         * WORKS ALONE — their rows, judged by the model kept, which reads a task by the keys of its values, its context
         * and its blocks: their inputs, values, context are read of them alone, not of every work; the values chosen
         * from are those the model kept chooses from ([valueKeys]) and their own. No [basis]: what the weights were
         * fitted on is told by the background refresh, which reads every work.
         *
         * [modelled]: the values the model gives of the facets before this one in the chain, made in the same pass, and
         * the drafts of the layer; of the others the site's and the user's alone.
         *
         * [kept]: THE TASK OF THE SCORING KEPT ([FittedSuggester.scoring]) — its values those of the scoring, in its
         * order, without names, groups or texts of them: the scoring knows each; the works' own features alone read, of
         * every work or of the works [only]. No [basis].
         */
        fun load(
            store: SourceStore,
            facet: FacetDef,
            encoder: TextEncoder,
            read: WorksRead,
            known: Map<String, String> = emptyMap(),
            modelled: WorkChances = emptyMap(),
            valueKeys: List<String>? = null,
            allowed: ((String) -> Set<String>)? = null,
            barred: ((String) -> Set<String>)? = null,
            groupings: ((List<String>) -> List<ValueGrouping>)? = null,
            examples: ((String) -> List<String>)? = null,
            lackWeight: Double = 1.0,
            hintNames: Map<String, List<String>> = emptyMap(),
            hintAmong: (String) -> Collection<String> = { emptyList() },
            kept: List<String>? = null,
            laps: Laps = Laps(),
        ): FacetData {
            val schema = store.schema
            // The rows of the task: every work, or the works [WorksRead.only]; their values alone read
            val only = read.only
            val every = read.ids
            val ids = every
            val site = read.site
            val corrections = read.corrections
            laps.lap("works' values")
            // Of a facet of tags, the user's answers with what they say of the tags above and under them
            val hierarchy = store.hierarchies()[facet.key]
            val answers =
                if (hierarchy == null) corrections else corrections.mapValues { hierarchy.expand(facet.key, it.value) }
            val within = hierarchy?.let { mapOf(facet.key to it) }.orEmpty()
            val complete = read.complete
            // The examples are the facet's values as far as the model learns, the user's answers over
            // them — the facet the user corrects; the site's facet they come from is among the item's inputs
            // And the drafts of the layer ([LayerDrafts][io.github.vlsergey.recommend4me.draft.LayerDrafts]): values
            // the item
            // has unless the user said otherwise, learnt from with less weight ([DRAFT_WEIGHT]) — they are no one's
            // word
            val drafts = read.drafts
            val drafted =
                drafts
                    .mapValues { (_, list) -> list.filter { it.facet == facet.key }.map { it.key } }
                    .filterValues { it.isNotEmpty() }
            val learnt =
                if (examples == null && drafted.isEmpty()) site
                else
                    HashMap(site).also { all ->
                        every.forEach { id ->
                            val own = examples?.invoke(id).orEmpty() + drafted[id].orEmpty()
                            if (own.isNotEmpty())
                                all[id] =
                                    HashMap(site[id].orEmpty()).apply {
                                        this[facet.key] = (this[facet.key].orEmpty() + own).distinct().toMutableList()
                                    }
                        }
                    }
            val corrected = every.associateWith { id ->
                TagHierarchy.corrected(learnt[id].orEmpty(), corrections[id].orEmpty(), within, store.singleFacets)
            }
            laps.lap("corrected")

            // Every value of the facet the site or the user gave any work read, the values the model kept
            // chooses from, and every value known besides
            val everyValue =
                (corrected.values.flatMap { it[facet.key].orEmpty() } +
                        answers.values.flatten().filter { it.facet == facet.key }.map { it.key } +
                        known.keys +
                        valueKeys.orEmpty())
                    .distinct()
                    .sorted()
            // Of works of a facet whose values a work may have are limited ([allowed]: a work's
            // characters are of its universes), those they may have and their own alone: no other is
            // chosen from — of a work of two universes some thousand characters, not every one
            val values =
                if (kept != null) kept
                else if (only == null || allowed == null) everyValue
                else {
                    val own = HashSet<String>()
                    every.forEach { id ->
                        val out = barred?.invoke(id).orEmpty()
                        allowed(id).forEach { if (it !in out) own += it }
                        own += corrected[id]?.get(facet.key).orEmpty()
                        answers[id].orEmpty().filter { it.facet == facet.key }.forEach { own += it.key }
                        own += drafted[id].orEmpty()
                        own += examples?.invoke(id).orEmpty()
                    }
                    everyValue.filter { it in own }
                }
            val index = values.withIndex().associate { (i, k) -> k to i }

            // The other facets' values as the context — of the facets the source has now, the site's own among them.
            // THE CHAIN: of the facets before this one, the values the model gives the item besides the
            // site's and the user's, made in the same pass ([modelled] holds those alone, and the drafts);
            // the tags with those above them
            // Made once of the pass ([WorksRead.context]): the facet's own values, which alone the examples
            // and the drafts of it add to, left out
            val hierarchies = store.hierarchies()
            val facets = schema.facets.map { it.key }.toSet()
            val made = read.context(store, modelled)
            val own = made.places[facet.key]
            // Every work's values of the other facets by their numbers of the pass, each facet's apart from the others'
            val contextOf =
                ids.indices.map { i ->
                    val work = made.of[i] ?: return@map IntArray(0)
                    var n = 0
                    work.forEachIndexed { k, part -> if (k != own && part != null) n += part.size }
                    val out = IntArray(n)
                    var at = 0
                    work.forEachIndexed { k, part ->
                        if (k == own || part == null) return@forEachIndexed
                        part.copyInto(out, at)
                        at += part.size
                    }
                    out
                }
            // A value of the context only one item of the fitting has teaches nothing of the others: no
            // column of it — of a catalogue of games, the developers of one game each, the operating
            // systems and languages written by hand once. An item judged alone ([only]) is read by the
            // columns of the layout fitted last: every value of its own kept
            val shared =
                if (only != null) null
                else IntArray(made.names.size).also { n -> contextOf.forEach { keys -> keys.forEach { n[it]++ } } }
            // The columns of the task by the values' numbers, in the order the works first have them
            val column = IntArray(made.names.size) { -1 }
            val contextKeys = ArrayList<String>()
            val context = contextOf.map { keys ->
                var n = 0
                val out = IntArray(keys.size)
                keys.forEach { key ->
                    if (shared != null && shared[key] <= 1) return@forEach
                    if (column[key] < 0) {
                        column[key] = contextKeys.size
                        contextKeys += made.names[key]
                    }
                    out[n++] = column[key]
                }
                if (n == out.size) out else out.copyOf(n)
            }
            laps.lap("context", contextKeys.size)

            val d = encoder.dim
            // A facet the site gives values of has them as its labels: its own line on the site is
            // those values written out, no input of it — as the facet is none of its own context
            // — whether the site gives values of it told of every work, as the model was fitted, not of the works read
            val siteGives =
                if (only == null) site.values.any { facet.key in it } else store.items.valueUses(facet.key).isNotEmpty()
            val ownLine = facet.original?.takeIf { siteGives }?.let { TextVectors.blocks(store.id, schema)[it]?.first }
            val inputs = read.inputs.filter { it.name != ownLine }
            laps.lap("inputs", inputs.size)

            // Of the scoring kept, no names: it knows every value's coordinates
            val names = Matrix(values.size, if (kept != null) 0 else d)
            if (kept == null)
                nameVectors(
                        store,
                        facet.key,
                        values,
                        corrections.values.flatten(),
                        known,
                        encoder,
                        asKept = only != null,
                    )
                    .forEach { (key, v) ->
                        index[key]?.let { names.held.put(v, 0, names.row(it), d) }
                    }

            fun indices(list: Collection<String>) = list.mapNotNull { index[it] }.distinct().toIntArray()

            // EVERY WORK'S ARRAYS MADE SIDE BY SIDE ON EVERY CORE: each of its own work alone, of what is read
            // already — of fanfics.me's 75 630 works a work after another was 650 ms of the six facets
            fun <T> ofEvery(make: (Int) -> T): List<T> {
                val out = arrayOfNulls<Any>(ids.size)
                IntStream.range(0, ids.size).parallel().forEach { out[it] = make(it) }
                @Suppress("UNCHECKED_CAST")
                return out.asList() as List<T>
            }
            val grouped = groupings?.invoke(values).orEmpty()
            // An item whose values of the facet are all named: every other value rejected by the user's word
            val allNamed = BooleanArray(ids.size) { facet.key in complete[ids[it]].orEmpty() }
            val assigned = ofEvery { i -> indices(corrected[ids[i]]?.get(facet.key).orEmpty()) }
            laps.lap("task: assigned")
            // What is learnt from, not what is judged: no names when the item [only] is judged by the weights fitted
            // last
            val (hintValues, hintTexts) =
                if (only != null || kept != null) emptyList<String>() to Matrix(0, 0)
                else hints(store, facet.key, hintNames, encoder)
            // The names of values of the task: every one, but of a dictionary's value the task lacks
            val named = hintValues.indices.filter { index[hintValues[it]] != null }
            val confirmed = ofEvery { i ->
                indices(answers[ids[i]].orEmpty().filter { it.facet == facet.key && it.added }.map { it.key })
            }
            val rejected = ofEvery { i ->
                if (allNamed[i])
                    assigned[i].toHashSet().let { own -> values.indices.filter { it !in own }.toIntArray() }
                else indices(answers[ids[i]].orEmpty().filter { it.facet == facet.key && !it.added }.map { it.key })
            }
            laps.lap("task: answered")
            // One array for every work of the same: of a universe's thousands of characters, made once — the
            // works' sets asked side by side, their arrays made a set at a time
            val allowedSets = ofEvery { i -> allowed?.invoke(ids[i]) }
            val allowedOf =
                IdentityHashMap<Set<String>, IntArray>().let { made ->
                    allowedSets.map { a -> a?.let { made.getOrPut(it) { indices(it) } } }
                }
            laps.lap("task: allowed")
            val barredOf = ofEvery { i -> barred?.invoke(ids[i])?.let(::indices) ?: IntArray(0) }
            laps.lap("task: barred")
            val examplesOf = ofEvery { i -> examples?.invoke(ids[i])?.let(::indices) ?: IntArray(0) }
            val draftsOf = ofEvery { i -> indices(drafted[ids[i]].orEmpty()) }
            laps.lap("task: examples and drafts")
            val task =
                SuggestionTask(
                    itemIds = ids,
                    inputs = inputs,
                    values = names,
                    valueKeys = values,
                    assigned = assigned,
                    confirmed = confirmed,
                    rejected = rejected,
                    context = context,
                    contextKeys = contextKeys,
                    allowed = allowedOf,
                    barred = barredOf,
                    groupings = grouped,
                    lackWeight = lackWeight,
                    examples = examplesOf,
                    drafts = draftsOf,
                    draftWeight = DRAFT_WEIGHT,
                    hints =
                        run {
                            // Every collection of values the names are judged among made indices once, one array
                            // shared by all its names: of a universe's characters thousands of names, each of
                            // thousands of rivals
                            val made = IdentityHashMap<Collection<String>, IntArray>()
                            named.map { k ->
                                val key = hintValues[k]
                                val v = index.getValue(key)
                                val of = hintAmong(key)
                                val among = made.getOrPut(of) { indices(of).also { it.sort() } }
                                NameHint(v, if (among.binarySearch(v) >= 0) among else (among + v).also { it.sort() })
                            }
                        },
                    hintTexts = if (named.size == hintValues.size) hintTexts else hintTexts.gather(named.toIntArray()),
                )
            laps.lap("task: made")
            // The item's own values: neither the site's examples nor the drafts
            val held =
                if (examples == null && drafted.isEmpty()) task.assigned
                else
                    ofEvery { i ->
                        val id = ids[i]
                        indices(
                            TagHierarchy.corrected(
                                    site[id].orEmpty(),
                                    corrections[id].orEmpty(),
                                    within,
                                    store.singleFacets,
                                )[facet.key]
                                .orEmpty()
                        )
                    }
            laps.lap("task: held")
            // The task of some works, or of the scoring kept, tells nothing of what the weights were fitted on
            if (only != null || kept != null) return FacetData(ids, values, task, held, allNamed, NO_BASIS)
            val hashes = HashMap<String, MutableList<String>>()
            store.textVectors.hashes(encoder.id).forEach { (k, hash) ->
                hashes.getOrPut(k.first) { ArrayList() } += hash
            }
            val windowCounts = store.parts.windowCounts(encoder.id)
            val fingerprints =
                LongArray(ids.size) { i ->
                    val id = ids[i]
                    fingerprint(
                        site[id].orEmpty(),
                        corrections[id].orEmpty(),
                        hashes[id].orEmpty(),
                        windowCounts[id] ?: 0,
                        complete[id].orEmpty(),
                        drafts[id].orEmpty(),
                    )
                }
            // The values chosen from and their groups are part of what is learnt: a universe added or
            // removed, a sex learnt of a character, fits again
            // What is kept of the chances is part of it too: a new rule of what to keep makes them again
            // And the names the values go by: an alias added to the dictionary is a new text to learn from
            val basis =
                Vectors.keyOf(
                    ids.indices.sortedBy { ids[it] }.joinToString(",") { "${ids[it]}:${fingerprints[it]}" } +
                        "\u0002" +
                        values.joinToString("\u0001") +
                        "\u0002" +
                        grouped.joinToString("\u0001") { g ->
                            g.name +
                                "=" +
                                g.of.joinToString(",") { groups -> groups.joinToString("+") { g.groups[it] } }
                        } +
                        "\u0002" +
                        KEPT +
                        "\u0002" +
                        Vectors.keyOf(
                            hintNames.entries
                                .sortedBy { it.key }
                                .joinToString("\u0001") { (v, names) -> v + "=" + names.joinToString("|") }
                        ) +
                        // The tags under others: a tag put under another is learnt again
                        "\u0002" +
                        (hierarchy?.signature() ?: "") +
                        // The other facets' values as the site and the user give them: one of them changed is learnt
                        // from again. NOT WHAT THE MODEL GAVE of the other facets: each refit of one changes what the
                        // next reads, the circle never settles, and every start would fit every facet again for
                        // nothing new. The model's values are read as they are when the chances are made
                        "\u0002" +
                        Vectors.keyOf(
                            ids.indices
                                .sortedBy { ids[it] }
                                .joinToString(",") { i ->
                                    val id = ids[i]
                                    id +
                                        ":" +
                                        TagHierarchy.corrected(
                                                ModelValues.of(schema, learnt[id].orEmpty(), null),
                                                corrections[id].orEmpty(),
                                                hierarchies,
                                                store.singleFacets,
                                            )
                                            .filterKeys { it != facet.key && it in facets }
                                            .entries
                                            .sortedBy { it.key }
                                            .joinToString(";") { (f, keys) ->
                                                f + "=" + keys.sorted().joinToString("+")
                                            }
                                }
                        )
                )
            return FacetData(ids, values, task, held, allNamed, basis)
        }

        /**
         * The vectors of the values' names as queries — of [known]'s texts for those it has — encoded now where missing
         * or the name changed. [asKept]: of works read by the model kept, the vector kept of a value is its vector
         * whatever its name now — the one the model was fitted with; a renamed value is encoded again at the next
         * fitting, not at every reading.
         */
        private fun nameVectors(
            store: SourceStore,
            facet: String,
            values: List<String>,
            corrections: List<FacetCorrection>,
            known: Map<String, String>,
            encoder: TextEncoder,
            asKept: Boolean = false,
        ): Map<String, FloatArray> {
            // The names [known] gives are the dictionary's: the source asked of the others alone
            val names =
                store.names(facet, values.filter { it !in known }) +
                    corrections.filter { it.facet == facet && it.name != null }.associate { it.key to it.name!! } +
                    known
            // Of a few values — one item's — those alone read; of every value, the facet's whole
            val stored = store.valueNames.of(facet, encoder.id, values.takeIf { it.size <= FEW_NAMES })
            val out = HashMap<String, FloatArray>()
            val todo = ArrayList<Pair<String, String>>()
            values.forEach { key ->
                val name = names[key] ?: key
                val have = stored[key]
                if (have != null && (asKept || have.first == Vectors.hash(name))) out[key] = have.second
                else todo += key to name
            }
            todo.chunked(ENCODE_BATCH).forEach { batch ->
                val vectors = encoder.encode(batch.map { it.second }, TextKind.QUERY)
                val rows =
                    batch.indices.mapNotNull { k ->
                        vectors[k]?.let { Triple(batch[k].first, Vectors.hash(batch[k].second), it) }
                    }
                store.valueNames.save(facet, encoder.id, rows)
                rows.forEach { (key, _, v) -> out[key] = v }
            }
            return out
        }

        /**
         * Every name of every value as a text of a work is encoded — a passage, not a query — each with the value it
         * names; kept with the source's vectors of names under "hint:<facet>", encoded again only when new. The value
         * of every name, and the names' vectors as the rows of one matrix, read into it as they are streamed: thousands
         * of names of a thousand numbers each are not held twice. A name the encoder gave no vector is left out.
         */
        private fun hints(
            store: SourceStore,
            facet: String,
            names: Map<String, List<String>>,
            encoder: TextEncoder,
        ): Pair<List<String>, Matrix> {
            if (names.isEmpty()) return emptyList<String>() to Matrix(0, 0)
            val kept = "hint:$facet"
            val wanted = names.flatMap { (value, list) -> list.distinct().map { value to it } }
            val at = wanted.withIndex().associate { (k, w) -> "${w.first}\u0001${w.second}" to k }
            val hashes = wanted.map { Vectors.hash(it.second) }
            val texts = Matrix(wanted.size, encoder.dim)
            val have = BooleanArray(wanted.size)
            fun put(k: Int, v: FloatArray) {
                if (v.size != texts.cols) return
                texts.held.put(v, 0, texts.row(k), texts.cols)
                have[k] = true
            }
            store.valueNames.forEach(kept, encoder.id) { key, hash, v ->
                at[key]?.takeIf { hashes[it] == hash }?.let { put(it, v) }
            }
            wanted.indices
                .filter { !have[it] }
                .chunked(ENCODE_BATCH)
                .forEach { batch ->
                    val vectors = encoder.encode(batch.map { wanted[it].second }, TextKind.PASSAGE)
                    val rows =
                        batch.indices.mapNotNull { j ->
                            vectors[j]?.let {
                                Triple(
                                    "${wanted[batch[j]].first}\u0001${wanted[batch[j]].second}",
                                    hashes[batch[j]],
                                    it,
                                )
                            }
                        }
                    store.valueNames.save(kept, encoder.id, rows)
                    batch.indices.forEach { j -> vectors[j]?.let { put(batch[j], it) } }
                }
            val rows = wanted.indices.filter { have[it] }
            return rows.map { wanted[it].first } to
                if (rows.size == wanted.size) texts else texts.gather(rows.toIntArray())
        }

        /** Names encoded in one call: memory, not meaning. */
        private const val ENCODE_BATCH = 64
    }
}
