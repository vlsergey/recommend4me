package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.encoder.ImageEncoder
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.interest.InterestMark
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Rating
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.setvector.SetVectors
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import java.time.Instant
import kotlin.math.ln1p
import kotlin.math.max
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(CatalogueReader::class.java)

/** What the model reads of an item beside its vectors: no texts, no addresses. */
class CatalogueItem(
    val key: ItemKey,
    val version: String,
    val updatedAt: Instant,
    /** Every categorical feature but the grade of an earlier version, which depends on the grades. */
    val categorical: Set<String>,
    val numeric: Map<String, Float>,
)

/** A grade of an item of a content type. */
class TypedRating(val key: ItemKey, val version: String, val grade: Int, val ratedAt: Instant)

/**
 * WHAT THE AVERAGE WORK OF THE CATALOGUE IS: where the vectors and the numbers of every work lie, and how often it has
 * a categorical feature. Counted as every work is read, or told by a model kept ([ModelMeta.averages]), counted when it
 * was trained. [shares]: a feature not among them is counted of the works read.
 */
class Averages(
    /** The mean and the spread of every block but the likeness to the marks, over the vectors of every work. */
    val vectors: Map<String, VectorSpread>,
    val numbers: Map<String, NumberSpread>,
    val shares: Map<String, Float> = emptyMap(),
)

/**
 * EVERYTHING THE MODEL READS OF EVERY NORMALIZED WORK OF A CONTENT TYPE, loaded in batch for one operation — one pass
 * over each source of each file: the works with their corrected facets and numbers, the grades — and handed to every
 * step that needs it. Nothing of it outlives the operation. Not the user's signals on the sites: they are the user's
 * verdict, the very thing the grades teach.
 *
 * A WORK IS ONE ROW, whatever sites have it: of several items it is made of all of them ([WorkFeatures]), named by the
 * first of them ([WorkClusters.representative]); its grades and its mark are the grades and the mark of that item, as
 * of every item of it.
 *
 * THE VECTORS OF THE WORKS THE TRAINING READS ALONE ([vectors]): the graded, those marked of interest, those linked to
 * them, and any asked for — a few hundred of 27 718 games, each a few thousand numbers. Of every work only what the
 * average work is made of is kept ([averages]); a work's vectors otherwise are read when it is scored, a chunk at a
 * time ([vectorsOfMany]), and let go.
 *
 * OF THE GRADED WORKS ALONE ([CatalogueReader.gradedWorks]): the rows of the grades are all a fitting reads, what the
 * average work is told by the model kept.
 */
class Catalogue(
    val type: TypeStore,
    val items: List<CatalogueItem>,
    val ratings: List<TypedRating>,
    /** Every block but the likeness to the marks, of the works the training reads, by item. */
    val vectors: Map<ItemKey, Map<String, FloatArray>>,
    val likeness: TypeLikeness,
    val works: WorkClusters,
    /** The user's marks of interest. */
    val interest: Map<ItemKey, InterestMark> = emptyMap(),
    val averages: Averages = Averages(emptyMap(), emptyMap()),
    /** The vectors of [keys] read now, as [vectors] holds them: of the works whose vectors are not kept. */
    private val read: (keys: List<ItemKey>) -> Map<ItemKey, Map<String, FloatArray>> = { emptyMap() },
) {
    val byKey: Map<ItemKey, CatalogueItem> = items.associateBy { it.key }

    fun versionOf(key: ItemKey): String? = byKey[key]?.version

    /** The likeness of a work to the marks, [excluded] left out: of a work of several items, the mean of theirs. */
    fun likenessOf(key: ItemKey, excluded: Set<ItemKey> = emptySet()): Map<String, FloatArray> =
        WorkFeatures.vectors(works.members(key).map { likeness.vectors(it, excluded) }.filter { it.isNotEmpty() })

    /** The vectors of a work the training reads, with its likeness to the marks. */
    fun vectorsOf(key: ItemKey): Map<String, FloatArray> = (vectors[key] ?: emptyMap()) + likenessOf(key)

    /** The vectors of [keys] with their likeness to the marks: those kept, the rest read now. */
    fun vectorsOfMany(keys: List<ItemKey>): Map<ItemKey, Map<String, FloatArray>> {
        val missing = keys.filter { it !in vectors }
        val fresh = if (missing.isEmpty()) emptyMap() else read(missing)
        return keys.associateWith { key -> (vectors[key] ?: fresh[key] ?: emptyMap()) + likenessOf(key) }
    }
}

