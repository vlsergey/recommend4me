package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.cast.CastAsked
import io.github.vlsergey.recommend4me.cast.SaidCast
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.draft.WorkDrafts
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.CharactersRead
import io.github.vlsergey.recommend4me.universe.LayerDraft
import java.util.stream.IntStream
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(WorksRead::class.java)

/**
 * WHAT EVERY FACET OF THE WORKS [ids] READS ALIKE — a portion of a pass, or the few works of a request
 * ([TaskPortions.MOST] at most, [WorkPasses]): their values, the user's answers, the drafts, the tags under others,
 * their inputs — READ AS THE READING BEGINS, once, and handed to every facet of it, of which it lives no longer: the
 * computation of the facets reads nothing of the database
 * ([DatabaseRefused][io.github.vlsergey.recommend4me.database.DatabaseRefused]). [imageEncoder] and [sets] (kind to the
 * directions in use): the pictures' and the sets' vectors read; [withInputs] false — no vector read, of a reading of
 * the works' values alone ([PassValues]).
 */
internal class WorksRead(
    private val store: SourceStore,
    encoder: TextEncoder,
    imageEncoder: String?,
    sets: Map<SetKind, Long>,
    ids: Collection<Long>,
    /** The characters of the dictionary's universes, every universe's read once for every facet of the operation. */
    val characters: CharactersRead,
    /**
     * The card the inputs are put on as they are read, for every facet of the works to multiply there
     * ([InputBlock.onCard]) — once, not a facet at a time; freed by [close]. Null: in the main memory alone.
     */
    private val card: Cublas? = null,
    withInputs: Boolean = true,
    /** The site's values and the user's answers of the works, read already by the reading this serves; null: read. */
    known: EveryItemFacets? = null,
) : AutoCloseable {
    val ids: List<Long> = ids.distinct()
    val site: Map<Long, Map<String, List<String>>> = known?.site ?: store.items.facetsOf(this.ids)
    val corrections: Map<Long, List<FacetCorrection>> = known?.corrections ?: store.corrections.facetsOf(this.ids)
    val complete: Map<Long, Set<String>> = store.corrections.completeOf(this.ids)

    /**
     * The user's answers and the facets said whole, as the facets' dictionaries read them ([Answers]): of what the
     * reading read.
     */
    val answers: Answers by lazy { Answers(corrections, complete) }
    val drafts: Map<Long, List<LayerDraft>> = WorkDrafts.of(store, this.ids, site)
    val inputs: List<InputBlock> =
        if (withInputs) inputs(store, this.ids, encoder, imageEncoder, sets, card) else emptyList()

    /** The blocks of the texts among the [inputs]: the title, the description, the notes, the site's lines. */
    private val textBlocks = TextVectors.blocks(store.id, store.schema).values.map { it.first }.toSet()

    /**
     * Whether each work of [ids], in their order, has the vector of one of its texts at least. Every work the models of
     * the facets learnt from has its texts' — its title's at least: one with none, captured just now and its texts not
     * encoded yet, is of an input no model ever learnt of, and a model's chances of it are none it can give
     * ([FacetData.judged]).
     */
    val encoded: BooleanArray =
        BooleanArray(ids.size).also { out ->
            inputs.filter { it.name in textBlocks }.forEach { block -> block.rows.forEach { out[it] = true } }
        }

    /** The tags under others of every facet of tags, read once for every facet of the pass. */
    val hierarchies: Map<String, TagHierarchy> = store.hierarchies()

    /**
     * What the bases of the facets of a pass read alike of these works ([FacetBasis]): of a pass alone, read at its
     * first need — the chances of the works never ask for it.
     */
    val bases: WorkBases by lazy { WorkBases(store, encoder, this) }

    /** What the line of each work says, by the source's facet of its entries: read at the first facet that reads it. */
    private val saidCasts = HashMap<String, List<SaidCast>>()

    /**
     * What the line of each work of the pass says ([CastAsked.casts]): read once for the characters, the secondary
     * characters and the pairings alike — before the facet that reads it is worked out.
     */
    fun casts(asked: CastAsked): List<SaidCast> = saidCasts.getOrPut(asked.entries) { asked.casts(store, ids, site) }

    /** The inputs on the card freed: the pass is over, and nothing of it reads them after. */
    override fun close() {
        inputs.forEach { it.onCard?.close() }
    }

    /**
     * THE CONTEXT OF THE WORKS OF A PASS AS NUMBERS: every value of every facet a number of the pass, its name
     * "facet\u0001key" ([names]); of every work, in the order of [ids], of every facet of the source by its place in
     * the schema ([places]), its values' numbers ([of]).
     */
    class Context(val names: List<String>, val places: Map<String, Int>, val of: Array<Array<IntArray?>?>)

    private var context: Context? = null

    /** The model's values the [context] was made of: the chain's own, given more of as it goes ([given]). */
    private var contextOf: WorkChances? = null

    /** Of every facet, the number of each of its values in the [context]. */
    private val numbers = HashMap<String, HashMap<String, Int>>()
    private val names = ArrayList<String>()

    private fun numbersOf(facet: String, keys: List<String>): IntArray? {
        if (keys.isEmpty()) return null
        val own = numbers.getOrPut(facet) { HashMap() }
        return keys
            .distinct()
            .map { key ->
                own.getOrPut(key) {
                    names.add("$facet\u0001$key")
                    names.size - 1
                }
            }
            .toIntArray()
    }

    /**
     * THE CONTEXT OF EVERY WORK, MADE ONCE OF THE READING: of every facet of the source, by work, its values — the
     * site's, the user's answers over them, the tags with those above them, and those the model gives of [modelled] —
     * as a facet's context reads them, each facet's own left out by it ([FacetData.load]). A facet of the chain given
     * its chances ([given]) is made again alone, the others kept: the context of six facets of tens of thousands of
     * works was made whole six times. [modelled] is the chain's one map of the pass, whatever it holds by then.
     */
    fun context(store: SourceStore, modelled: WorkChances): Context {
        context?.let {
            check(modelled === contextOf) {
                "${store.id}: the context of a pass asked of other chances than it was made of"
            }
            return it
        }
        val schema = store.schema
        val places = schema.facets.withIndex().associate { (k, f) -> f.key to k }
        val of = arrayOfNulls<Array<IntArray?>>(ids.size)
        // Every work's values made side by side on every core; numbered after, a work after another, as they were
        val valuesOf = arrayOfNulls<Map<String, List<String>>>(ids.size)
        IntStream.range(0, ids.size).parallel().forEach { i ->
            val id = ids[i]
            valuesOf[i] =
                TagHierarchy.corrected(
                    ModelValues.of(schema, site[id].orEmpty(), modelled[id]),
                    corrections[id].orEmpty(),
                    hierarchies,
                    store.singleFacets,
                )
        }
        ids.indices.forEach { i ->
            valuesOf[i]!!.forEach { (f, keys) ->
                val place = places[f] ?: return@forEach
                val own = numbersOf(f, keys) ?: return@forEach
                (of[i] ?: arrayOfNulls<IntArray>(places.size).also { of[i] = it })[place] = own
            }
        }
        return Context(names, places, of).also {
            context = it
            contextOf = modelled
        }
    }

    /**
     * The [facet] of the chain given its chances in [modelled]: its part of the [context] made again of them — every
     * facet's values made alone of its own site's values, answers and chances — the other facets' kept. Nothing when
     * the context is not made yet: it is made of them at its need.
     */
    fun given(store: SourceStore, facet: String, modelled: WorkChances) {
        val made = context ?: return
        check(modelled === contextOf) { "${store.id}: the context of a pass given other chances than it was made of" }
        val place = made.places[facet] ?: return
        val schema = store.schema
        // Every work's values of it made side by side on every core; numbered after, a work after another, as they were
        val keysOf = arrayOfNulls<List<String>>(ids.size)
        IntStream.range(0, ids.size).parallel().forEach { i ->
            val id = ids[i]
            val values = site[id]?.get(facet)?.let { mapOf(facet to it) }.orEmpty()
            val chances = modelled[id]?.get(facet)?.let { mapOf(facet to it) }
            val answers = corrections[id].orEmpty().filter { it.facet == facet }
            keysOf[i] =
                TagHierarchy.corrected(
                        ModelValues.of(schema, values, chances),
                        answers,
                        hierarchies,
                        store.singleFacets,
                    )[facet]
                    .orEmpty()
        }
        ids.indices.forEach { i ->
            val own = numbersOf(facet, keysOf[i]!!)
            val work = made.of[i]
            if (own == null) work?.set(place, null)
            else (work ?: arrayOfNulls<IntArray>(made.places.size).also { made.of[i] = it })[place] = own
        }
    }
}

