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

    /**
     * The places of the values [list], each once, in their order; a value the task lacks left out. A primitive array of
     * a few, no list of boxed numbers and no set: asked some eight times a work and a facet of every work of a pass.
     */
    fun of(list: Collection<String>): IntArray {
        if (list.isEmpty()) return NONE
        val out = IntArray(list.size)
        var n = 0
        for (key in list) {
            val place = index[key] ?: continue
            var seen = false
            for (q in 0 until n) if (out[q] == place) seen = true
            if (!seen) out[n++] = place
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    private companion object {
        val NONE = IntArray(0)
    }
}

/**
 * ONE FACET OF SOME WORKS READ FOR ITS MODEL ([FacetData.load]): the works' values of it as the site, the user and the
 * drafts give them, the values chosen from — of the value side of a pass ([ValueSide]) or of the scoring kept — the
 * other facets' values as the context ([TaskContext]), the inputs: the task made of them. Or what the works tell of the
 * value side of a pass ([counted]).
 */
internal class FacetRead(
    private val store: SourceStore,
    private val facet: FacetDef,
    private val encoder: TextEncoder,
    private val read: WorksRead,
    private val asked: FacetAsked,
    private val laps: Laps,
) {
    private val ids = read.ids

    /** The facet's task read now, a step after another, the time of each told to the [laps]. */
    fun data(): FacetData {
        val owned = owned()
        laps.lap("corrected")
        val side = asked.side
        val values = asked.kept?.keys ?: checkNotNull(side) { "${facet.key}: a task of no values chosen from" }.values
        val ownInputs = inputs()
        laps.lap("inputs", ownInputs.size)
        // The site's line of characters and pairings as the way chosen reads it: its inputs and its columns in place of
        // the line's own
        val (rows, cast) = contextOf(ownInputs)
        val context =
            if (side == null) TaskContext.ofKeys(rows) else TaskContext(side.columnsOf(rows), side.contextKeys)
        laps.lap("context", context.keys.size)
        val inputs = cast?.inputs ?: ownInputs
        if (cast != null) laps.lap("cast: ${asked.cast?.asked?.reader?.id}", inputs.size)
        val index = side?.index ?: ValueIndex(values)
        val task = task(values, index, owned, context, inputs, cast?.named)
        laps.lap("task: made")
        val held = held(owned, index)
        laps.lap("task: held")
        return FacetData(
            ids,
            values,
            task,
            held,
            owned.allNamed,
            read.encoded,
            said(index, owned, task, added = true),
            said(index, owned, task, added = false),
        )
    }

    /**
     * WHAT THE WORKS TELL OF THE VALUE SIDE OF A PASS: every value of the facet the site, the drafts, the site's
     * examples or the user give them, the keys of their context — the site's line read without its vectors: its columns
     * are what is counted —, the texts of their lines with vectors.
     */
    fun counted(): PortionCount {
        val owned = owned()
        val values = HashSet<String>()
        owned.corrected.values.forEach { values += it[facet.key].orEmpty() }
        owned.answers.values.forEach { list -> list.forEach { if (it.facet == facet.key) values += it.key } }
        val (rows, _) = contextOf(emptyList())
        return PortionCount(values, rows, asked.cast?.vectors?.keys.orEmpty())
    }

    /**
     * Of every work, the keys of its context — read by the way chosen of its line ([TaskCast]) when the facet reads
     * one, of its [inputs] — and the cast read.
     */
    private fun contextOf(inputs: List<InputBlock>): Pair<List<List<String>>, TaskCast?> {
        val own = TaskContext.keysOf(store, read, asked.modelled, facet.key)
        val cast = asked.cast?.let { TaskCast.of(it, ids, inputs, own) }
        return (cast?.context ?: own) to cast
    }

    /**
     * What each work has of the facet and was answered of it: its values corrected by the user's answers, the answers
     * with what they say of the tags above and under them, its drafts, and whether the user said its values are all
     * named.
     */
    private fun owned(): Owned {
        val corrections = read.corrections
        // Of a facet of tags, the user's answers with what they say of the tags above and under them
        val hierarchy = read.hierarchies[facet.key]
        val answers =
            if (hierarchy == null) corrections else corrections.mapValues { hierarchy.expand(facet.key, it.value) }
        val within = hierarchy?.let { mapOf(facet.key to it) }.orEmpty()
        // THE USER'S WORD ALONE IS WHAT THE MODEL LEARNS (user, 2026-11-19: "the site does not train the model";
        // "the site's markup is the input, the user's markup the output"): of every work the values the user said it
        // has, with what they say of the tags above and under them. What the site gives the work — of the facet
        // itself, its examples of another — and the drafts of the layer
        // ([WorkDrafts][io.github.vlsergey.recommend4me.draft.WorkDrafts]) are inputs of its pairs ([examplesOf])
        val drafted =
            read.drafts
                .mapValues { (_, list) -> list.filter { it.facet == facet.key }.map { it.key } }
                .filterValues { it.isNotEmpty() }
        // Of the works the user answered on alone: of the rest, nothing
        val corrected =
            ids.filter { corrections[it] != null }
                .associateWith { id ->
                    TagHierarchy.corrected(emptyMap(), corrections.getValue(id), within, store.singleFacets)
                }
        // An item whose values of the facet are all named: every other value rejected by the user's word
        val allNamed = BooleanArray(ids.size) { facet.key in read.complete[ids[it]].orEmpty() }
        return Owned(corrected, answers, drafted, allNamed, within)
    }

    /**
     * Of every work the values of the facet the user said it has ([added]) or has not, for the calibration: one answer
     * one value, nothing spread over the values' hierarchy; of a work the user said all named every value it has not —
     * "no other": the noes of the values the user never saw, the low scores, which no single answer reaches.
     */
    private fun said(index: ValueIndex, owned: Owned, task: SuggestionTask, added: Boolean): List<IntArray> =
        ids.mapIndexed { i, id ->
            if (!added && owned.allNamed[i]) task.rejected[i]
            else
                index.of(
                    read.corrections[id].orEmpty().filter { it.facet == facet.key && it.added == added }.map { it.key }
                )
        }

    /**
     * What each work has of the facet and was answered of it: its values [corrected] by the user's answers, the
     * [answers] — with what they say of the tags above and under them — its drafts ([drafted]), whether the user said
     * its values are all named ([allNamed]), and the facet's tags under others ([within]).
     */
    private class Owned(
        val corrected: Map<Long, Facets>,
        val answers: Map<Long, List<FacetCorrection>>,
        val drafted: Map<Long, List<String>>,
        val allNamed: BooleanArray,
        val within: Map<String, TagHierarchy>,
    )

    /**
     * The inputs of the works. A facet the site gives values of has them as its labels: its own line on the site is
     * those values written out, no input of it — as the facet is none of its own context — whether the site gives
     * values of it told of every work, as the model was fitted, not of the works read ([FacetAsked.siteGives]).
     */
    private fun inputs(): List<InputBlock> {
        val ownLine =
            facet.original?.takeIf { asked.siteGives }?.let { TextVectors.blocks(store.id, store.schema)[it]?.first }
        return read.inputs.filter { it.name != ownLine }
    }

    /**
     * The task of the facet's [values] ([index]): EVERY WORK'S ARRAYS MADE SIDE BY SIDE ON EVERY CORE, each of its own
     * work alone, of what is read already — what it has, was answered of ([owned]), may have and may not, is taught by
     * besides — with the names of the values, their groups and texts of the value side of a pass (none of the scoring
     * kept: it knows every value), the [context] and the [inputs]; [named]: of every row, the values the site's line
     * names, a witness among the examples, never a value taught.
     */
    private fun task(
        values: List<String>,
        index: ValueIndex,
        owned: Owned,
        context: TaskContext,
        inputs: List<InputBlock>,
        named: List<List<String>>?,
    ): SuggestionTask {
        val side = asked.side
        val assigned = ofEvery(ids.size) { i -> index.of(owned.corrected[ids[i]]?.get(facet.key).orEmpty()) }
        laps.lap("task: assigned")
        val confirmed = ofEvery(ids.size) { i -> answered(index, owned, i, added = true) }
        // Of a facet of one value a work, the one the user said the work has says it has none other
        val single = facet.key in store.singleFacets
        val rejected =
            ofEvery(ids.size) { i ->
                if (owned.allNamed[i] || (single && assigned[i].isNotEmpty()))
                    assigned[i].toHashSet().let { own -> values.indices.filter { it !in own }.toIntArray() }
                else answered(index, owned, i, added = false)
            }
        laps.lap("task: answered")
        val (allowedOf, barredOf) = limits(index)
        // What the site and the dictionary say of the work, inputs of its pairs: the site's values of the facet, its
        // examples of another facet, the values its line names, the drafts
        val examplesOf =
            ofEvery(ids.size) { i ->
                val id = ids[i]
                index.of(
                    read.site[id]?.get(facet.key).orEmpty() +
                        asked.examples?.invoke(id).orEmpty() +
                        named?.get(i).orEmpty() +
                        owned.drafted[id].orEmpty()
                )
            }
        laps.lap("task: examples")
        return SuggestionTask(
            itemIds = ids.toLongArray(),
            inputs = inputs,
            values = side?.names ?: asked.kept?.names ?: Matrix(values.size, 0),
            valueKeys = values,
            assigned = assigned,
            confirmed = confirmed,
            rejected = rejected,
            context = context.of,
            contextKeys = context.keys,
            allowed = allowedOf,
            barred = barredOf,
            groupings = side?.groupings ?: asked.kept?.groupings.orEmpty(),
            // A value the user said nothing of is no word: of a work not said all named, none learnt of it
            lackWeight = 0.0,
            examples = examplesOf,
            drafts = List(ids.size) { IntArray(0) },
            draftWeight = 0.0,
            hints = side?.hints.orEmpty(),
            hintTexts = side?.hintTexts ?: Matrix(0, 0),
        )
    }

    /**
     * The values of the facet the user confirmed ([added]) or rejected of the work of row [i], by their places, of the
     * [owned] answers.
     */
    private fun answered(index: ValueIndex, owned: Owned, i: Int, added: Boolean): IntArray =
        index.of(owned.answers[ids[i]].orEmpty().filter { it.facet == facet.key && it.added == added }.map { it.key })

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

    /**
     * The values a work has of the facet: the site's own, the user's answers over them — what the site gives of another
     * facet and the drafts are given as drafts ([FacetChain.seeded]), shown and filled as the user's word leaves them.
     */
    private fun held(owned: Owned, index: ValueIndex): List<IntArray> =
        ofEvery(ids.size) { i ->
            val id = ids[i]
            index.of(
                TagHierarchy.corrected(
                        read.site[id].orEmpty(),
                        read.corrections[id].orEmpty(),
                        owned.within,
                        store.singleFacets,
                    )[facet.key]
                    .orEmpty()
            )
        }
}

/**
 * What a portion of the works of a pass tells of the value side of a facet ([FacetRead.counted]): the [values] its
 * works have or were answered of, the keys of every work's [context], in its order, the [castTexts] of their lines with
 * vectors.
 */
internal class PortionCount(val values: Set<String>, val context: List<List<String>>, val castTexts: Set<String>)

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
