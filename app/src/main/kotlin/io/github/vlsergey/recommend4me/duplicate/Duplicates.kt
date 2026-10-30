package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicateFacet
import io.github.vlsergey.recommend4me.api.model.DuplicatePage
import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateWork
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.interest.Interest
import io.github.vlsergey.recommend4me.interest.LinkedInterest
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.LinkedGrades
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.vector.Vectors
import io.github.vlsergey.recommend4me.work.Works
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(Duplicates::class.java)

/**
 * The user's word on a pair: one work; different works; or related works ([related]) — one the
 * TRANSLATION of the other, one holding a PARTIAL of the other's text (a site's fewer chapters, a collection) — not linked,
 * each its own grade and interest, left out of the model of the answers ([fit]): a translation's
 * authors differ while its tags and description are the other's — neither one work nor two.
 */
enum class DuplicateAnswer {
    SAME, DIFFERENT, TRANSLATION, PARTIAL;

    /** Related works, kept by their kind ([io.github.vlsergey.recommend4me.correction.TypeLinkRepository.relate]): neither one work nor two. */
    val related: Boolean get() = this == TRANSLATION || this == PARTIAL
}

/**
 * THE WORKS OF DIFFERENT SITES THAT MAY BE ONE WORK, and the user's word on each pair. A search
 * ([search]), in the queue of the computations, compares every work of every site of the content
 * type with every work of every other site by every field of a work every site has alike, the text
 * itself aside: the vectors of their titles and of their descriptions (the encoder of texts reads
 * many languages: an original on Archive of Our Own and its translation on fanfics.me are near), the
 * values of every facet the sites share, each apart ([valuesOf]), and the numbers of their size — and keeps of
 * every work the one of every other site likeliest one work with it, of the pairs the user did not
 * turn down (different, related: the next likeliest in their place); no threshold, none hidden. It
 * runs on start when the type was never searched, and when the user asks. The pairs are answered on
 * as the work's window links the works: one work — linked, its grade and interest shared
 * ([LinkedGrades], [LinkedInterest]) — different works, or related ones (a translation, a part: not
 * linked, each its own grade), never offered again
 * ([io.github.vlsergey.recommend4me.correction.TypeLinkRepository.distinct], [io.github.vlsergey.recommend4me.correction.TypeLinkRepository.relate]).
 *
 * THE ANSWERS TEACH THE COMPARING: how likely a pair is one work is a model of the user's answers
 * ([DuplicateModel]) over how alike its layers are, fitted at every search and a few seconds after
 * the answers ([answered]); the search keeps of every work the pair the model finds likeliest. The
 * pairs are shown by the larger of the two works' scores by the formula of the list (the prediction
 * times the chance of interest) times that chance: a likely pair of a work the user would read first.
 */