/** Reads a [Catalogue], and the same of single items for the requests of the interface. */
@Component
class CatalogueReader(
    private val plugins: Plugins,
    private val works: Works,
    private val likenesses: Likenesses,
    private val suggestions: FacetSuggestions,
    private val setVectors: SetVectors,
) {

    /**
     * The catalogue of [type]: every work's facets and numbers, the vectors of the works a training reads — the graded,
     * those marked of interest, those linked, and [also] — and the spreads of every work's vectors, counted as they
     * stream past.
     */
    fun read(type: TypeStore, withLikeness: Boolean = true, also: Collection<ItemKey> = emptyList()): Catalogue {
        val started = System.currentTimeMillis()
        val severalSources = type.sources.size > 1
        val items = ArrayList<CatalogueItem>()
        val ratings = ArrayList<TypedRating>()
        val interest = HashMap<ItemKey, InterestMark>()
        // The works a training reads, known before the vectors are: of them alone the vectors are kept
        type.sources.forEach { s ->
            ratings += s.ratings.all().map { TypedRating(ItemKey(s.id, it.itemId), it.version, it.grade, it.ratedAt) }
            s.interest.all().forEach { (id, mark) -> interest[ItemKey(s.id, id)] = mark }
        }
        val clusters = works.of(type)
        val kept =
            HashSet<ItemKey>().apply {
                ratings.forEach { add(it.key) }
                addAll(interest.keys)
                clusters.linkedWorks().forEach(::addAll)
                addAll(also)
            }

        val reading = VectorReading(kept, plugins.textEncoder(), plugins.imageEncoder(), type.embeddings.ids())
        type.sources.forEach { s ->
            val laps = Laps()
            items += readItems(s, severalSources, laps = laps)
            reading.read(s, laps)
            log.info("{}/{}: catalogue read in ms: {}", type.id, s.id, laps)
        }
        val read = System.currentTimeMillis()
        val graded = items.map { it.key }.toSet().let { keys -> ratings.map { it.key }.filter { it in keys }.toSet() }
        val likeness =
            if (withLikeness) likenesses.forTraining(type, reading.center(), graded) else likenesses.stored(type)
        val numbers =
            FeatureLayout.numberSpreads(
                items
                    .filter { clusters.representative(it.key) == it.key }
                    .map { ItemInput(it.key, emptyMap(), emptySet(), it.numeric) }
            )
        val made = System.currentTimeMillis()
        val averages = Averages(reading.spreads.spreads(), numbers)
        return catalogue(type, items, ratings, reading.vectors, likeness, interest, averages).also {
            log.info(
                "{}: catalogue of {} works read of {} items in {} ms, the vectors of {} kept, likeness in {} ms",
                type.id,
                it.items.size,
                items.size,
                read - started,
                it.vectors.size,
                made - read,
            )
        }
    }

    /**
     * THE GRADED WORKS ALONE, each with all its items, and [also]: what a fitting on the rows of the grades reads, and
     * no other work — their facets and numbers read of them, their chances made as their suggesters say they read
     * ([io.github.vlsergey.recommend4me.suggestion.FacetSuggester.reads]), their vectors by their keys. The [averages]
     * are the caller's, of the model it goes by.
     */
    fun gradedWorks(type: TypeStore, averages: Averages, also: Collection<ItemKey> = emptyList()): Catalogue {
        val started = System.currentTimeMillis()
        val ratings =
            type.sources.flatMap { s ->
                s.ratings.all().map { TypedRating(ItemKey(s.id, it.itemId), it.version, it.grade, it.ratedAt) }
            }
        val interest = HashMap<ItemKey, InterestMark>()
        type.sources.forEach { s -> s.interest.all().forEach { (id, mark) -> interest[ItemKey(s.id, id)] = mark } }
        val clusters = works.of(type)
        val wanted = (ratings.map { it.key } + also).flatMapTo(HashSet()) { clusters.members(it) }
        val of = wanted.groupBy({ it.source }, { it.id })
        val items = type.sources.flatMap { s -> of[s.id]?.let { readItems(s, type.sources.size > 1, it) }.orEmpty() }
        val vectors = vectorsOfMany(type, wanted.toList())
        val keys = items.map { it.key }.toSet()
        val likeness = likenesses.asMade(type, ratings.map { it.key }.filter { it in keys }.toSet())
        log.info(
            "{}: {} items of the graded works read in {} ms",
            type.id,
            items.size,
            System.currentTimeMillis() - started,
        )
        return catalogue(type, items, ratings, vectors, likeness, interest, averages)
    }

    /**
     * What was read as the catalogue of the normalized works: a work of several items one row, named by the first of
     * them.
     */
    private fun catalogue(
        type: TypeStore,
        items: List<CatalogueItem>,
        ratings: List<TypedRating>,
        read: Map<ItemKey, Map<String, FloatArray>>,
        likeness: TypeLikeness,
        interest: Map<ItemKey, InterestMark>,
        averages: Averages,
    ): Catalogue {
        val clusters = works.of(type)
        val vectors = HashMap(read)
        val merged = HashMap<ItemKey, CatalogueItem>()
        val itemOf = items.associateBy { it.key }
        clusters.linkedWorks().forEach { members ->
            val own = members.mapNotNull(itemOf::get)
            val name = members.first()
            if (name !in itemOf) return@forEach
            merged[name] = WorkFeatures.item(name, own)
            val joined = WorkFeatures.vectors(members.mapNotNull { vectors[it] }) + setVectors.ofWork(type, members)
            members.forEach(vectors::remove)
            vectors[name] = joined
        }
        val named = items.filter { clusters.representative(it.key) == it.key }.map { merged[it.key] ?: it }
        val keys = named.map { it.key }.toSet()
        return Catalogue(
            type,
            named,
            ratings.filter { it.key in keys },
            vectors,
            likeness,
            clusters,
            interest.filterKeys { it in keys },
            averages = averages,
            read = { wanted -> vectorsOfMany(type, wanted) },
        )
    }

    /**
     * The items of one source as the model reads them: their facets and numbers with the user's corrections, the source
     * when the type has several. Of the items [ids], when given; of every item in one pass over each table.
     */
    private fun readItems(
        s: SourceStore,
        severalSources: Boolean,
        ids: List<String>? = null,
        laps: Laps = Laps(),
    ): List<CatalogueItem> {
        val facetIds = s.schema.facets.filter { it.feature }.associate { it.key to FeatureNames.facetId(s.source, it) }
        // Of every work, every facet and every answer read once, and handed to the chances made of them:
        // a facet not of the model gives no feature below
        val every = if (ids == null) EveryItemFacets.read(s) else null
        val facets: Map<String, Map<String, List<String>>> = every?.site ?: s.items.facetsOf(ids!!)
        laps.lap("facets", facets.size)
        val facetCorrections = every?.corrections ?: s.corrections.facetsOf(ids!!)
        laps.lap("corrections", facetCorrections.size)
        // The values the model gives the items of the facets it works out: made now of them, in one pass
        val chances =
            suggestions.chancesOf(
                s,
                ids,
                s.schema.facets.filter { it.infer && it.feature }.map { it.key },
                laps.within("chances:"),
                every,
            )
        laps.restart()
        val numberDefs = s.schema.numbers.filter { it.feature }.associateBy { it.key }
        val numbers = HashMap<String, HashMap<String, Double>>()
        fun number(id: String, key: String, value: Double) {
            if (key in numberDefs) numbers.getOrPut(id) { HashMap() }[key] = value
        }
        if (ids == null) s.items.forEachNumber(::number)
        else s.items.numbersOf(ids).forEach { (id, own) -> own.forEach { (key, value) -> number(id, key, value) } }
        laps.lap("numbers", numbers.size)
        val fields = if (ids == null) s.corrections.allFields() else s.corrections.fieldsOf(ids)
        // A tag under another gives the work the other too; a tag the user said it has not, none under it
        val hierarchies = s.hierarchies()
        // The tags the user ignores stay with the works, the model reads none of them
        val ignored = s.ignoredTags()
        laps.lap("fields and tag trees")
        val wanted = ids?.toHashSet()
        // Every feature's name one string of the reading, made once however many works have it
        val names = HashMap<String, HashMap<String, String>>()
        fun feature(facetId: String, key: String) =
            names.getOrPut(facetId) { HashMap() }.getOrPut(key) { FeatureNames.facet(facetId, key) }
        val source = "source:${s.id}"
        val made =
            s.items
                .keys()
                .filter { wanted == null || it.id in wanted }
                .map { k ->
                    val base = ModelValues.of(s.schema, facets[k.id].orEmpty(), chances[k.id])
                    val corrected =
                        TagHierarchy.corrected(base, facetCorrections[k.id].orEmpty(), hierarchies, s.singleFacets)
                    val categorical = categoricalOf(corrected, facetIds, ignored, ::feature)
                    if (severalSources) categorical += source
                    val numeric = numericOf(s, numberDefs, numbers[k.id].orEmpty(), fields[k.id].orEmpty())
                    CatalogueItem(ItemKey(s.id, k.id), k.version, k.updatedAt, categorical, numeric)
                }
        laps.lap("items", made.size)
        return made
    }

    /** One item as the model reads it, without its vectors; null when there is no such item. */
    fun item(type: TypeStore, key: ItemKey): CatalogueItem? {
        val s = type.source(key.source) ?: return null
        return s.items.find(key.id)?.let { head ->
            val facetIds =
                s.schema.facets.filter { it.feature }.associate { it.key to FeatureNames.facetId(s.source, it) }
            val wanted = s.schema.facets.filter { it.infer && it.feature }.map { it.key }
            val base =
                ModelValues.of(
                    s.schema,
                    s.items.facets(key.id),
                    suggestions.chancesOf(s, listOf(key.id), wanted)[key.id],
                )
            val ignored = s.ignoredTags()
            val corrected =
                TagHierarchy.corrected(base, s.corrections.facetsOf(key.id), s.hierarchies(), s.singleFacets)
            val categorical = categoricalOf(corrected, facetIds, ignored, FeatureNames::facet)
            if (type.sources.size > 1) categorical += "source:${s.id}"
            val numberDefs = s.schema.numbers.filter { it.feature }.associateBy { it.key }
            val numeric = numericOf(s, numberDefs, s.items.numbers(key.id), s.corrections.fieldsOf(key.id))
            CatalogueItem(key, head.version, head.updatedAt, categorical, numeric)
        }
    }

    /**
     * The work of the item as the model reads it, without its vectors, named by the first of its items; null when there
     * is no such item.
     */
    fun work(type: TypeStore, key: ItemKey): CatalogueItem? {
        val members = works.of(type).members(key).sortedBy { it.toString() }
        if (members.size == 1) return item(type, key)
        val own = members.mapNotNull { item(type, it) }
        return own.takeIf { it.isNotEmpty() }?.let { WorkFeatures.item(it.first().key, it) }
    }

    /**
     * The vectors of the work of the item — texts, pictures, sets — without its likeness to the marks: for a request,
     * of what is kept alone, so of several items the mean of their sets too, the sets of all of them made in a
     * computation ([SetVectors.ofWork]).
     */
    fun vectors(type: TypeStore, key: ItemKey): Map<String, FloatArray> {
        val members = works.of(type).members(key)
        val read = vectorsOfMany(type, members)
        return WorkFeatures.vectors(members.mapNotNull { read[it] })
    }

    /**
     * The vectors of [keys] — texts, pictures, sets — without their likeness to the marks: a query of each kind a
     * source.
     */
    fun vectorsOfMany(type: TypeStore, keys: List<ItemKey>): Map<ItemKey, Map<String, FloatArray>> {
        val out = HashMap<ItemKey, HashMap<String, FloatArray>>()
        val embeddings = type.embeddings.ids()
        keys.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            type.source(source)?.let { s ->
                vectorsOf(s, ids, embeddings) { id, block, v ->
                    out.getOrPut(ItemKey(source, id)) { HashMap() }[block] = v
                }
            }
        }
        return out
    }

    /** The vectors of the items [ids] of the source [s], every one handed to [put]: of texts, of pictures, of sets. */
    private fun vectorsOf(
        s: SourceStore,
        ids: List<String>,
        embeddings: Map<SetKind, Long>,
        put: (id: String, block: String, v: FloatArray) -> Unit,
    ) {
        plugins.textEncoder()?.let { encoder ->
            val blocks = TextVectors.blocks(s.id, s.schema).mapValues { it.value.first }
            s.textVectors.ofMany(ids, encoder.id).forEach { (id, texts) ->
                texts.forEach { (k, v) -> blocks[k]?.let { put(id, it, v) } }
            }
        }
        plugins.imageEncoder()?.let { encoder ->
            s.pictures.itemVectors.itemVectorsOf(ids, encoder.id).forEach { (id, blocks) ->
                blocks.forEach { (b, v) -> put(id, b, v) }
            }
        }
        s.setVectors.ofMany(ids, embeddings).forEach { (id, blocks) -> blocks.forEach { (b, v) -> put(id, b, v) } }
    }

    companion object {
        fun transform(scale: NumberScale, value: Double): Float =
            when (scale) {
                NumberScale.LOG -> ln1p(max(value, 0.0)).toFloat()
                NumberScale.LINEAR -> value.toFloat()
            }

        /** For each rating, the grade of an earlier, different version of the same item. */
        fun previousGradesOfRatings(all: List<TypedRating>): Map<TypedRating, Int> {
            val result = HashMap<TypedRating, Int>()
            all.groupBy { it.key }
                .values
                .forEach { list ->
                    val sorted = list.sortedBy { it.ratedAt }
                    sorted.forEachIndexed { i, rating ->
                        sorted
                            .subList(0, i)
                            .lastOrNull { it.version != rating.version }
                            ?.let { result[rating] = it.grade }
                    }
                }
            return result
        }

        /** For each item, the latest grade of a version other than the current one. */
        fun previousGradesOfCurrentVersions(
            all: List<TypedRating>,
            versionOf: (ItemKey) -> String?,
        ): Map<ItemKey, Int> =
            all.groupBy { it.key }
                .mapNotNull { (key, list) ->
                    val version = versionOf(key) ?: return@mapNotNull null
                    list.filter { it.version != version }.maxByOrNull { it.ratedAt }?.let { key to it.grade }
                }
                .toMap()

        fun inputOf(item: CatalogueItem, vectors: Map<String, FloatArray>, previousGrade: Int?): ItemInput =
            ItemInput(
                item.key,
                vectors,
                if (previousGrade == null) item.categorical else item.categorical + "prev:$previousGrade",
                item.numeric,
            )

        fun rating(s: SourceStore, r: Rating) = TypedRating(ItemKey(s.id, r.itemId), r.version, r.grade, r.ratedAt)
    }
}

