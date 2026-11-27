package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.interest.InterestMark
import io.github.vlsergey.recommend4me.item.EveryItemFacets
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemKeys
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.likeness.Likenesses
import io.github.vlsergey.recommend4me.likeness.TypeLikeness
import io.github.vlsergey.recommend4me.manner.TypeManners
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Rating
import io.github.vlsergey.recommend4me.series.ItemSeries
import io.github.vlsergey.recommend4me.setvector.SetKind
import io.github.vlsergey.recommend4me.setvector.SetVectors
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.suggestion.Chance
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.ListModelValues
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
 * WHAT THE MODEL READS OF SOME NORMALIZED WORKS OF A CONTENT TYPE, loaded in batch for one operation and handed to
 * every step that needs it: the works with their corrected facets and numbers, the grades. Nothing of it outlives the
 * operation. Not the user's signals on the sites: they are the user's verdict, the very thing the grades teach.
 *
 * NEVER EVERY WORK AT ONCE: a type of a million works — ficbook's dump — held whole is more than the memory
 * (2026-11-17). A training reads the works it learns of alone — the graded, those marked of interest
 * ([EveryWork.training], [CatalogueReader.gradedWorks]) — and what the average work is ([averages]), counted of every
 * work as it streams past ([AverageCount]) by the last pass of the expectations and kept ([AverageWorkRepository]); a
 * pass of the expectations reads every work a portion at a time ([EveryWork.ofKeys], [WorkPredictions]).
 *
 * A WORK IS ONE ROW, whatever sites have it: of several items it is made of all of them ([WorkFeatures]), named by the
 * first of them ([WorkClusters.representative]); its grades and its mark are the grades and the mark of that item, as
 * of every item of it.
 *
 * THE VECTORS OF THE WORKS READ FOR A TRAINING ([vectors]), each a few thousand numbers; of a portion's works they are
 * read when it is scored, a chunk at a time ([vectorsOfMany]), and let go.
 *
 * THE MANNERS OF THE WORKS ([TypeManners]) — its own, its authors', its series', each kept — are read with the works
 * and handed to every work's vectors with them.
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
    private val lists: ListIndexes,
) {

    /**
     * THE GRADED WORKS ALONE, each with all its items, and [also]: what a fitting on the rows of the grades reads, and
     * no other work — their facets and numbers read of them, the values the models gave them as the index of the list
     * keeps them — written there of the rated works before the models of the grades are made (owner, 2026-11-19: the
     * second stage of four, [docs/computations.md]) —, their vectors by their keys. The [averages] are the caller's, of
     * the model it goes by; the likeness to the marks as made for the model kept.
     */
    fun gradedWorks(type: TypeStore, averages: Averages, also: Collection<ItemKey> = emptyList()): Catalogue =
        gradedWorks(type, averages, also, ratingsOf(type), interestOf(type)) { likenesses.asMade(type, it) }

    /**
     * [gradedWorks] of the [ratings] and the marks of [interest] read, the likeness to the marks made of the graded.
     */
    internal fun gradedWorks(
        type: TypeStore,
        averages: Averages,
        also: Collection<ItemKey>,
        ratings: List<TypedRating>,
        interest: Map<ItemKey, InterestMark>,
        likenessOf: (graded: Set<ItemKey>) -> TypeLikeness,
    ): Catalogue {
        val started = System.currentTimeMillis()
        val clusters = works.of(type)
        val wanted = (ratings.map { it.key } + also).flatMapTo(HashSet()) { clusters.members(it) }
        val of = wanted.groupBy({ it.source }, { it.id })
        val series = ItemSeries.of(type, wanted)
        val index = lists.of(type.id)
        val items =
            type.sources.flatMap { s ->
                of[s.id]
                    ?.toHashSet()
                    ?.let { ids ->
                        val kept = index.written { ListModelValues.of(it, s.id, ids) }
                        readItems(s, type.sources.size > 1, series, s.items.keysOf(ids), kept = kept)
                    }
                    .orEmpty()
            }
        val vectors = TypeManners.of(type, clusters, wanted).with(vectorsOfMany(type, wanted.toList()), wanted)
        val keys = items.map { it.key }.toSet()
        val likeness = likenessOf(ratings.map { it.key }.filter { it in keys }.toSet())
        log.info(
            "{}: {} items of the graded works read in {} ms",
            type.id,
            items.size,
            System.currentTimeMillis() - started,
        )
        return catalogue(type, items, ratings, vectors, likeness, interest, averages, clusters)
    }

    /**
     * What was read as the catalogue of the normalized works: a work of several items one row, named by the first of
     * them.
     */
    internal fun catalogue(
        type: TypeStore,
        items: List<CatalogueItem>,
        ratings: List<TypedRating>,
        read: Map<ItemKey, Map<String, FloatArray>>,
        likeness: TypeLikeness,
        interest: Map<ItemKey, InterestMark>,
        averages: Averages,
        /** Which items are one work, as the operation read it. */
        clusters: WorkClusters,
        /** The manners of every work, when read; else those of the works read later read with them. */
        manners: TypeManners? = null,
        /** Told of every vector of an item read later, as it is read. */
        seen: VectorSeen? = null,
        /**
         * The sets of the works of several items made of all of them; none when the vectors of the works are not read —
         * a pass that counts the works and reads their vectors otherwise ([EveryWork.averageWork]).
         */
        sets: Boolean = true,
    ): Catalogue {
        val vectors = HashMap(read)
        val merged = HashMap<ItemKey, CatalogueItem>()
        val itemOf = items.associateBy { it.key }
        val linked = clusters.linkedWorks().filter { it.first() in itemOf }
        // The sets of every work of several items read at once, not a reading a work
        val made = if (sets) setVectors.ofWorks(type, linked) else List(linked.size) { emptyMap() }
        val joins = WorkFeatures.joins(type.sources.map { it.source })
        linked.forEachIndexed { w, members ->
            // Named as the catalogue names it, by its representative — not by the item that says the most, listed
            // first: a work named otherwise was read of its representative's vectors alone, those counted twice
            val name = clusters.representative(members.first())
            merged[name] = WorkFeatures.item(name, members.mapNotNull(itemOf::get), joins)
            val joined = WorkFeatures.vectors(members.mapNotNull { vectors[it] }) + made[w]
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
            read = { wanted ->
                (manners ?: TypeManners.of(type, clusters, wanted)).with(vectorsOfMany(type, wanted, seen), wanted)
            },
        )
    }

    /**
     * The items [heads] of one source as the model reads them: their facets and numbers with the user's corrections,
     * the source when the type has several, the normalized series of their works ([series]).
     */
    internal fun readItems(
        s: SourceStore,
        severalSources: Boolean,
        series: Map<ItemKey, Long>,
        heads: List<ItemKeys>,
        laps: Laps = Laps(),
        /** The site's values of the items, read already by the operation: not read again. */
        read: Map<Long, Map<String, List<String>>>? = null,
        /**
         * The values the models gave the items beyond the site's own as the index of the list keeps them
         * ([ListModelValues]): no model run of them; null — made now by the chain of the models kept.
         */
        kept: Map<Long, Map<String, List<String>>>? = null,
        /** Told, of every item, the values the chain run now gave it beyond the site's own: what the index keeps. */
        given: MutableMap<Long, Map<String, List<String>>>? = null,
    ): List<CatalogueItem> {
        val ids = heads.map { it.id }
        val facetIds = s.schema.facets.filter { it.feature }.associate { it.key to FeatureNames.facetId(s.source, it) }
        // A facet not of the model gives no feature below
        val facets = read ?: s.items.facetsOf(ids)
        laps.lap("facets", facets.size)
        val facetCorrections = s.corrections.facetsOf(ids)
        laps.lap("corrections", facetCorrections.size)
        // The values the model gives the items of the facets it works out: made now of them, in one pass — unless kept
        val chances =
            if (kept != null) emptyMap()
            else
                suggestions.chancesOf(
                    s,
                    ids,
                    // Of every facet the source works out when its values are told: the index of the list keeps them
                    // all
                    s.schema.facets.filter { it.infer && (it.feature || given != null) }.map { it.key },
                    laps.within("chances:"),
                    // The values and the answers of the items read above: not read again by the chain
                    EveryItemFacets(facets, facetCorrections),
                )
        laps.restart()
        val numberDefs = s.schema.numbers.filter { it.feature }.associateBy { it.key }
        val numbers = s.items.numbersOf(ids).mapValues { (_, own) -> own.filterKeys { it in numberDefs } }
        laps.lap("numbers", numbers.size)
        val fields = s.corrections.fieldsOf(ids)
        // A tag under another gives the work the other too; a tag the user said it has not, none under it
        val hierarchies = s.hierarchies()
        // The tags the user ignores stay with the works, the model reads none of them
        val ignored = s.ignoredTags()
        laps.lap("fields and tag trees")
        // Every feature's name one string of the reading, made once however many works have it
        val names = HashMap<String, HashMap<String, String>>()
        fun feature(facetId: String, key: String) =
            names.getOrPut(facetId) { HashMap() }.getOrPut(key) { FeatureNames.facet(facetId, key) }
        val source = "source:${s.id}"
        val made = heads.map { k ->
            val site = facets[k.id].orEmpty()
            val base =
                if (kept != null) withKept(site, kept[k.id].orEmpty())
                else ModelValues.of(s.schema, site, chances[k.id]).also { given?.put(k.id, beyond(it, site)) }
            val corrected = TagHierarchy.corrected(base, facetCorrections[k.id].orEmpty(), hierarchies, s.singleFacets)
            val categorical = categoricalOf(corrected, facetIds, ignored, ::feature)
            if (severalSources) categorical += source
            categorical += listOfNotNull(series[ItemKey(s.id, k.id)]).map(FeatureNames::series)
            val numeric = numericOf(s, numberDefs, numbers[k.id].orEmpty(), fields[k.id].orEmpty())
            CatalogueItem(ItemKey(s.id, k.id), k.version, k.updatedAt, categorical, numeric)
        }
        laps.lap("items", made.size)
        return made
    }

    /** One item as the model reads it, without its vectors; null when there is no such item. */
    fun item(type: TypeStore, key: ItemKey, made: Map<String, Map<String, Chance>>? = null): CatalogueItem? {
        val s = type.source(key.source) ?: return null
        return s.items.find(key.id)?.let { head ->
            val facetIds =
                s.schema.facets.filter { it.feature }.associate { it.key to FeatureNames.facetId(s.source, it) }
            val wanted = s.schema.facets.filter { it.infer && it.feature }.map { it.key }
            val base =
                ModelValues.of(
                    s.schema,
                    s.items.facets(key.id),
                    made ?: suggestions.chancesOf(s, listOf(key.id), wanted)[key.id],
                )
            val ignored = s.ignoredTags()
            val corrected =
                TagHierarchy.corrected(base, s.corrections.facetsOf(key.id), s.hierarchies(), s.singleFacets)
            val categorical = categoricalOf(corrected, facetIds, ignored, FeatureNames::facet)
            if (type.sources.size > 1) categorical += "source:${s.id}"
            ItemSeries.of(type, listOf(key))[key]?.let { categorical += FeatureNames.series(it) }
            val numberDefs = s.schema.numbers.filter { it.feature }.associateBy { it.key }
            val numeric = numericOf(s, numberDefs, s.items.numbers(key.id), s.corrections.fieldsOf(key.id))
            CatalogueItem(key, head.version, head.updatedAt, categorical, numeric)
        }
    }

    /**
     * The work of the item as the model reads it, without its vectors, named by the first of its items; null when there
     * is no such item.
     */
    fun work(type: TypeStore, key: ItemKey, read: ReadWork? = null): CatalogueItem? {
        val members = (read?.members ?: works.ofKeys(type, listOf(key)).members(key)).sortedBy { it.toString() }
        if (members.size == 1) return item(type, key, read?.chances?.get(key))
        val own = members.mapNotNull { item(type, it, read?.chances?.get(it)) }
        return own.takeIf { it.isNotEmpty() }
            ?.let { WorkFeatures.item(it.first().key, it, WorkFeatures.joins(type.sources.map { s -> s.source })) }
    }

    /**
     * The vectors of the work of the item — texts, pictures, sets — without its likeness to the marks: for a request,
     * of what is kept alone, so of several items the mean of their sets too, the sets of all of them made in a
     * computation ([SetVectors.ofWork]).
     */
    fun vectors(
        type: TypeStore,
        key: ItemKey,
        members: List<ItemKey> = works.ofKeys(type, listOf(key)).members(key),
    ): Map<String, FloatArray> {
        val read = vectorsOfMany(type, members)
        return WorkFeatures.vectors(members.mapNotNull { read[it] }) +
            TypeManners.of(type, works.ofKeys(type, members), members).blocks(key)
    }

    /**
     * The vectors of [keys] — texts, pictures, sets — without their likeness to the marks: a query of each kind a
     * source.
     */
    fun vectorsOfMany(
        type: TypeStore,
        keys: List<ItemKey>,
        /** Told of every vector as it is read. */
        seen: VectorSeen? = null,
    ): Map<ItemKey, Map<String, FloatArray>> {
        val out = HashMap<ItemKey, HashMap<String, FloatArray>>()
        val embeddings = type.embeddings.ids()
        keys.groupBy({ it.source }, { it.id }).forEach { (source, ids) ->
            type.source(source)?.let { s ->
                vectorsOf(s, ids, embeddings) { id, block, v, picture ->
                    out.getOrPut(ItemKey(source, id)) { HashMap() }[block] = v
                    seen?.seen(block, v, picture)
                }
            }
        }
        return out
    }

    /**
     * The vectors of the items [ids] of the source [s], every one handed to [put]: of texts, of pictures (`picture`),
     * of sets.
     */
    private fun vectorsOf(
        s: SourceStore,
        ids: List<Long>,
        embeddings: Map<SetKind, Long>,
        put: (id: Long, block: String, v: FloatArray, picture: Boolean) -> Unit,
    ) {
        plugins.textEncoder()?.let { encoder ->
            val blocks = TextVectors.blocks(s.id, s.schema).mapValues { it.value.first }
            s.textVectors.ofMany(ids, encoder.id).forEach { (id, texts) ->
                texts.forEach { (k, v) -> blocks[k]?.let { put(id, it, v, false) } }
            }
        }
        plugins.imageEncoder()?.let { encoder ->
            s.pictures.itemVectors.itemVectorsOf(ids, encoder.id).forEach { (id, blocks) ->
                blocks.forEach { (b, v) -> put(id, b, v, true) }
            }
        }
        s.setVectors.ofMany(ids, embeddings).forEach { (id, blocks) ->
            blocks.forEach { (b, v) -> put(id, b, v, false) }
        }
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

/** The [site]'s values of an item with those the models gave it beyond them, [kept]. */
private fun withKept(site: Map<String, List<String>>, kept: Map<String, List<String>>): Map<String, List<String>> =
    if (kept.isEmpty()) site
    else HashMap(site).apply { kept.forEach { (facet, values) -> merge(facet, values) { a, b -> (a + b).distinct() } } }

/** Of the [values] of an item, those beyond its [site]'s own, by facet: what the models gave it. */
private fun beyond(values: Map<String, List<String>>, site: Map<String, List<String>>): Map<String, List<String>> =
    values
        .mapValues { (facet, list) -> site[facet].orEmpty().toHashSet().let { own -> list.filter { it !in own } } }
        .filterValues { it.isNotEmpty() }

/** Every grade of the [type]. */
internal fun ratingsOf(type: TypeStore): List<TypedRating> =
    type.sources.flatMap { s ->
        s.ratings.all().map { TypedRating(ItemKey(s.id, it.itemId), it.version, it.grade, it.ratedAt) }
    }

/** Every mark of interest of the [type]. */
internal fun interestOf(type: TypeStore): Map<ItemKey, InterestMark> =
    HashMap<ItemKey, InterestMark>().apply {
        type.sources.forEach { s -> s.interest.all().forEach { (id, mark) -> put(ItemKey(s.id, id), mark) } }
    }
