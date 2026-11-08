package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.TaskCast
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.textvector.TextVectors
import java.util.IdentityHashMap
import java.util.stream.IntStream

/** The values of a work's facets: facet to its values. */
private typealias Facets = Map<String, List<String>>

/** The facet's values chosen from by their places in the task. */
internal class ValueIndex(values: List<String>) {
    private val index = values.withIndex().associate { (i, k) -> k to i }

    /** The place of the value [key]; null of a value the task lacks. */
    operator fun get(key: String) = index[key]

    /** The places of the values [list], each once; a value the task lacks left out. */
    fun of(list: Collection<String>) = list.mapNotNull { index[it] }.distinct().toIntArray()
}

/**
 * ONE FACET OF A PASS READ FOR ITS MODEL ([FacetData.load]): the works' values of it as the site, the user and the
 * drafts give them, the values chosen from, the other facets' values as the context ([TaskContext]), the inputs, the
 * names of the values ([ValueNames]) — the task made of them, with its basis ([FacetBasis]).
 */
internal class FacetRead(
    private val store: SourceStore,
    private val facet: FacetDef,
    private val encoder: TextEncoder,
    private val read: WorksRead,
    private val asked: FacetAsked,
    private val laps: Laps,
) {
    // The rows of the task: every work, or the works [WorksRead.only]; their values alone read
    private val only = read.only
    private val ids = read.ids

    /** The facet's task read now, a step after another, the time of each told to the [laps]. */
    fun data(): FacetData {
        val site = read.site
        val corrections = read.corrections
        laps.lap("works' values")
        // Of a facet of tags, the user's answers with what they say of the tags above and under them
        val hierarchy = store.hierarchies()[facet.key]
        val answers =
            if (hierarchy == null) corrections else corrections.mapValues { hierarchy.expand(facet.key, it.value) }
        val within = hierarchy?.let { mapOf(facet.key to it) }.orEmpty()
        // The examples are the facet's values as far as the model learns, the user's answers over
        // them — the facet the user corrects; the site's facet they come from is among the item's inputs
        // And the drafts of the layer ([LayerDrafts][io.github.vlsergey.recommend4me.draft.LayerDrafts]): values
        // the item has unless the user said otherwise, learnt from with less weight ([FacetData.DRAFT_WEIGHT]) — they
        // are no one's word
        val drafted =
            read.drafts
                .mapValues { (_, list) -> list.filter { it.facet == facet.key }.map { it.key } }
                .filterValues { it.isNotEmpty() }
        val learnt = learnt(site, drafted)
        val corrected = ids.associateWith { id ->
            TagHierarchy.corrected(learnt[id].orEmpty(), corrections[id].orEmpty(), within, store.singleFacets)
        }
        laps.lap("corrected")
        val values = asked.kept ?: chosenFrom(corrected, answers, drafted)
        val ownContext = TaskContext.of(store, read, asked.modelled, facet.key)
        laps.lap("context", ownContext.keys.size)
        val ownInputs = inputs(site)
        laps.lap("inputs", ownInputs.size)
        // The site's line of characters and pairings as the way chosen reads it: its inputs and its columns in place of
        // the line's own
        val cast = asked.cast?.let { TaskCast.of(it, ids, it.casts(store, ids, site), ownInputs, ownContext.keysOf()) }
        val context = cast?.let { TaskContext.ofKeys(it.context, shared = only == null) } ?: ownContext
        val inputs = cast?.inputs ?: ownInputs
        if (cast != null) laps.lap("cast: ${asked.cast.reader.id}", inputs.size)
        // An item whose values of the facet are all named: every other value rejected by the user's word
        val allNamed = BooleanArray(ids.size) { facet.key in read.complete[ids[it]].orEmpty() }
        val index = ValueIndex(values)
        val task = task(values, index, Owned(corrected, answers, drafted, allNamed), context, inputs, cast?.named)
        laps.lap("task: made")
        val held = held(task, index, site, corrections, within, drafted)
        laps.lap("task: held")
        // The task of some works, or of the scoring kept, tells nothing of what the weights were fitted on
        val basis =
            if (only != null || asked.kept != null) FacetData.NO_BASIS
            else
                FacetBasis.of(
                    store,
                    facet.key,
                    encoder,
                    read,
                    values,
                    task.groupings,
                    asked.hintNames,
                    learnt,
                    cast?.signature,
                )
        return FacetData(ids, values, task, held, allNamed, basis)
    }

    /**
     * What each work has of the facet and was answered of it: its values [corrected] by the user's answers, the
     * [answers] — with what they say of the tags above and under them — its drafts ([drafted]), and whether the user
     * said its values are all named ([allNamed]).
     */
    private class Owned(
        val corrected: Map<String, Facets>,
        val answers: Map<String, List<FacetCorrection>>,
        val drafted: Map<String, List<String>>,
        val allNamed: BooleanArray,
    )

    /**
     * The values each work learns from: the site's, and of the facet the site's examples ([FacetAsked.examples]) and
     * the drafts of the layer ([drafted]) besides.
     */
    private fun learnt(site: Map<String, Facets>, drafted: Map<String, List<String>>): Map<String, Facets> {
        val examples = asked.examples
        if (examples == null && drafted.isEmpty()) return site
        return HashMap(site).also { all ->
            ids.forEach { id ->
                val own = examples?.invoke(id).orEmpty() + drafted[id].orEmpty()
                if (own.isNotEmpty())
                    all[id] =
                        HashMap(site[id].orEmpty()).apply {
                            this[facet.key] = (this[facet.key].orEmpty() + own).distinct().toMutableList()
                        }
            }
        }
    }

    /**
     * Every value of the facet the site or the user gave any work read, the values the model kept chooses from, and
     * every value known besides. Of works of a facet whose values a work may have are limited ([FacetAsked.allowed]: a
     * work's characters are of its universes), those they may have and their own alone: no other is chosen from — of a
     * work of two universes some thousand characters, not every one.
     */
    private fun chosenFrom(
        corrected: Map<String, Facets>,
        answers: Map<String, List<FacetCorrection>>,
        drafted: Map<String, List<String>>,
    ): List<String> {
        val everyValue =
            (corrected.values.flatMap { it[facet.key].orEmpty() } +
                    answers.values.flatten().filter { it.facet == facet.key }.map { it.key } +
                    asked.known.keys +
                    asked.valueKeys.orEmpty())
                .distinct()
                .sorted()
        val allowed = asked.allowed
        if (only == null || allowed == null) return everyValue
        val own = HashSet<String>()
        ids.forEach { id ->
            val out = asked.barred?.invoke(id).orEmpty()
            allowed(id).forEach { if (it !in out) own += it }
            own += corrected[id]?.get(facet.key).orEmpty()
            answers[id].orEmpty().filter { it.facet == facet.key }.forEach { own += it.key }
            own += drafted[id].orEmpty()
            own += asked.examples?.invoke(id).orEmpty()
        }
        return everyValue.filter { it in own }
    }

    /**
     * The inputs of the works. A facet the site gives values of has them as its labels: its own line on the site is
     * those values written out, no input of it — as the facet is none of its own context — whether the site gives
     * values of it told of every work, as the model was fitted, not of the works read.
     */
    private fun inputs(site: Map<String, Facets>): List<InputBlock> {
        val siteGives =
            if (only == null) site.values.any { facet.key in it } else store.items.valueUses(facet.key).isNotEmpty()
        val ownLine =
            facet.original?.takeIf { siteGives }?.let { TextVectors.blocks(store.id, store.schema)[it]?.first }
        return read.inputs.filter { it.name != ownLine }
    }

    /**
     * The task of the facet's [values] ([index]): EVERY WORK'S ARRAYS MADE SIDE BY SIDE ON EVERY CORE, each of its own
     * work alone, of what is read already — what it has, was answered of ([owned]), may have and may not, is taught by
     * besides — with the names of the values, their groups, the [context] and the [inputs]; [named]: of every row, the
     * values the site's line names, a witness among the examples, never a value taught.
     */
    private fun task(
        values: List<String>,
        index: ValueIndex,
        owned: Owned,
        context: TaskContext,
        inputs: List<InputBlock>,
        named: List<List<String>>?,
    ): SuggestionTask {
        // Of the scoring kept, no names: it knows every value's coordinates
        val names =
            if (asked.kept != null) Matrix(values.size, 0)
            else
                ValueNames.matrix(
                    store,
                    facet.key,
                    values,
                    index,
                    read.corrections.values.flatten(),
                    asked.known,
                    encoder,
                    asKept = only != null,
                )
        val grouped = asked.groupings?.invoke(values).orEmpty()
        val assigned = ofEvery(ids.size) { i -> index.of(owned.corrected[ids[i]]?.get(facet.key).orEmpty()) }
        laps.lap("task: assigned")
        val (hints, hintTexts) = hints(index)
        val confirmed = ofEvery(ids.size) { i -> answered(index, owned, i, added = true) }
        val rejected =
            ofEvery(ids.size) { i ->
                if (owned.allNamed[i])
                    assigned[i].toHashSet().let { own -> values.indices.filter { it !in own }.toIntArray() }
                else answered(index, owned, i, added = false)
            }
        laps.lap("task: answered")
        val (allowedOf, barredOf) = limits(index)
        val examplesOf =
            ofEvery(ids.size) { i ->
                index.of(asked.examples?.invoke(ids[i]).orEmpty() + named?.get(i).orEmpty())
            }
        val draftsOf = ofEvery(ids.size) { i -> index.of(owned.drafted[ids[i]].orEmpty()) }
        laps.lap("task: examples and drafts")
        return SuggestionTask(
            itemIds = ids,
            inputs = inputs,
            values = names,
            valueKeys = values,
            assigned = assigned,
            confirmed = confirmed,
            rejected = rejected,
            context = context.of,
            contextKeys = context.keys,
            allowed = allowedOf,
            barred = barredOf,
            groupings = grouped,
            lackWeight = asked.lackWeight,
            examples = examplesOf,
            drafts = draftsOf,
            draftWeight = FacetData.DRAFT_WEIGHT,
            hints = hints,
            hintTexts = hintTexts,
        )
    }

    /**
     * The values of the facet the user confirmed ([added]) or rejected of the work of row [i], by their places, of the
     * [owned] answers.
     */
    private fun answered(index: ValueIndex, owned: Owned, i: Int, added: Boolean): IntArray =
        index.of(owned.answers[ids[i]].orEmpty().filter { it.facet == facet.key && it.added == added }.map { it.key })

    /**
     * The texts naming the values and the values each is judged among ([NameHint]): what is learnt from, not what is
     * judged — no names when the item [only] is judged by the weights fitted last. The names of values of the task:
     * every one, but of a dictionary's value the task lacks.
     */
    private fun hints(index: ValueIndex): Pair<List<NameHint>, Matrix> {
        if (only != null || asked.kept != null) return emptyList<NameHint>() to Matrix(0, 0)
        val (hintValues, hintTexts) = ValueNames.texts(store, facet.key, asked.hintNames, encoder)
        val named = hintValues.indices.filter { index[hintValues[it]] != null }
        // Every collection of values the names are judged among made indices once, one array
        // shared by all its names: of a universe's characters thousands of names, each of
        // thousands of rivals
        val made = IdentityHashMap<Collection<String>, IntArray>()
        val hints = named.map { k ->
            val key = hintValues[k]
            val v = index[key]!!
            val of = asked.hintAmong(key)
            val among = made.getOrPut(of) { index.of(of).also { it.sort() } }
            NameHint(v, if (among.binarySearch(v) >= 0) among else (among + v).also { it.sort() })
        }
        return hints to if (named.size == hintValues.size) hintTexts else hintTexts.gather(named.toIntArray())
    }

    /**
     * Of every work, the values it may have ([FacetAsked.allowed]) and those of them it may not after all
     * ([FacetAsked.barred]). One array for every work of the same: of a universe's thousands of characters, made once —
     * the works' sets asked side by side, their arrays made a set at a time.
     */
    private fun limits(index: ValueIndex): Pair<List<IntArray?>, List<IntArray>> {
        val allowedSets = ofEvery(ids.size) { i -> asked.allowed?.invoke(ids[i]) }
        val allowedOf =
            IdentityHashMap<Set<String>, IntArray>().let { made ->
                allowedSets.map { a -> a?.let { made.getOrPut(it) { index.of(it) } } }
            }
        laps.lap("task: allowed")
        val barredOf = ofEvery(ids.size) { i -> asked.barred?.invoke(ids[i])?.let(index::of) ?: IntArray(0) }
        laps.lap("task: barred")
        return allowedOf to barredOf
    }

    /** The item's own values: neither the site's examples nor the drafts. */
    private fun held(
        task: SuggestionTask,
        index: ValueIndex,
        site: Map<String, Facets>,
        corrections: Map<String, List<FacetCorrection>>,
        within: Map<String, TagHierarchy>,
        drafted: Map<String, List<String>>,
    ): List<IntArray> =
        if (asked.examples == null && drafted.isEmpty()) task.assigned
        else
            ofEvery(ids.size) { i ->
                val id = ids[i]
                index.of(
                    TagHierarchy.corrected(site[id].orEmpty(), corrections[id].orEmpty(), within, store.singleFacets)[
                            facet.key]
                        .orEmpty()
                )
            }
}

/**
 * EVERY WORK'S ARRAY MADE SIDE BY SIDE ON EVERY CORE: each of its own work alone, of what is read already — of
 * fanfics.me's 75 630 works a work after another was 650 ms of the six facets. [n]: the works; [make] the array of the
 * work of a row.
 */
private fun <T> ofEvery(n: Int, make: (Int) -> T): List<T> {
    val out = arrayOfNulls<Any>(n)
    IntStream.range(0, n).parallel().forEach { out[it] = make(it) }
    @Suppress("UNCHECKED_CAST")
    return out.asList() as List<T>
}