/**
 * The categorical features of an item of the facet values [corrected] — the user's word over the site's and the
 * model's: every value of a facet of the model ([facetIds]: facet to its id) but those the user ignores, named by
 * [name].
 */
private fun categoricalOf(
    corrected: Map<String, List<String>>,
    facetIds: Map<String, String>,
    ignored: Map<String, Set<String>>,
    name: (facetId: String, key: String) -> String,
): LinkedHashSet<String> {
    val categorical = LinkedHashSet<String>()
    corrected.forEach { (facet, keys) ->
        val facetId = facetIds[facet] ?: return@forEach
        val unread = ignored[facet]
        keys.forEach { if (unread == null || it !in unread) categorical += name(facetId, it) }
    }
    return categorical
}

/**
 * The numeric features of an item of the source [s]: its [numbers] with the user's [fields] over them, of the numbers
 * of the model ([numberDefs]), each on its scale.
 */
private fun numericOf(
    s: SourceStore,
    numberDefs: Map<String, NumberDef>,
    numbers: Map<String, Double>,
    fields: Map<String, String>,
): Map<String, Float> =
    Corrected.numbers(numbers, fields)
        .mapNotNull { (key, value) ->
            val def = numberDefs[key] ?: return@mapNotNull null
            FeatureNames.number(FeatureNames.numberId(s.source, def)) to CatalogueReader.transform(def.scale, value)
        }
        .toMap()