/**
 * Every input of the [ids], as the model of the grades reads them: a block of every text that has vectors (the title,
 * the description, the notes, the site's lines), of every picture block, of every set (the reviews, the windows of the
 * chapters, the screenshots), read by their keys.
 *
 * A BLOCK OF THE WORKS THAT HAVE IT ALONE ([InputBlock]): every vector written as it is read into the rows of its
 * block, a chunk of them after another ([Gathered]), laid out whole once every vector is read; then, given the [card],
 * put there for every facet of the pass.
 */
private fun inputs(
    store: SourceStore,
    ids: List<Long>,
    encoder: TextEncoder,
    imageEncoder: String?,
    sets: Map<SetKind, Long>,
    card: Cublas?,
): List<InputBlock> {
    val texts = TextVectors.blocks(store.id, store.schema).mapValues { it.value.first }
    // Where the time goes, of a pass over every work: every kind of vector read, the blocks laid out, put on the card
    val gathered = InputsGathered(ids)
    readOf(store, ids, encoder, texts, imageEncoder, sets, gathered)
    val laid = gathered.blocks.map { (name, g) -> name to g.laidOut() }
    gathered.mark()
    val out = laid.map { (name, l) ->
        InputBlock(name, l.first, l.second, card?.let { Gathered.onCard(it, name, l.first, l.second) })
    }
    if (ids.size >= LOGGED_INPUTS) logInputs(store, ids, out, gathered, card != null)
    return out
}