@Component
class Duplicates(
    private val stores: Stores,
    private val plugins: Plugins,
    private val workload: Workload,
    private val works: Works,
    private val linkedGrades: LinkedGrades,
    private val linkedInterest: LinkedInterest,
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "duplicates").apply { isDaemon = true } }

    /** The types whose search is planned or running. */
    private val searching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The types whose model of the answers is to be fitted again, put off by every answer till they are quiet. */
    private val fitting = ConcurrentHashMap<String, ScheduledFuture<*>>()

    private fun searchWork(type: TypeStore) = Work("duplicates", type = type.id)

    private fun fittingWork(type: TypeStore) = Work("duplicatesModel", type = type.id)

    /** Whether a search or a fitting of the type is planned or running: the pairs shown may change. */
    private fun pending(type: TypeStore) = type.id in searching || fitting.containsKey(type.id)

    /** Every type of several sites never searched yet: searched once, in the background. */
    @Order(20)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.types.filter { it.sources.size > 1 && it.duplicates.search() == null }.forEach { plan(it, "start") }
    }

    /** The search of the type planned, in the background; false when one is planned or running already. */
    fun plan(type: TypeStore, reason: String): Boolean {
        if (!searching.add(type.id)) return false
        val work = searchWork(type)
        workload.plan(work, Instant.now(), reason)
        worker.execute {
            try {
                workload.run(work) { search(type) }
            } catch (e: Exception) {
                log.warn("{}: the duplicates were not looked for: {}", type.id, e.message, e)
            } finally {
                searching -= type.id
            }
        }
        return true
    }

    /**
     * The works of one site as the search reads them: those with a vector of their title, its unit
     * rows ([titles], one row a work, [width] numbers), and of their descriptions ([descriptions],
     * a row of noughts and [described] false of a work without one); the values of the facets the
     * sites share ([values], by their places among every value of the type) — weighed once every site
     * is read ([weigh]), and of every value the works that have it with its weight in each; and the
     * numbers of its size the sites share ([sizes], by the standard number, nought when not given).
     */
    private class Side(
        val store: SourceStore, val ids: List<String>, val width: Int, val titles: Floats, val descriptions: Floats, val described: BooleanArray,
        val values: Array<IntArray>, val sizes: Map<String, DoubleArray>,
    ) {
        /** Of every work, the weight of each of its [values]: the value's rarity among every work of the type, the work's values of each facet of unit length. */
        lateinit var weights: Array<FloatArray>

        /** Of every work, the facets it has a value of: a bit a facet, by its place among the type's ([familyOf]). */
        lateinit var facets: LongArray

        /** Of every value of the type, the works of this site that have it ([withValueItems]) and its weight in each. */
        lateinit var withValueItems: Array<IntArray>
        lateinit var withValueWeights: Array<FloatArray>

        /** Its works' values weighed by the [rarity] of every value, every facet's of a work apart, and every value's works laid out. */
        fun weigh(rarity: FloatArray, familyOf: IntArray) {
            facets = LongArray(values.size) { i -> values[i].fold(0L) { bits, v -> bits or (1L shl familyOf[v]) } }
            weights = Array(values.size) { i ->
                val w = FloatArray(values[i].size) { k -> rarity[values[i][k]] }
                val norms = HashMap<Int, Double>()
                values[i].forEachIndexed { k, v -> norms.merge(familyOf[v], w[k].toDouble() * w[k], Double::plus) }
                values[i].forEachIndexed { k, v -> norms[familyOf[v]]?.let { n -> if (n > 0) w[k] /= Math.sqrt(n).toFloat() } }
                w
            }
            val count = IntArray(rarity.size)
            values.forEach { own -> own.forEach { count[it]++ } }
            withValueItems = Array(rarity.size) { IntArray(count[it]) }
            withValueWeights = Array(rarity.size) { FloatArray(count[it]) }
            val filled = IntArray(rarity.size)
            values.forEachIndexed { i, own ->
                own.forEachIndexed { k, t ->
                    withValueItems[t][filled[t]] = i
                    withValueWeights[t][filled[t]] = weights[i][k]
                    filled[t]++
                }
            }
        }
    }

    /**
     * Every work's values of every facet the sites share, as the site and the user give them — no
     * model asked — each "<facet>:<value>": of the facets of the application's layer (the kind, the
     * universes, the characters — the main and the secondary alike —, the pairings, the normalized
     * tags, the languages, the age rating) the site's own, the drafts of the layer, of the tags the
     * site's tags by the normalized ones they are bound to, the tags the user ignores left out, the
     * user's answers over them all; of a site's facet of the standard values every site gives alike
     * (the status, the direction, the ratings the site allows) its values. By their places in
     * [places], every new value given one.
     */
    private fun valuesOf(s: SourceStore, ids: List<String>, places: HashMap<String, Int>): Array<IntArray> {
        val own = HashMap<String, MutableSet<String>>()
        fun add(id: String, prefix: String, key: String) {
            own.getOrPut(id) { HashSet() } += "$prefix:$key"
        }
        val layers = s.schema.facets.filter { it.editable }
        val bound = s.tags.bindingsOf(s.id)
        val ignored = s.ignoredTags()
        layers.forEach { f ->
            s.items.forEachFacet(f.key) { id, keys -> keys.forEach { add(id, familyOf(f.key), it) } }
            f.examplesFrom?.let { site -> s.items.forEachFacet(site) { id, keys -> keys.forEach { k -> bound[k]?.let { add(id, familyOf(f.key), TagRepository.key(it)) } } } }
        }
        val layerKeys = layers.map { it.key }.toSet()
        s.drafts.all().forEach { (id, drafts) -> drafts.filter { it.facet in layerKeys }.forEach { add(id, familyOf(it.facet), it.key) } }
        s.corrections.allFacets().filter { it.facet in layerKeys }.forEach { c ->
            val value = "${familyOf(c.facet)}:${c.key}"
            if (c.added) own.getOrPut(c.itemId) { HashSet() } += value else own[c.itemId]?.remove(value)
        }
        ignored.forEach { (facet, keys) -> keys.forEach { k -> own.values.forEach { it -= "${familyOf(facet)}:$k" } } }
        s.schema.facets.filter { !it.editable && it.standard?.values != null }.forEach { f ->
            s.items.forEachFacet(f.key) { id, keys -> keys.forEach { add(id, f.standard!!.key, it) } }
        }
        return Array(ids.size) { i -> own[ids[i]].orEmpty().map { places.getOrPut(it) { places.size } }.sorted().toIntArray() }
    }

    /** The facet a facet of the layer's values are compared as: the characters of either facet of them are one — a main one on a site, a secondary one on another. */
    private fun familyOf(facet: String) = if (facet in UniverseFacet.CHARACTER_FACETS) UniverseFacets.CHARACTERS else facet

    /** The numbers of the size of every work the sites share — a standard number, not how the site's readers met it — by its key. */
    private fun sizesOf(s: SourceStore, ids: List<String>): Map<String, DoubleArray> {
        val at = ids.withIndex().associate { it.value to it.index }
        return s.schema.numbers.filter { it.standard != null && it.standard!!.role !in SITE_ROLES }.associate { n ->
            val out = DoubleArray(ids.size)
            s.items.number(n.key).forEach { (id, v) -> at[id]?.let { out[it] = v } }
            n.standard!!.key to out
        }
    }

    /** The side of the source by the [encoder]'s vectors, read as they go, and its works' values and sizes; null when it has none. */
    private fun side(s: SourceStore, encoder: String, places: HashMap<String, Int>): Side? {
        val description = s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key
        val ids = s.textVectors.keys().filter { it.second == TextVectors.TITLE }.map { it.first }.distinct().sorted()
        if (ids.isEmpty()) return null
        val at = ids.withIndex().associate { it.value to it.index }
        var width = 0
        var titles: Floats? = null
        var descriptions: Floats? = null
        val described = BooleanArray(ids.size)
        s.textVectors.forEach(encoder, listOfNotNull(TextVectors.TITLE, description)) { id, key, vector ->
            val i = at[id] ?: return@forEach
            if (titles == null) {
                width = vector.size
                titles = Floats(ids.size * width)
                descriptions = Floats(ids.size * width)
            }
            if (vector.size != width) return@forEach
            val unit = Vectors.unit(vector)
            if (key == TextVectors.TITLE) titles!!.put(unit, 0, i * width, width)
            else {
                descriptions!!.put(unit, 0, i * width, width)
                described[i] = true
            }
        }
        return titles?.let { Side(s, ids, width, it, descriptions!!, described, valuesOf(s, ids, places), sizesOf(s, ids)) }
    }

    /**
     * THE SEARCH: every work of every site against every work of every other by every layer at once —
     * the cosines of their titles' and their descriptions' vectors, a block of rows of one site against
     * the whole other ([Blas.gemm]); of the values of the facets the sites share, one layer of them
     * all, each value weighed by how rare it is among every work of the type (the logarithm of the
     * works over those with it: "finished", a value of most works, tells little, a character of a few
     * much), through the works of the other site that have each value; of their size, the mean of the
     * smaller over the larger of every number both have — how alike two works are the mean of the
     * layers both have; the most alike of every other site kept of every work, both ways, and the whole
     * kept in place of the last search's.
     */
    private fun search(type: TypeStore) {
        val encoder = plugins.textEncoder()?.id ?: return
        val started = System.currentTimeMillis()
        val places = HashMap<String, Int>()
        val sides = type.sources.mapNotNull { side(it, encoder, places) }
        val works = sides.sumOf { it.ids.size }
        val having = IntArray(places.size)
        sides.forEach { side -> side.values.forEach { own -> own.forEach { having[it]++ } } }
        val rarity = FloatArray(places.size) { t -> if (having[t] == 0) 0f else Math.log(works.toDouble() / having[t]).toFloat() }
        // Every value's facet: the name before its value ("characters:wikidata:Q…")
        val names = arrayOfNulls<String>(places.size).also { out -> places.forEach { (name, at) -> out[at] = name } }
        val families = names.map { it!!.substringBefore(':') }.distinct().sorted()
        require(families.size < Long.SIZE_BITS) { "${families.size} facets of the type: no bit of a mask each" }
        val familyAt = families.withIndex().associate { it.value to it.index }
        val familyOf = IntArray(places.size) { familyAt.getValue(names[it]!!.substringBefore(':')) }
        sides.forEach { it.weigh(rarity, familyOf) }
        // The pairs chosen by the model of the answers as the last fitting left it
        val scorer = DuplicateModel(type.duplicates.search()?.weights).Scorer(families)
        val read = System.currentTimeMillis() - started
        // The pairs the user turned down — different works, related ones — passed over: the next likeliest of that site taken
        val said = said(type)
        val turnedDown = HashMap<Pair<String, String>, HashMap<Int, MutableList<Int>>>()
        val bySource = sides.associateBy { it.store.id }
        said.filter { it.value != DuplicateAnswer.SAME }.keys.forEach { (x, y) ->
            val a = bySource[x.source] ?: return@forEach
            val b = bySource[y.source] ?: return@forEach
            val i = a.ids.binarySearch(x.id).takeIf { it >= 0 } ?: return@forEach
            val j = b.ids.binarySearch(y.id).takeIf { it >= 0 } ?: return@forEach
            turnedDown.getOrPut(x.source to y.source) { HashMap() }.getOrPut(i) { ArrayList() } += j
            turnedDown.getOrPut(y.source to x.source) { HashMap() }.getOrPut(j) { ArrayList() } += i
        }
        val found = HashMap<Pair<ItemKey, ItemKey>, DuplicateCandidate>()
        for (a in sides) for (b in sides) {
            if (a === b || a.width != b.width) continue
            val n = b.ids.size
            val passedOver = turnedDown[a.store.id to b.store.id].orEmpty()
            val passed = BooleanArray(n)
            val rows = maxOf(1, minOf(a.ids.size, BLOCK / n))
            val titles = Floats(rows * n)
            val descriptions = Floats(rows * n)
            val title = FloatArray(n)
            val description = FloatArray(n)
            // Of a work of this site, its values' cosine of every facet with every work of the other, and the works it touched: made nought again after
            val valued = Array(families.size) { FloatArray(n) }
            val sizeKeys = a.sizes.keys.filter { it in b.sizes }
            val touched = IntArray(n)
            val isTouched = BooleanArray(n)
            var from = 0
            while (from < a.ids.size) {
                val m = minOf(rows, a.ids.size - from)
                Blas.gemm(false, true, m, n, a.width, a.titles, from * a.width, a.width, b.titles, 0, b.width, titles, 0, n)
                Blas.gemm(false, true, m, n, a.width, a.descriptions, from * a.width, a.width, b.descriptions, 0, b.width, descriptions, 0, n)
                for (r in 0 until m) {
                    val i = from + r
                    titles.take(title, 0, r * n, n)
                    descriptions.take(description, 0, r * n, n)
                    var touches = 0
                    val ownValues = a.values[i]
                    for (k in ownValues.indices) {
                        val w = a.weights[i][k]
                        val into = valued[familyOf[ownValues[k]]]
                        val items = b.withValueItems[ownValues[k]]
                        val weights = b.withValueWeights[ownValues[k]]
                        for (q in items.indices) {
                            val j = items[q]
                            if (!isTouched[j]) {
                                isTouched[j] = true
                                touched[touches++] = j
                            }
                            into[j] += w * weights[q]
                        }
                    }
                    val ownFacets = a.facets[i]
                    /** How alike the sizes of the two are: of every number both have, the smaller over the larger, their mean; null when they share none. */
                    fun size(j: Int): Float? {
                        var sum = 0.0
                        var known = 0
                        for (key in sizeKeys) {
                            val x = a.sizes[key]!![i]
                            val y = b.sizes[key]!![j]
                            if (x > 0 && y > 0) {
                                sum += minOf(x, y) / maxOf(x, y)
                                known++
                            }
                        }
                        return if (known == 0) null else (sum / known).toFloat()
                    }
                    // The likeliest one work with it of the other site, of those the user did not turn down
                    val own = passedOver[i]
                    own?.forEach { passed[it] = true }
                    var best = -1
                    var score = Double.NEGATIVE_INFINITY
                    for (j in 0 until n) {
                        if (passed[j]) continue
                        val sized = size(j)
                        val s = scorer.score(
                            title[j], description[j], a.described[i] && b.described[j], sized ?: 0f, sized != null,
                            ownFacets and b.facets[j], valued, j,
                        )
                        if (s > score) {
                            score = s
                            best = j
                        }
                    }
                    if (best >= 0) {
                        val (first, second) = type.links.ordered(ItemKey(a.store.id, a.ids[i]), ItemKey(b.store.id, b.ids[best]))
                        found.getOrPut(first to second) {
                            DuplicateCandidate(
                                first, second, title[best],
                                description = if (a.described[i] && b.described[best]) description[best] else null,
                                size = size(best),
                                facets = families.indices.filter { (ownFacets and b.facets[best]) and (1L shl it) != 0L }.associate { families[it] to valued[it][best] },
                            )
                        }
                    }
                    for (q in 0 until touches) {
                        val j = touched[q]
                        isTouched[j] = false
                        for (f in valued.indices) valued[f][j] = 0f
                    }
                    own?.forEach { passed[it] = false }
                }
                from += m
            }
        }
        // Every pair the user answered on, found or not: what the model learns from
        said.keys.filter { it !in found }.forEach { pair ->
            val a = bySource[pair.first.source] ?: return@forEach
            val b = bySource[pair.second.source] ?: return@forEach
            if (a === b || a.width != b.width) return@forEach
            val i = a.ids.binarySearch(pair.first.id).takeIf { it >= 0 } ?: return@forEach
            val j = b.ids.binarySearch(pair.second.id).takeIf { it >= 0 } ?: return@forEach
            found[pair] = layersOf(pair, a, i, b, j, families, familyOf)
        }
        val compared = System.currentTimeMillis() - started
        val fitted = fit(found.values, said)
        type.duplicates.replace(found.values, DuplicateSearch(Instant.now(), encoder, works, fitted?.weights))
        log.info(
            "{}: duplicates looked for among {} works of {} sites, by {} facets apart — {} pairs in {} ms (reading {}, comparing {}, keeping {}): {}",
            type.id, works, sides.size, families.size, found.size, System.currentTimeMillis() - started, read, compared - read,
            System.currentTimeMillis() - started - compared, fitted?.weights,
        )
    }

    /** The layers of the pair of [a]'s [i]-th work and [b]'s [j]-th, worked out alone: a pair the user answered on that the search did not keep. */
    private fun layersOf(pair: Pair<ItemKey, ItemKey>, a: Side, i: Int, b: Side, j: Int, families: List<String>, familyOf: IntArray): DuplicateCandidate {
        fun dot(x: Floats, y: Floats): Float {
            val u = FloatArray(a.width).also { x.take(it, 0, i * a.width, a.width) }
            val v = FloatArray(b.width).also { y.take(it, 0, j * b.width, b.width) }
            var sum = 0f
            for (k in u.indices) sum += u[k] * v[k]
            return sum
        }
        val both = a.facets[i] and b.facets[j]
        val facets = FloatArray(families.size)
        var p = 0
        var q = 0
        while (p < a.values[i].size && q < b.values[j].size) {
            val x = a.values[i][p]
            val y = b.values[j][q]
            when {
                x == y -> facets[familyOf[x]] += a.weights[i][p++] * b.weights[j][q++]
                x < y -> p++
                else -> q++
            }
        }
        val ratios = a.sizes.keys.filter { it in b.sizes }.mapNotNull { key ->
            val x = a.sizes[key]!![i]
            val y = b.sizes[key]!![j]
            if (x > 0 && y > 0) minOf(x, y) / maxOf(x, y) else null
        }
        return DuplicateCandidate(
            pair.first, pair.second, dot(a.titles, b.titles),
            description = if (a.described[i] && b.described[j]) dot(a.descriptions, b.descriptions) else null,
            size = if (ratios.isEmpty()) null else ratios.average().toFloat(),
            facets = families.indices.filter { both and (1L shl it) != 0L }.associate { families[it] to facets[it] },
        )
    }

    /**
     * The model of the user's answers of the [pairs]: one work, different — every pair not answered
     * on a likely different one — the related works left out (user, 2026-10-30): a translation's
     * authors differ, its tags and description are the other's, a part is neither one nor two — they
     * teach neither one work nor two.
     */
    private fun fit(pairs: Collection<DuplicateCandidate>, said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>): DuplicateModel? =
        DuplicateModel.fit(pairs.filter { said[it.pair]?.related != true }) { c -> said[c.pair]?.let { it == DuplicateAnswer.SAME } }

    /** The user's word on every pair across the sites, the smaller key first: one work (linked, by any way), different, related. */
    private fun said(type: TypeStore): Map<Pair<ItemKey, ItemKey>, DuplicateAnswer> {
        val out = HashMap<Pair<ItemKey, ItemKey>, DuplicateAnswer>()
        type.links.distincts().forEach { out[it] = DuplicateAnswer.DIFFERENT }
        type.links.relations().forEach { (pair, kind) -> runCatching { DuplicateAnswer.valueOf(kind) }.getOrNull()?.takeIf { it.related }?.let { out[pair] = it } }
        works.of(type).linkedWorks().forEach { members ->
            members.forEachIndexed { k, a ->
                members.drop(k + 1).filter { it.source != a.source }.forEach { b -> out[type.links.ordered(a, b)] = DuplicateAnswer.SAME }
            }
        }
        return out
    }

    /**
     * The model of the answers fitted again a few seconds after the last answer, of the pairs kept
     * as they are: in the background, in the queue of the computations; the pairs are shown by it
     * as soon as it is kept.
     */
    private fun answered(type: TypeStore) {
        val work = fittingWork(type)
        workload.plan(work, Instant.now().plusSeconds(QUIET_SECONDS), "answer")
        fitting.compute(type.id) { _, before ->
            before?.cancel(false)
            worker.schedule({
                try {
                    workload.run(work) {
                        val started = System.currentTimeMillis()
                        val said = said(type)
                        val pairs = type.duplicates.all()
                        val model = fit(pairs, said)
                        type.duplicates.keepWeights(model?.weights)
                        log.info(
                            "{}: the model of the duplicates fitted on {} pairs, {} said one work, {} related, {} different, in {} ms: {}",
                            type.id, pairs.size, said.values.count { it == DuplicateAnswer.SAME }, said.values.count { it.related },
                            said.values.count { it == DuplicateAnswer.DIFFERENT }, System.currentTimeMillis() - started, model?.weights,
                        )
                    }
                } catch (e: Exception) {
                    log.warn("{}: the model of the duplicates was not fitted: {}", type.id, e.message, e)
                } finally {
                    fitting.remove(type.id)
                }
            }, QUIET_SECONDS, TimeUnit.SECONDS)
        }
    }

    /**
     * A page of the pairs of the type: not [answered] — those neither linked (by any way, through
     * other works too) nor said to be different — or answered, the works linked across the sites and
     * those said to be different. The first by the larger of the two works' scores by the formula of
     * the list (the prediction times the chance of interest: a work the user would read first) times
     * the chance the pair is one work, by the model of the answers; equal, the likelier pair first.
     * Every pair read for the request and let go. Null of no such type.
     */
    fun page(typeId: String, answered: Boolean, offset: Int, limit: Int): DuplicatePage? {
        val type = stores.type(typeId) ?: return null
        val said = said(type)
        val search = type.duplicates.search()
        val model = DuplicateModel(search?.weights)
        val kept = type.duplicates.all()
        // The score of every work by the formula of the list: the prediction (not below nought) times the chance of interest
        val formula = HashMap<ItemKey, Double>()
        type.sources.forEach { s ->
            val marks = s.interest.all()
            type.models.predicted(s.id).forEach { (id, p) ->
                val chance = Interest.chance(marks[id]?.interesting, p.interest)
                formula[ItemKey(s.id, id)] = p.score.coerceAtLeast(0.0) * (chance ?: 1.0)
            }
        }
        // How every facet compared is named: the label of the facet of any source of the type that is it
        val labels = HashMap<String, String>()
        type.sources.forEach { s ->
            s.schema.facets.forEach { def ->
                val family = if (def.editable) familyOf(def.key) else def.standard?.takeIf { it.values != null }?.key
                if (family != null && (!def.editable || family == def.key)) labels.putIfAbsent(family, I18nKeys.facetLabel(s.id, def))
            }
        }
        class Ranked(val pair: Pair<ItemKey, ItemKey>, val layers: DuplicateCandidate?, val chance: Double?, val priority: Double)
        fun ranked(pair: Pair<ItemKey, ItemKey>, layers: DuplicateCandidate?): Ranked {
            val chance = layers?.let(model::chance)
            val best = maxOf(formula[pair.first] ?: 0.0, formula[pair.second] ?: 0.0)
            return Ranked(pair, layers, chance, best * (chance ?: 0.0))
        }
        val rows = if (!answered) kept.filter { it.pair !in said }.map { ranked(it.pair, it) }
        else kept.associateBy { it.pair }.let { byPair -> said.keys.map { ranked(it, byPair[it]) } }
        val sorted = rows.sortedWith(compareByDescending<Ranked> { it.priority }.thenByDescending { it.chance ?: -1.0 }.thenBy { it.pair.first.toString() })
        val page = sorted.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, MAX_PAGE))
        val described = describe(type, page.flatMap { listOf(it.pair.first, it.pair.second) })
        return DuplicatePage(
            total = rows.size,
            pairs = page.mapNotNull { r ->
                val first = described[r.pair.first] ?: return@mapNotNull null
                val second = described[r.pair.second] ?: return@mapNotNull null
                val d = r.layers
                DuplicatePair(
                    first = first.work, second = second.work,
                    chance = r.chance?.toBigDecimal(), priority = r.priority.toBigDecimal(),
                    title = d?.title?.toBigDecimal(), description = d?.description?.toBigDecimal(), propertySize = d?.size?.toBigDecimal(),
                    facets = d?.facets.orEmpty().entries.sortedByDescending { it.value }.map { (family, v) ->
                        DuplicateFacet(key = family, labelKey = labels[family] ?: family, `value` = v.toBigDecimal())
                    },
                    sameAuthor = first.authors.any { it in second.authors },
                    answer = said[r.pair]?.let { DuplicatePair.Answer.valueOf(it.name) },
                )
            },
            pending = pending(type),
            searchedAt = search?.madeAt?.atOffset(ZoneOffset.UTC),
            works = search?.works,
            learnt = search?.weights != null,
        )
    }

    /** A work as a pair shows it, and its authors' names in lower case: what "the same author" is told by. */
    private class Described(val work: DuplicateWork, val authors: Set<String>)

    /** The works as the pairs show them: each source's read in one go; a work no longer there, none. */
    private fun describe(type: TypeStore, keys: List<ItemKey>): Map<ItemKey, Described> {
        val out = HashMap<ItemKey, Described>()
        keys.distinct().groupBy { it.source }.forEach { (source, own) ->
            val s = type.source(source) ?: return@forEach
            val ids = own.map { it.id }
            val heads = s.items.heads(ids)
            val fields = s.corrections.fieldsOf(ids)
            val description = s.schema.texts.firstOrNull { it.role == TextRole.DESCRIPTION }?.key
            val texts = if (description == null) emptyMap() else s.items.textsOf(ids, listOf(description))
            val authorFacet = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.key
            val fandomFacet = UniverseFacet.fandomFacet(s.schema)
            val facets = s.items.facetsOf(ids)
            fun namesOf(facet: String?): Map<String, String> =
                facet?.let { f -> s.names(f, facets.values.flatMap { it[f].orEmpty() }.distinct()) }.orEmpty()
            val authorNames = namesOf(authorFacet)
            val fandomNames = namesOf(fandomFacet)
            ids.forEach { id ->
                val head = heads[id] ?: return@forEach
                val written = fields[id].orEmpty()
                val authors = authorFacet?.let { f -> facets[id]?.get(f).orEmpty().map { authorNames[it] ?: it } }.orEmpty()
                val shown = Corrected.texts(texts[id].orEmpty(), written)
                out[ItemKey(source, id)] = Described(
                    DuplicateWork(
                        source = source, item = id, title = Corrected.title(head.title, written), url = head.url,
                        authors = authors,
                        fandoms = fandomFacet?.let { f -> facets[id]?.get(f).orEmpty().map { fandomNames[it] ?: it } }.orEmpty(),
                        description = description?.let { shown[it] },
                    ),
                    authors.map { it.trim().lowercase() }.toSet(),
                )
            }
        }
        return out
    }

    /**
     * The user's word on the pair, in place of any before: one work — linked as the work's window
     * links them, its grade and interest shared — different works, or related ones (a translation, a
     * part: not linked, each its own grade and interest), a link between them taken away. False when
     * either is no work of the type, or they are one item.
     */
    fun answer(typeId: String, a: ItemKey, b: ItemKey, verdict: DuplicateAnswer): Boolean {
        val type = stores.type(typeId) ?: return false
        val first = type.source(a.source)?.takeIf { it.items.find(a.id) != null } ?: return false
        if (type.source(b.source)?.items?.find(b.id) == null || a == b) return false
        val now = Instant.now()
        type.links.undistinct(a, b)
        type.links.unrelate(a, b)
        when (verdict) {
            DuplicateAnswer.SAME -> {
                if (a.source == b.source) first.corrections.link(a.id, b.id, now) else type.links.link(a, b, now)
                // One work, one grade and one interest mark: written to each of its items
                linkedGrades.share(a)
                linkedInterest.share(a)
            }
            DuplicateAnswer.DIFFERENT -> {
                if (a.source == b.source) first.corrections.unlink(a.id, b.id)
                type.links.distinct(a, b, now)
            }
            DuplicateAnswer.TRANSLATION, DuplicateAnswer.PARTIAL -> {
                if (a.source == b.source) first.corrections.unlink(a.id, b.id)
                type.links.relate(a, b, verdict.name, now)
            }
        }
        answered(type)
        return true
    }

    /** The word on the pair taken back: their link, that they are different, or related. */
    fun unanswer(typeId: String, a: ItemKey, b: ItemKey) {
        val type = stores.type(typeId) ?: return
        if (a.source == b.source) type.source(a.source)?.corrections?.unlink(a.id, b.id)
        type.links.unlink(a, b)
        type.links.undistinct(a, b)
        type.links.unrelate(a, b)
        answered(type)
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        /** The roles of the numbers of how a site's readers met a work — its likes, its views —: not the work's own. */
        private val SITE_ROLES = setOf(NumberRole.APPROVAL, NumberRole.REACH)

        /** The cosines held at once, a block of one site's rows against the whole other: memory, not meaning. */
        private const val BLOCK = 16 shl 20

        /** The quiet after the last answer before the model of the answers is fitted again. */
        private const val QUIET_SECONDS = 3L

        /** The pairs a page holds at most. */
        private const val MAX_PAGE = 200
    }
}
