package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicatePage
import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateWork
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.interest.LinkedInterest
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.LinkedGrades
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TextRole
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.universe.UniverseFacet
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

private val log = LoggerFactory.getLogger(Duplicates::class.java)

/** The user's word on a pair: one work, or different works. */
enum class DuplicateAnswer { SAME, DIFFERENT }

/**
 * THE WORKS OF DIFFERENT SITES THAT MAY BE ONE WORK, and the user's word on each pair. A search
 * ([search]), in the queue of the computations, compares every work of every site of the content
 * type with every work of every other site by the vectors of their titles and of their descriptions
 * — the encoder of texts reads many languages: an original on Archive of Our Own and its translation
 * on fanfics.me are near — and keeps of every work the [NEAREST] most alike of every other site, the
 * pairs ordered by how alike they are; no threshold, none hidden. It runs on start when the type was
 * never searched, and when the user asks. The pairs are answered on as the work's window links the
 * works: one work — linked, its grade and interest shared ([LinkedGrades], [LinkedInterest]) — or
 * different works, never offered again ([io.github.vlsergey.recommend4me.correction.TypeLinkRepository.distinct]).
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
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "duplicates").apply { isDaemon = true } }

    /** The types whose search is planned or running. */
    private val searching: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun searchWork(type: TypeStore) = Work("duplicates", type = type.id)

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
     * a row of noughts and [described] false of a work without one).
     */
    private class Side(val store: SourceStore, val ids: List<String>, val width: Int, val titles: Floats, val descriptions: Floats, val described: BooleanArray)

    /** The side of the source by the [encoder]'s vectors, read as they go; null when it has none. */
    private fun side(s: SourceStore, encoder: String): Side? {
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
        return titles?.let { Side(s, ids, width, it, descriptions!!, described) }
    }

    /**
     * THE SEARCH: every work of every site against every work of every other, by the cosines of their
     * titles' and their descriptions' vectors — a block of rows of one site against the whole other
     * at once ([Blas.gemm]) — the [NEAREST] most alike of every other site kept of every work, both
     * ways, and the whole kept in place of the last search's.
     */
    private fun search(type: TypeStore) {
        val encoder = plugins.textEncoder()?.id ?: return
        val started = System.currentTimeMillis()
        val sides = type.sources.mapNotNull { side(it, encoder) }
        val read = System.currentTimeMillis() - started
        val found = HashMap<Pair<ItemKey, ItemKey>, DuplicateCandidate>()
        for (a in sides) for (b in sides) {
            if (a === b || a.width != b.width) continue
            val n = b.ids.size
            val rows = maxOf(1, minOf(a.ids.size, BLOCK / n))
            val titles = Floats(rows * n)
            val descriptions = Floats(rows * n)
            val title = FloatArray(n)
            val description = FloatArray(n)
            var from = 0
            while (from < a.ids.size) {
                val m = minOf(rows, a.ids.size - from)
                Blas.gemm(false, true, m, n, a.width, a.titles, from * a.width, a.width, b.titles, 0, b.width, titles, 0, n)
                Blas.gemm(false, true, m, n, a.width, a.descriptions, from * a.width, a.width, b.descriptions, 0, b.width, descriptions, 0, n)
                for (r in 0 until m) {
                    val i = from + r
                    titles.take(title, 0, r * n, n)
                    descriptions.take(description, 0, r * n, n)
                    // The nearest of the other site, the most alike first
                    val best = IntArray(NEAREST) { -1 }
                    val score = FloatArray(NEAREST) { Float.NEGATIVE_INFINITY }
                    for (j in 0 until n) {
                        val s = if (a.described[i] && b.described[j]) (title[j] + description[j]) / 2 else title[j]
                        if (s <= score[NEAREST - 1]) continue
                        var k = NEAREST - 1
                        while (k > 0 && s > score[k - 1]) {
                            score[k] = score[k - 1]
                            best[k] = best[k - 1]
                            k--
                        }
                        score[k] = s
                        best[k] = j
                    }
                    best.filter { it >= 0 }.forEach { j ->
                        val (first, second) = type.links.ordered(ItemKey(a.store.id, a.ids[i]), ItemKey(b.store.id, b.ids[j]))
                        found.getOrPut(first to second) {
                            DuplicateCandidate(first, second, title[j], if (a.described[i] && b.described[j]) description[j] else null)
                        }
                    }
                }
                from += m
            }
        }
        val compared = System.currentTimeMillis() - started
        val worksRead = sides.sumOf { it.ids.size }
        type.duplicates.replace(found.values, DuplicateSearch(Instant.now(), encoder, worksRead))
        log.info(
            "{}: duplicates looked for among {} works of {} sites — {} pairs in {} ms (reading {}, comparing {}, keeping {})",
            type.id, worksRead, sides.size, found.size, System.currentTimeMillis() - started, read, compared - read, System.currentTimeMillis() - started - compared,
        )
    }

    /**
     * A page of the pairs of the type: not [answered] — the most alike first, those neither linked
     * (by any way, through other works too) nor said to be different — or answered, the works linked
     * across the sites and those said to be different, the most alike first, those the search did
     * not find last. Null of no such type.
     */
    fun page(typeId: String, answered: Boolean, offset: Int, limit: Int): DuplicatePage? {
        val type = stores.type(typeId) ?: return null
        val clusters = works.of(type)
        val distinct = type.links.distincts().toSet()
        // Every pair of works linked across the sites, as the pairs are kept: the smaller key first
        val linked = clusters.linkedWorks().flatMap { members ->
            members.flatMapIndexed { k, a -> members.drop(k + 1).filter { it.source != a.source }.map { b -> type.links.ordered(a, b) } }
        }.toSet()
        fun answerOf(pair: Pair<ItemKey, ItemKey>) = when {
            clusters.work(pair.first) == clusters.work(pair.second) -> DuplicateAnswer.SAME
            pair in distinct -> DuplicateAnswer.DIFFERENT
            else -> null
        }
        val skip = offset.coerceAtLeast(0)
        val wanted = limit.coerceIn(1, MAX_PAGE)
        val search = type.duplicates.search()
        val answeredPairs = linked + distinct
        val answeredFound = type.duplicates.of(answeredPairs)
        val (total, pairs) = if (!answered) {
            val page = ArrayList<DuplicateCandidate>()
            var passed = 0
            // The answered ones are few: the most alike so many more than the page, not every pair sorted
            type.duplicates.forEachByLikeness(skip + wanted + answeredFound.size) { d ->
                if (answerOf(d.pair) == null) {
                    if (passed < skip) passed++ else page += d
                }
                page.size < wanted
            }
            (type.duplicates.count() - answeredFound.size) to page.map { it.pair to it }
        } else {
            val all = answeredPairs.sortedWith(compareByDescending<Pair<ItemKey, ItemKey>> { answeredFound[it]?.similarity ?: -2f }.thenBy { it.first.toString() })
            all.size to all.drop(skip).take(wanted).map { it to answeredFound[it] }
        }
        val described = describe(type, pairs.flatMap { listOf(it.first.first, it.first.second) })
        return DuplicatePage(
            total = total,
            pairs = pairs.mapNotNull { (pair, d) ->
                val first = described[pair.first] ?: return@mapNotNull null
                val second = described[pair.second] ?: return@mapNotNull null
                DuplicatePair(
                    first = first.work, second = second.work,
                    similarity = d?.similarity?.toBigDecimal(), title = d?.title?.toBigDecimal(), description = d?.description?.toBigDecimal(),
                    sameAuthor = first.authors.any { it in second.authors },
                    answer = answerOf(pair)?.let { DuplicatePair.Answer.valueOf(it.name) },
                )
            },
            searching = type.id in searching,
            searchedAt = search?.madeAt?.atOffset(ZoneOffset.UTC),
            works = search?.works,
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
     * The user's word on the pair: one work — linked as the work's window links them, its grade and
     * interest shared, a word that they are different taken back — or different works, a link
     * between them taken away. False when either is no work of the type, or they are one item.
     */
    fun answer(typeId: String, a: ItemKey, b: ItemKey, same: Boolean): Boolean {
        val type = stores.type(typeId) ?: return false
        val first = type.source(a.source)?.takeIf { it.items.find(a.id) != null } ?: return false
        if (type.source(b.source)?.items?.find(b.id) == null || a == b) return false
        val now = Instant.now()
        if (same) {
            type.links.undistinct(a, b)
            if (a.source == b.source) first.corrections.link(a.id, b.id, now) else type.links.link(a, b, now)
            // One work, one grade and one interest mark: written to each of its items
            linkedGrades.share(a)
            linkedInterest.share(a)
        } else {
            if (a.source == b.source) first.corrections.unlink(a.id, b.id)
            type.links.distinct(a, b, now)
        }
        return true
    }

    /** The word on the pair taken back: their link, or that they are different. */
    fun unanswer(typeId: String, a: ItemKey, b: ItemKey) {
        val type = stores.type(typeId) ?: return
        if (a.source == b.source) type.source(a.source)?.corrections?.unlink(a.id, b.id)
        type.links.unlink(a, b)
        type.links.undistinct(a, b)
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        /** The works of every other site kept of every work: its nearest. */
        private const val NEAREST = 2

        /** The cosines held at once, a block of one site's rows against the whole other: memory, not meaning. */
        private const val BLOCK = 16 shl 20

        /** The pairs a page holds at most. */
        private const val MAX_PAGE = 200
    }
}