/**
 * The vectors of the works [ids] gathered into the rows of their blocks as they are read ([Gathered]), and where the
 * time goes: how many were read when each kind of them was done ([mark]).
 */
private class InputsGathered(private val ids: List<Long>) {
    private val row = ids.withIndex().associate { (i, id) -> id to i }
    val blocks = LinkedHashMap<String, Gathered>()
    private var read = 0

    /** How many vectors were read, and the time in nanoseconds, at the start and at every [mark]. */
    val marks = arrayListOf(0 to System.nanoTime())

    /** A vector of the work [id] read, of the [block]. */
    fun put(id: Long, block: String, v: FloatArray) {
        read++
        val i = row[id] ?: return
        blocks.getOrPut(block) { Gathered(ids.size, v.size) }.put(i, v)
    }

    /**
     * A vector of the texts or the pictures read as the database keeps it, half precision: its bytes copied as they
     * are.
     */
    fun halves(id: Long, block: String, bytes: ByteArray) {
        read++
        val i = row[id] ?: return
        blocks.getOrPut(block) { Gathered(ids.size, bytes.size / HALF_BYTES) }.putHalves(i, bytes)
    }

    /** A kind of vector read, or a stage done: how many were read by now, and when. */
    fun mark() {
        marks += read to System.nanoTime()
    }
}

/**
 * The vectors of the works [ids] alone read by their keys, a kind after another: the texts', the pictures' by the
 * [imageEncoder], the sets'.
 */
private fun readOf(
    store: SourceStore,
    ids: List<Long>,
    encoder: TextEncoder,
    texts: Map<String, String>,
    imageEncoder: String?,
    sets: Map<SetKind, Long>,
    gathered: InputsGathered,
) {
    store.textVectors.halvesOf(ids, encoder.id).forEach { (id, own) ->
        own.forEach { (key, v) -> texts[key]?.let { gathered.halves(id, it, v) } }
    }
    gathered.mark()
    imageEncoder?.let { e ->
        store.pictures.itemVectors.itemHalvesOf(ids, e).forEach { (id, own) ->
            own.forEach { (block, v) -> gathered.halves(id, block, v) }
        }
    }
    gathered.mark()
    store.setVectors.ofMany(ids, sets).forEach { (id, own) -> own.forEach { (block, v) -> gathered.put(id, block, v) } }
    gathered.mark()
}

/**
 * The time of reading the inputs of a pass in the log: of every kind of vector, how many were read and in how long; the
 * blocks laid out; put on the card, when [onCard].
 */
private fun logInputs(
    store: SourceStore,
    ids: List<Long>,
    out: List<InputBlock>,
    gathered: InputsGathered,
    onCard: Boolean,
) {
    val marks = gathered.marks
    fun count(k: Int) = marks[k].first - marks[k - 1].first
    fun millis(k: Int) = (marks[k].second - marks[k - 1].second) / NANOS_IN_MILLI
    val laidOut = marks.last().second
    log.info(
        "{}: the inputs of {} works, {} MB: the texts' {} vectors read in {} ms, the pictures' {} in {} ms, " +
            "the sets' {} in {} ms; the {} blocks laid out in {} ms{}",
        store.id,
        ids.size,
        out.sumOf { it.vectors.rows.toLong() * it.dim * HALF_BYTES } / BYTES_IN_MB,
        count(TEXTS),
        millis(TEXTS),
        count(PICTURES),
        millis(PICTURES),
        count(SETS),
        millis(SETS),
        out.size,
        millis(LAID_OUT),
        if (onCard) ", put on the card in ${(System.nanoTime() - laidOut) / NANOS_IN_MILLI} ms" else "",
    )
}

/**
 * The works of a reading from which the time of reading its inputs is told in the log: of a portion of a pass, not of a
 * page.
 */
private const val LOGGED_INPUTS = 1000

/** The bytes of a number in half precision, as the vectors are kept. */
private const val HALF_BYTES = 2

/** Bytes in a megabyte: the size of the inputs, as the log tells it. */
private const val BYTES_IN_MB = 1 shl 20

/** Nanoseconds in a millisecond: the times of the log are told in milliseconds. */
private const val NANOS_IN_MILLI = 1_000_000

/** The mark of the reading ([InputsGathered.marks]) after the texts. */
private const val TEXTS = 1

/** The mark of the reading after the pictures. */
private const val PICTURES = 2

/** The mark of the reading after the sets. */
private const val SETS = 3

/** The mark of the reading after the blocks were laid out. */
private const val LAID_OUT = 4
