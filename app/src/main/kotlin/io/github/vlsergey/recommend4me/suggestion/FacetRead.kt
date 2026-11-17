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
        val values = asked.kept ?: checkNotNull(side) { "${facet.key}: a task of no values chosen from" }.values
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
        val held = held(task, index, owned)
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
        // The examples are the facet's values as far as the model learns, the user's answers over
        // them — the facet the user corrects; the site's facet they come from is among the item's inputs
        // And the drafts of the layer ([LayerDrafts][io.github.vlsergey.recommend4me.draft.LayerDrafts]): values
        // the item has unless the user said otherwise, learnt from with less weight ([FacetData.DRAFT_WEIGHT]) — they
        // are no one's word
        val drafted =
            read.drafts
                .mapValues { (_, list) -> list.filter { it.facet == facet.key }.map { it.key } }
                .filterValues { it.isNotEmpty() }
        val learnt = learnt(read.site, drafted)
        val corrected = ids.associateWith { id ->
            TagHierarchy.corrected(learnt[id].orEmpty(), corrections[id].orEmpty(), within, store.singleFacets)
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
        val corrected: Map<String, Facets>,
        val answers: Map<String, List<FacetCorrection>>,
        val drafted: Map<String, List<String>>,
        val allNamed: BooleanArray,
        val within: Map<String, TagHierarchy>,
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
            values = side?.names ?: Matrix(values.size, 0),
            valueKeys = values,
            assigned = assigned,
            confirmed = confirmed,
            rejected = rejected,
            context = context.of,
            contextKeys = context.keys,
            allowed = allowedOf,
            barred = barredOf,
            groupings = side?.groupings.orEmpty(),
            lackWeight = side?.lackWeight ?: 1.0,
            examples = examplesOf,
            drafts = draftsOf,
            draftWeight = FacetData.DRAFT_WEIGHT,
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

    /** The item's own values: neither the site's examples nor the drafts. */
    private fun held(task: SuggestionTask, index: ValueIndex, owned: Owned): List<IntArray> =
        if (asked.examples == null && owned.drafted.isEmpty()) task.assigned
        else
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