/**
 * The vectors of a catalogue's items read as they stream past, a source at a time: the spread of every block counted of
 * all of them, the vectors of the works the training reads ([kept]) alone kept, and the mean of the pictures.
 */
private class VectorReading(
    private val kept: Set<ItemKey>,
    private val textEncoder: TextEncoder?,
    private val imageEncoder: ImageEncoder?,
    private val embeddings: Map<SetKind, Long>,
) {
    val vectors = HashMap<ItemKey, HashMap<String, FloatArray>>()
    val spreads = VectorSpreads()
    private val centerSum = imageEncoder?.let { DoubleArray(it.dim) }
    private var pictures = 0

    /** The mean vector of the pictures read; null of none. */
    fun center(): FloatArray? =
        centerSum?.takeIf { pictures > 0 }?.let { sum -> FloatArray(sum.size) { (sum[it] / pictures).toFloat() } }

    /** Every vector of the source [s]: of its texts, its pictures and its sets, each kind's time a lap of [laps]. */
    fun read(s: SourceStore, laps: Laps) {
        var count = 0
        fun put(id: String, block: String, v: FloatArray) {
            count++
            spreads.add(block, v)
            val key = ItemKey(s.id, id)
            if (key in kept) vectors.getOrPut(key) { HashMap() }[block] = v
        }
        if (textEncoder != null) {
            // The title and the site's lines too: every text that has vectors
            val blocks = TextVectors.blocks(s.id, s.schema).mapValues { it.value.first }
            s.textVectors.forEach(textEncoder.id, blocks.keys) { id, key, v -> put(id, blocks.getValue(key), v) }
            laps.lap("text vectors", count)
        }
        if (imageEncoder != null) {
            count = 0
            s.pictures.itemVectors.forEachItemVector(imageEncoder.id) { id, block, v ->
                put(id, block, v)
                if (v.size == centerSum!!.size) {
                    for (k in v.indices) centerSum[k] += v[k].toDouble()
                    pictures++
                }
            }
            laps.lap("picture vectors", count)
        }
        count = 0
        s.setVectors.forEach(embeddings) { id, key, v -> put(id, key, v) }
        laps.lap("set vectors", count)
    }
}
