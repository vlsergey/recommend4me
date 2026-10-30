package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.interest.InterestMark
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Rating
import io.github.vlsergey.recommend4me.setvector.SetVectors
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.math.ln1p
import kotlin.math.max

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
 * EVERYTHING THE MODEL READS OF EVERY NORMALIZED WORK OF A CONTENT TYPE, loaded in batch for one
 * operation — one pass over each source of each file: the works with their corrected facets and
 * numbers, the grades — and handed to every step that needs it. Nothing of it outlives the operation.
 * Not the user's signals on the sites: they are the user's verdict, the very thing the grades teach.
 *
 * A WORK IS ONE ROW, whatever sites have it: of several items it is made of all of them
 * ([WorkFeatures]), named by the first of them ([WorkClusters.representative]); its grades and its
 * mark are the grades and the mark of that item, as of every item of it.
 *
 * THE VECTORS OF THE WORKS THE TRAINING READS ALONE ([vectors]): the graded, those marked of interest,
 * those linked to them, and any asked for — a few hundred of 27 718 games, each a few thousand
 * numbers. Of every work only what the average work is made of is kept ([spreads]); a work's vectors
 * otherwise are read when it is scored, a chunk at a time ([vectorsOfMany]), and let go.
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
    /** The mean and the spread of every block but the likeness to the marks, over the vectors of every work. */
    val spreads: Map<String, VectorSpread> = emptyMap(),
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
     * The catalogue of [type]: every work's facets and numbers, the vectors of the works a training
     * reads — the graded, those marked of interest, those linked, and [also] — and the spreads of
     * every work's vectors, counted as they stream past.
     */
    fun read(type: TypeStore, withLikeness: Boolean = true, also: Collection<ItemKey> = emptyList()): Catalogue {
        val started = System.currentTimeMillis()
        val textEncoder = plugins.textEncoder()
        val imageEncoder = plugins.imageEncoder()
        val embeddings = type.embeddings.ids()
        val severalSources = type.sources.size > 1
        val items = ArrayList<CatalogueItem>()
        val vectors = HashMap<ItemKey, HashMap<String, FloatArray>>()
        val spreads = VectorSpreads()
        val ratings = ArrayList<TypedRating>()
        val interest = HashMap<ItemKey, InterestMark>()
        val centerSum = imageEncoder?.let { DoubleArray(it.dim) }
        var pictures = 0
        // The works a training reads, known before the vectors are: of them alone the vectors are kept
        type.sources.forEach { s ->
            ratings += s.ratings.all().map { TypedRating(ItemKey(s.id, it.itemId), it.version, it.grade, it.ratedAt) }
            s.interest.all().forEach { (id, mark) -> interest[ItemKey(s.id, id)] = mark }
        }
        val clusters = works.of(type)
        val kept = HashSet<ItemKey>().apply {
            ratings.forEach { add(it.key) }
            addAll(interest.keys)
            clusters.linkedWorks().forEach(::addAll)
            addAll(also)
        }

        type.sources.forEach { s ->
            items += readItems(s, severalSources)
            fun put(id: String, block: String, v: FloatArray) {
                spreads.add(block, v)
                val key = ItemKey(s.id, id)
                if (key in kept) vectors.getOrPut(key) { HashMap() }[block] = v
            }
            if (textEncoder != null) {
                // The title and the site's lines too: every text that has vectors
                val blocks = TextVectors.blocks(s.id, s.schema).mapValues { it.value.first }
                s.textVectors.forEach(textEncoder.id, blocks.keys) { id, key, v -> put(id, blocks.getValue(key), v) }
            }
            if (imageEncoder != null) {
                s.pictures.forEachItemVector(imageEncoder.id) { id, block, v ->
                    put(id, block, v)
                    if (v.size == centerSum!!.size) {
                        for (k in v.indices) centerSum[k] += v[k].toDouble()
                        pictures++
                    }
                }
            }
            s.setVectors.forEach(embeddings) { id, key, v -> put(id, key, v) }
        }
        val read = System.currentTimeMillis()
        val graded = items.map { it.key }.toSet().let { keys -> ratings.map { it.key }.filter { it in keys }.toSet() }
        // A work of several items becomes one row, named by the first of them
        val merged = HashMap<ItemKey, CatalogueItem>()
        val itemOf = items.associateBy { it.key }
        clusters.linkedWorks().forEach { members ->
            val own = members.mapNotNull(itemOf::get)
            val name = members.first()
            if (name !in itemOf) return@forEach
            merged[name] = WorkFeatures.item(name, own)
            val joined = WorkFeatures.vectors(members.mapNotNull { vectors[it] }) + setVectors.ofWork(type, members)
            members.forEach(vectors::remove)
            vectors[name] = HashMap(joined)
        }
        val named = items.filter { clusters.representative(it.key) == it.key }.map { merged[it.key] ?: it }
        val keys = named.map { it.key }.toSet()
        val center = centerSum?.takeIf { pictures > 0 }?.let { sum -> FloatArray(sum.size) { (sum[it] / pictures).toFloat() } }
        val likeness = if (withLikeness) likenesses.forTraining(type, center, graded) else likenesses.stored(type)
        log.info(
            "{}: catalogue of {} works read of {} items in {} ms, the vectors of {} kept, likeness in {} ms",
            type.id, named.size, items.size, read - started, vectors.size, System.currentTimeMillis() - read,
        )
        return Catalogue(
            type, named, ratings.filter { it.key in keys }, vectors, likeness, clusters, interest.filterKeys { it in keys },
            spreads = spreads.spreads(), read = { wanted -> vectorsOfMany(type, wanted) },
        )
    }

    /**
     * The items of one source as the model reads them: their facets and numbers with the user's
     * corrections, the source when the type has several.
     */
    private fun readItems(s: SourceStore, severalSources: Boolean): List<CatalogueItem> {
        val facetIds = s.schema.facets.filter { it.feature }.associate { it.key to FeatureNames.facetId(s.source, it) }
        val facets = HashMap<String, HashMap<String, MutableList<String>>>()
        s.items.forEachFacetValue { id, facet, key ->
            if (facet in facetIds) facets.getOrPut(id) { HashMap() }.getOrPut(facet) { ArrayList() } += key
        }
        val facetCorrections = s.corrections.allFacets().groupBy { it.itemId }
        // The values the model gives the items of the facets it works out: made now of every work, in one pass
        val chances = suggestions.chancesOf(s, null, s.schema.facets.filter { it.infer && it.feature }.map { it.key })
        val numberDefs = s.schema.numbers.filter { it.feature }.associateBy { it.key }
        val numbers = HashMap<String, HashMap<String, Double>>()
        s.items.forEachNumber { id, key, value -> if (key in numberDefs) numbers.getOrPut(id) { HashMap() }[key] = value }
        val fields = s.corrections.allFields()
        // A tag under another gives the work the other too; a tag the user said it has not, none under it
        val hierarchies = s.hierarchies()
        // The tags the user ignores stay with the works, the model reads none of them
        val ignored = s.ignoredTags()
        return s.items.keys().map { k ->
            val categorical = LinkedHashSet<String>()
            val base = ModelValues.of(s.schema, facets[k.id].orEmpty(), chances[k.id])
            TagHierarchy.corrected(base, facetCorrections[k.id].orEmpty(), hierarchies, s.singleFacets).forEach { (facet, keys) ->
                val facetId = facetIds[facet] ?: return@forEach
                val unread = ignored[facet]
                keys.forEach { if (unread == null || it !in unread) categorical += FeatureNames.facet(facetId, it) }
            }
            if (severalSources) categorical += "source:${s.id}"
            val numeric = Corrected.numbers(numbers[k.id].orEmpty(), fields[k.id].orEmpty()).mapNotNull { (key, value) ->
                val def = numberDefs[key] ?: return@mapNotNull null
                FeatureNames.number(FeatureNames.numberId(s.source, def)) to transform(def.scale, value)
            }.toMap()
            CatalogueItem(ItemKey(s.id, k.id), k.version, k.updatedAt, categorical, numeric)
        }
    }

    /** One item as the model reads it, without its vectors; null when there is no such item. */
    fun item(type: TypeStore, key: ItemKey): CatalogueItem? {
        val s = type.source(key.source) ?: return null
        val head = s.items.find(key.id) ?: return null
        val facetIds = s.schema.facets.filter { it.feature }.associate { it.key to FeatureNames.facetId(s.source, it) }
        val categorical = LinkedHashSet<String>()
        val wanted = s.schema.facets.filter { it.infer && it.feature }.map { it.key }
        val base = ModelValues.of(s.schema, s.items.facets(key.id), suggestions.chancesOf(s, listOf(key.id), wanted)[key.id])
        val ignored = s.ignoredTags()
        TagHierarchy.corrected(base, s.corrections.facetsOf(key.id), s.hierarchies(), s.singleFacets).forEach { (facet, keys) ->
            val facetId = facetIds[facet] ?: return@forEach
            val unread = ignored[facet]
            keys.forEach { if (unread == null || it !in unread) categorical += FeatureNames.facet(facetId, it) }
        }
        if (type.sources.size > 1) categorical += "source:${s.id}"
        val numberDefs = s.schema.numbers.filter { it.feature }.associateBy { it.key }
        val numeric = Corrected.numbers(s.items.numbers(key.id), s.corrections.fieldsOf(key.id)).mapNotNull { (k, value) ->
            val def = numberDefs[k] ?: return@mapNotNull null
            FeatureNames.number(FeatureNames.numberId(s.source, def)) to transform(def.scale, value)
        }.toMap()
        return CatalogueItem(key, head.version, head.updatedAt, categorical, numeric)
    }

    /** The work of the item as the model reads it, without its vectors, named by the first of its items; null when there is no such item. */
    fun work(type: TypeStore, key: ItemKey): CatalogueItem? {
        val members = works.of(type).members(key).sortedBy { it.toString() }
        if (members.size == 1) return item(type, key)
        val own = members.mapNotNull { item(type, it) }
        return own.takeIf { it.isNotEmpty() }?.let { WorkFeatures.item(it.first().key, it) }
    }

    /**
     * The vectors of the work of the item — texts, pictures, sets — without its likeness to the marks:
     * for a request, of what is kept alone, so of several items the mean of their sets too, the sets of
     * all of them made in a computation ([SetVectors.ofWork]).
     */
    fun vectors(type: TypeStore, key: ItemKey): Map<String, FloatArray> {
        val members = works.of(type).members(key)
        val read = vectorsOfMany(type, members)
        return WorkFeatures.vectors(members.mapNotNull { read[it] })
    }

    /** The vectors of [keys] — texts, pictures, sets — without their likeness to the marks: a query of each kind a source. */
    fun vectorsOfMany(type: TypeStore, keys: List<ItemKey>): Map<ItemKey, Map<String, FloatArray>> {
        val out = HashMap<ItemKey, HashMap<String, FloatArray>>()
        val embeddings = type.embeddings.ids()
        keys.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            val s = type.source(source) ?: return@forEach
            fun put(id: String, block: String, v: FloatArray) {
                out.getOrPut(ItemKey(source, id)) { HashMap() }[block] = v
            }
            plugins.textEncoder()?.let { encoder ->
                val blocks = TextVectors.blocks(s.id, s.schema).mapValues { it.value.first }
                s.textVectors.ofMany(ids, encoder.id).forEach { (id, texts) -> texts.forEach { (k, v) -> blocks[k]?.let { put(id, it, v) } } }
            }
            plugins.imageEncoder()?.let { encoder ->
                s.pictures.itemVectorsOf(ids, encoder.id).forEach { (id, blocks) -> blocks.forEach { (b, v) -> put(id, b, v) } }
            }
            s.setVectors.ofMany(ids, embeddings).forEach { (id, blocks) -> blocks.forEach { (b, v) -> put(id, b, v) } }
        }
        return out
    }

    companion object {
        fun transform(scale: NumberScale, value: Double): Float = when (scale) {
            NumberScale.LOG -> ln1p(max(value, 0.0)).toFloat()
            NumberScale.LINEAR -> value.toFloat()
        }

        /** For each rating, the grade of an earlier, different version of the same item. */
        fun previousGradesOfRatings(all: List<TypedRating>): Map<TypedRating, Int> {
            val result = HashMap<TypedRating, Int>()
            all.groupBy { it.key }.values.forEach { list ->
                val sorted = list.sortedBy { it.ratedAt }
                sorted.forEachIndexed { i, rating ->
                    sorted.subList(0, i).lastOrNull { it.version != rating.version }?.let { result[rating] = it.grade }
                }
            }
            return result
        }

        /** For each item, the latest grade of a version other than the current one. */
        fun previousGradesOfCurrentVersions(all: List<TypedRating>, versionOf: (ItemKey) -> String?): Map<ItemKey, Int> =
            all.groupBy { it.key }.mapNotNull { (key, list) ->
                val version = versionOf(key) ?: return@mapNotNull null
                list.filter { it.version != version }.maxByOrNull { it.ratedAt }?.let { key to it.grade }
            }.toMap()

        fun inputOf(item: CatalogueItem, vectors: Map<String, FloatArray>, previousGrade: Int?): ItemInput =
            ItemInput(item.key, vectors, if (previousGrade == null) item.categorical else item.categorical + "prev:$previousGrade", item.numeric)

        fun rating(s: SourceStore, r: Rating) = TypedRating(ItemKey(s.id, r.itemId), r.version, r.grade, r.ratedAt)
    }
}
