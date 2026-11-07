package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.CharactersRead
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import org.slf4j.LoggerFactory
import java.util.stream.IntStream

private val log = LoggerFactory.getLogger(WorksRead::class.java)

/**
 * WHAT EVERY FACET OF A PASS READS ALIKE of the works [only] (every work when null): their values,
 * the user's answers, the drafts, their inputs — read at the first need, once, and handed to every
 * facet of the pass, of which it lives no longer. [imageEncoder] and [sets] (kind to the directions
 * in use): the pictures' and the sets' vectors read.
 */
internal class WorksRead(
    store: SourceStore, encoder: TextEncoder, imageEncoder: String?, sets: Map<SetKind, Long>, val only: Collection<String>?,
    /** The characters of the dictionary's universes, every universe's read once for every facet of the pass. */
    val characters: CharactersRead,
    /** Of every work ([only] null), their facets and answers read already by the reading the pass serves. */
    val every: EveryItemFacets? = null,
    /**
     * The card the inputs are put on as they are read, for every facet of the pass to multiply there
     * ([InputBlock.onCard]) — once, not a facet at a time; freed by [close]. Null: in the main memory alone.
     */
    private val card: Cublas? = null,
) : AutoCloseable {
    init {
        require(every == null || only == null) { "the facets of every work given to a pass of some works" }
    }

    val ids: List<String> by lazy { only?.distinct() ?: store.items.keys().map { it.id } }
    val site: Map<String, Map<String, List<String>>> by lazy {
        every?.site ?: if (only == null) EveryItemFacets.siteOf(store) else store.items.facetsOf(ids)
    }
    val corrections: Map<String, List<FacetCorrection>> by lazy {
        every?.corrections ?: if (only == null) store.corrections.allFacets().groupBy { it.itemId } else store.corrections.facetsOf(ids)
    }
    val complete: Map<String, Set<String>> by lazy { if (only == null) store.corrections.allComplete() else store.corrections.completeOf(ids) }

    /** The user's answers and the facets said whole, as the facets' dictionaries read them ([Answers]): of what the pass read. */
    val answers: Answers by lazy { Answers(corrections, complete) }
    val drafts: Map<String, List<LayerDraft>> by lazy { if (only == null) store.drafts.all() else store.drafts.ofItems(ids) }
    private val inputsRead = lazy { inputs(store, ids, encoder, imageEncoder, sets, only, card) }
    val inputs: List<InputBlock> by inputsRead

    /** The inputs on the card freed: the pass is over, and nothing of it reads them after. */
    override fun close() {
        if (inputsRead.isInitialized()) inputs.forEach { it.onCard?.close() }
    }

    /**
     * THE CONTEXT OF THE WORKS OF A PASS AS NUMBERS: every value of every facet a number of the
     * pass, its name "facet\u0001key" ([names]); of every work, in the order of [ids], of every
     * facet of the source by its place in the schema ([places]), its values' numbers ([of]).
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
        return keys.distinct().map { key -> own.getOrPut(key) { names.add("$facet\u0001$key"); names.size - 1 } }.toIntArray()
    }

    /**
     * THE CONTEXT OF EVERY WORK, MADE ONCE OF THE PASS: of every facet of the source, by work, its
     * values — the site's, the user's answers over them, the tags with those above them, and those
     * the model gives of [modelled] — as a facet's context reads them, each facet's own left out by
     * it ([FacetData.load]). A facet of the chain given its chances ([given]) is made again alone, the
     * others kept: the context of six facets of tens of thousands of works was made whole six times.
     * [modelled] is the chain's one map of the pass, whatever it holds by then.
     */
    fun context(store: SourceStore, modelled: WorkChances): Context {
        context?.let {
            check(modelled === contextOf) { "${store.id}: the context of a pass asked of other chances than it was made of" }
            return it
        }
        val schema = store.schema
        val hierarchies = store.hierarchies()
        val places = schema.facets.withIndex().associate { (k, f) -> f.key to k }
        val of = arrayOfNulls<Array<IntArray?>>(ids.size)
        // Every work's values made side by side on every core; numbered after, a work after another, as they were
        val valuesOf = arrayOfNulls<Map<String, List<String>>>(ids.size)
        IntStream.range(0, ids.size).parallel().forEach { i ->
            val id = ids[i]
            valuesOf[i] = TagHierarchy.corrected(ModelValues.of(schema, site[id].orEmpty(), modelled[id]), corrections[id].orEmpty(), hierarchies, store.singleFacets)
        }
        ids.indices.forEach { i ->
            valuesOf[i]!!
                .forEach { (f, keys) ->
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
     * The [facet] of the chain given its chances in [modelled]: its part of the [context] made again
     * of them — every facet's values made alone of its own site's values, answers and chances — the
     * other facets' kept. Nothing when the context is not made yet: it is made of them at its need.
     */
    fun given(store: SourceStore, facet: String, modelled: WorkChances) {
        val made = context ?: return
        check(modelled === contextOf) { "${store.id}: the context of a pass given other chances than it was made of" }
        val place = made.places[facet] ?: return
        val schema = store.schema
        val hierarchies = store.hierarchies()
        // Every work's values of it made side by side on every core; numbered after, a work after another, as they were
        val keysOf = arrayOfNulls<List<String>>(ids.size)
        IntStream.range(0, ids.size).parallel().forEach { i ->
            val id = ids[i]
            val values = site[id]?.get(facet)?.let { mapOf(facet to it) }.orEmpty()
            val chances = modelled[id]?.get(facet)?.let { mapOf(facet to it) }
            val answers = corrections[id].orEmpty().filter { it.facet == facet }
            keysOf[i] = TagHierarchy.corrected(ModelValues.of(schema, values, chances), answers, hierarchies, store.singleFacets)[facet].orEmpty()
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
 * Every input of the [ids], as the model of the grades reads them: a block of every text
 * that has vectors (the title, the description, the notes, the site's lines), of every
 * picture block, of every set (the reviews, the windows of the chapters, the screenshots).
 * Of the works [only] alone when given, read by their keys; of every work streamed.
 *
 * A BLOCK OF THE WORKS THAT HAVE IT ALONE ([InputBlock]): every vector written as it is read
 * into the rows of its block, a chunk of them after another ([Gathered]), laid out whole once
 * every vector is read; then, given the [card], put there for every facet of the pass.
 */
private fun inputs(
    store: SourceStore, ids: List<String>, encoder: TextEncoder, imageEncoder: String?, sets: Map<SetKind, Long>, only: Collection<String>?,
    card: Cublas?,
): List<InputBlock> {
    val row = ids.withIndex().associate { (i, id) -> id to i }
    val blocks = LinkedHashMap<String, Gathered>()
    fun put(id: String, block: String, v: FloatArray) {
        val i = row[id] ?: return
        blocks.getOrPut(block) { Gathered(ids.size, v.size) }.put(i, v)
    }
    // The texts' and the pictures' vectors as the database keeps them, half precision: their bytes copied as they are
    fun halves(id: String, block: String, bytes: ByteArray) {
        val i = row[id] ?: return
        blocks.getOrPut(block) { Gathered(ids.size, bytes.size / 2) }.putHalves(i, bytes)
    }
    val texts = TextVectors.blocks(store.id, store.schema).mapValues { it.value.first }
    // Where the time goes, of a pass over every work: every kind of vector read, the blocks laid out, put on the card
    val started = System.nanoTime()
    var read = 0
    fun counted(before: Int) = read - before
    val readTexts: Int
    val readPictures: Int
    val readSets: Int
    val afterTexts: Long
    val afterPictures: Long
    if (only == null) {
        store.textVectors.forEachHalves(encoder.id, texts.keys) { id, key, v -> read++; halves(id, texts.getValue(key), v) }
        readTexts = counted(0)
        afterTexts = System.nanoTime()
        imageEncoder?.let { e -> store.pictures.forEachItemHalves(e) { id, block, v -> read++; halves(id, block, v) } }
        readPictures = counted(readTexts)
        afterPictures = System.nanoTime()
        store.setVectors.forEach(sets) { id, block, v -> read++; put(id, block, v) }
    } else {
        store.textVectors.halvesOf(ids, encoder.id).forEach { (id, own) -> own.forEach { (key, v) -> texts[key]?.let { read++; halves(id, it, v) } } }
        readTexts = counted(0)
        afterTexts = System.nanoTime()
        imageEncoder?.let { e -> store.pictures.itemHalvesOf(ids, e).forEach { (id, own) -> own.forEach { (block, v) -> read++; halves(id, block, v) } } }
        readPictures = counted(readTexts)
        afterPictures = System.nanoTime()
        store.setVectors.ofMany(ids, sets).forEach { (id, own) -> own.forEach { (block, v) -> read++; put(id, block, v) } }
    }
    readSets = counted(readTexts + readPictures)
    val afterSets = System.nanoTime()
    val laid = blocks.map { (name, gathered) -> name to gathered.laidOut() }
    val afterLaying = System.nanoTime()
    val out = laid.map { (name, l) -> InputBlock(name, l.first, l.second, card?.let { Gathered.onCard(it, name, l.first, l.second) }) }
    if (ids.size >= LOGGED_INPUTS) log.info(
        "{}: the inputs of {} works, {} MB: the texts' {} vectors read in {} ms, the pictures' {} in {} ms, the sets' {} in {} ms; " +
            "the {} blocks laid out in {} ms{}",
        store.id, ids.size, out.sumOf { it.vectors.rows.toLong() * it.dim * 2 } / (1 shl 20),
        readTexts, (afterTexts - started) / 1_000_000, readPictures, (afterPictures - afterTexts) / 1_000_000, readSets, (afterSets - afterPictures) / 1_000_000,
        out.size, (afterLaying - afterSets) / 1_000_000, if (card == null) "" else ", put on the card in ${(System.nanoTime() - afterLaying) / 1_000_000} ms",
    )
    return out
}

/** The works of a pass from which the time of reading its inputs is told in the log: of a pass over a source, not of a page. */
private const val LOGGED_INPUTS = 1000
