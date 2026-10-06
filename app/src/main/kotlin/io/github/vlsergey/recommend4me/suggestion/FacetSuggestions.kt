package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.encoder.TextKind
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.vector.Vectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = LoggerFactory.getLogger(FacetSuggestions::class.java)

/** The suggestions of one facet of an item as the interface shows them: values to add, and values of the site's that do not fit. */
class ItemFacetSuggestions(val facet: FacetDef, val suggested: List<Suggestion>, val doubted: List<Suggestion>, val names: Map<String, String>)

/**
 * THE VALUES A WORK SHOULD HAVE, worked out by the suggester plugin for every facet a source marks
 * [suggest][FacetDef.suggest]: from the work's text, its other values, and every other work of the
 * source. Kept in the model database of the content type, with what each item's suggestions were
 * made of — its values, the user's word on them, its texts — as a fingerprint.
 *
 * Items whose data changed are worked out again a few seconds after the last change; an item that
 * never had suggestions — a page just opened — at once, when it is asked for. The suggester is
 * fitted again when the user's word on the facet has grown by [REFIT_FEEDBACK] answers or a tenth,
 * or after [REFIT_AFTER]; then every item is worked out again.
 *
 * NOTHING IS KEPT BETWEEN OPERATIONS: each one reads the source's items, values and vectors in one
 * pass, and drops them at its end. The queue of the items to work out is the only thing kept.
 */
@Service
class FacetSuggestions(private val stores: Stores, private val plugins: Plugins) {

    private val queued = ConcurrentHashMap<String, MutableSet<String>>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "suggestions").apply { isDaemon = true } }
    private val scheduled = AtomicBoolean(false)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun facetsOf(store: SourceStore): List<FacetDef> = store.source.schema.facets.filter { it.suggest }

    @EventListener
    fun itemsChanged(event: ItemsChanged) = queue(event.source, event.ids)

    @EventListener
    fun textVectorsChanged(event: TextVectorsChanged) = queue(event.source, event.ids)

    private fun queue(source: String, ids: Collection<String>) {
        val store = stores.source(source) ?: return
        if (facetsOf(store).isEmpty() || ids.isEmpty()) return
        queued.computeIfAbsent(source) { ConcurrentHashMap.newKeySet() } += ids
        if (scheduled.compareAndSet(false, true)) worker.schedule(::drain, DELAY_SECONDS, TimeUnit.SECONDS)
    }

    private fun drain() {
        scheduled.set(false)
        queued.keys.forEach { source ->
            val ids = queued[source] ?: return@forEach
            val batch = ids.toList()
            ids.removeAll(batch.toSet())
            val store = stores.source(source) ?: return@forEach
            try {
                refresh(store, batch)
            } catch (e: Exception) {
                log.warn("{}: suggestions of {} items were not made: {}", source, batch.size, e.message, e)
            }
        }
    }

    /** After the texts are encoded on start: every item whose suggestions are missing or stale. */
    @Order(4)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        worker.execute {
            stores.sources.filter { facetsOf(it).isNotEmpty() }.forEach { s ->
                try {
                    refresh(s, null)
                } catch (e: Exception) {
                    log.error("{}: suggestions were not made on start", s.id, e)
                }
            }
        }
    }

    /**
     * Works out the suggestions of the items [only] — of every item whose fingerprint changed when
     * null — of every suggested facet of the source; of every item after the suggester is fitted again.
     */
    fun refresh(store: SourceStore, only: Collection<String>?) {
        val suggester = plugins.suggester ?: return
        val encoder = plugins.textEncoder() ?: return
        locks.computeIfAbsent(store.id) { ReentrantLock() }.withLock {
            facetsOf(store).forEach { facet -> refreshFacet(store, facet, suggester, encoder, only?.toSet(), fit = true) }
        }
    }

    /**
     * The suggestions of one item made now, for a page that asks for them: by the suggester as it
     * was fitted (fitted now only when it never was), and not waiting long for a refresh under way.
     */
    private fun refreshNow(store: SourceStore, itemId: String) {
        val suggester = plugins.suggester ?: return
        val encoder = plugins.textEncoder() ?: return
        val lock = locks.computeIfAbsent(store.id) { ReentrantLock() }
        if (!lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS)) {
            queue(store.id, listOf(itemId))
            return
        }
        try {
            facetsOf(store).forEach { facet ->
                refreshFacet(store, facet, suggester, encoder, setOf(itemId), fit = store.suggestions.suggester(facet.key) == null)
            }
        } finally {
            lock.unlock()
        }
    }

    private fun refreshFacet(store: SourceStore, facet: FacetDef, suggester: FacetSuggester, encoder: TextEncoder, only: Set<String>?, fit: Boolean) {
        val started = System.currentTimeMillis()
        val data = FacetData.load(store, facet.key, encoder)
        if (data.ids.size < MIN_ITEMS || data.values.isEmpty()) return
        val now = Instant.now()
        val stored = store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id }
        // A few answers more do not refit — every item would be worked out again after each click
        val fresh = stored != null && kotlin.math.abs(data.feedback - stored.feedback) < maxOf(REFIT_FEEDBACK, stored.feedback / 10) &&
            stored.fittedAt.isAfter(now.minus(REFIT_AFTER))
        val refit = !fresh && (fit || stored == null)
        val fitted = if (!refit) suggester.unpack(stored!!.content) else {
            suggester.fit(data.task).also {
                store.suggestions.saveSuggester(facet.key, suggester.id, now, data.feedback, it.pack())
                log.info("{}: the suggester of {} fitted on {} items, {} values", store.id, facet.key, data.ids.size, data.values.size)
            }
        }
        if (!fresh && !refit) queue(store.id, only.orEmpty())
        val known = store.suggestions.fingerprints(facet.key)
        val rows = data.ids.indices.filter { i ->
            val id = data.ids[i]
            when {
                refit -> true
                only != null -> id in only && known[id] != data.fingerprints[i]
                else -> known[id] != data.fingerprints[i]
            }
        }
        if (rows.isEmpty()) return
        val scores = fitted.on(data.task)
        rows.chunked(CHUNK).forEach { chunk ->
            val chances = scores.of(chunk.toIntArray())
            val made = HashMap<String, Long>()
            val suggestions = ArrayList<Suggestion>()
            chunk.forEachIndexed { k, i ->
                val id = data.ids[i]
                made[id] = data.fingerprints[i]
                suggestions += pick(id, facet.key, chances[k], data, i)
            }
            store.suggestions.replace(facet.key, made, suggestions, now)
        }
        if (rows.size > 1) log.info("{}: {} suggestions of {} items made in {} ms", store.id, facet.key, rows.size, System.currentTimeMillis() - started)
    }

    /** The values to suggest for item [i] and the site's values to doubt, by their [chances]. */
    private fun pick(id: String, facet: String, chances: FloatArray, data: FacetData, i: Int): List<Suggestion> {
        val has = data.task.assigned[i].toHashSet()
        val confirmed = data.task.confirmed[i].toHashSet()
        val rejected = data.task.rejected[i].toHashSet()
        val suggested = chances.indices.filter { it !in has && it !in rejected && chances[it] >= SUGGEST_FROM }
            .sortedByDescending { chances[it] }.take(MAX_SUGGESTED)
            .map { Suggestion(id, facet, data.values[it], true, chances[it].toDouble()) }
        val doubted = has.filter { it !in confirmed && chances[it] < DOUBT_BELOW }
            .map { Suggestion(id, facet, data.values[it], false, chances[it].toDouble()) }
        return suggested + doubted
    }

    /**
     * The suggestions of an item for the interface, every suggested facet, read now with the
     * user's corrections on top: a value the user has answered on since is left out. An item that
     * never had suggestions gets them now; one whose data changed is queued.
     */
    fun ofItem(store: SourceStore, itemId: String): List<ItemFacetSuggestions> {
        val facets = facetsOf(store)
        if (facets.isEmpty() || store.items.find(itemId) == null) return emptyList()
        val corrections = store.corrections.facetsOf(itemId)
        val site = store.items.facets(itemId)
        val encoder = plugins.textEncoder()
        val hashes = encoder?.let { e -> store.textVectors.hashes(e.id, itemId) }.orEmpty()
        val blocks = FacetData.textKeys(store)
        val stale = facets.filter { facet ->
            val own = corrections.filter { it.facet == facet.key }
            val now = FacetData.fingerprint(site[facet.key].orEmpty(), own, blocks.mapNotNull { hashes[itemId to it] })
            store.suggestions.fingerprint(itemId, facet.key) != now
        }
        if (stale.isNotEmpty()) {
            if (stale.any { store.suggestions.fingerprint(itemId, it.key) == null }) refreshNow(store, itemId)
            else queue(store.id, listOf(itemId))
        }
        val made = store.suggestions.ofItems(listOf(itemId))[itemId].orEmpty()
        return facets.map { facet ->
            val own = corrections.filter { it.facet == facet.key }
            val answered = own.map { it.key }.toSet()
            val current = Corrected.facets(mapOf(facet.key to site[facet.key].orEmpty()), own)[facet.key].orEmpty().toSet()
            val list = made.filter { it.facet == facet.key && it.key !in answered }
            val suggested = list.filter { it.suggested && it.key !in current }
            val doubted = list.filter { !it.suggested && it.key in current }
            val names = store.items.facetNames(facet.key, (suggested + doubted).map { it.key }) +
                own.filter { it.name != null }.associate { it.key to it.name!! }
            ItemFacetSuggestions(facet, suggested, doubted, names)
        }
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        private const val DELAY_SECONDS = 5L

        /** How long a page waits for a refresh under way before its item is only queued. */
        private const val WAIT_SECONDS = 5L

        /** Fewer items than this teach nothing. */
        private const val MIN_ITEMS = 30

        /** Items scored at once and written in one transaction. */
        private const val CHUNK = 2_000

        private val REFIT_AFTER: Duration = Duration.ofHours(12)

        /** The user's answers on a facet since the last fitting that make it fitted again. */
        private const val REFIT_FEEDBACK = 10

        const val SUGGEST_FROM = 0.3f
        const val DOUBT_BELOW = 0.05f
        private const val MAX_SUGGESTED = 12
    }
}

/**
 * Everything of one facet of a source a suggester is handed, read in one pass per table: every
 * item with the vector of its texts and its values, every value with the vector of its name.
 */
internal class FacetData(
    val ids: List<String>,
    val values: List<String>,
    val task: SuggestionTask,
    val fingerprints: LongArray,
    /** How many corrections of the facet the user has made. */
    val feedback: Int,
) {
    companion object {
        /** A value is suggested once this many items have it — or the user gave it to one. */
        private const val MIN_VALUE_ITEMS = 3

        /** The texts the model reads: what a work is about. */
        fun textKeys(store: SourceStore): List<String> = store.source.schema.texts.filter { it.block != null }.map { it.key }

        fun fingerprint(site: List<String>, corrections: List<FacetCorrection>, textHashes: List<String>): Long =
            Vectors.keyOf(
                site.sorted().joinToString("\u0001") + "\u0002" +
                    corrections.sortedBy { it.key }.joinToString("\u0001") { "${it.key}=${it.added}" } + "\u0002" +
                    textHashes.sorted().joinToString("\u0001"),
            )

        fun load(store: SourceStore, facet: String, encoder: TextEncoder): FacetData {
            val ids = store.items.keys().map { it.id }
            val site = HashMap<String, List<String>>()
            store.items.forEachFacet(facet) { id, keys -> site[id] = keys }
            val corrections = store.corrections.allFacets().filter { it.facet == facet }.groupBy { it.itemId }
            val corrected = ids.map { id ->
                val own = corrections[id].orEmpty()
                if (own.isEmpty()) site[id].orEmpty() else Corrected.facets(mapOf(facet to site[id].orEmpty()), own)[facet].orEmpty()
            }

            // The values worth suggesting: on a few items, or given by the user
            val counts = HashMap<String, Int>()
            corrected.forEach { keys -> keys.forEach { counts.merge(it, 1, Int::plus) } }
            val byUser = corrections.values.flatten().filter { it.added }.map { it.key }.toSet()
            val values = counts.filter { (k, n) -> n >= MIN_VALUE_ITEMS || k in byUser }.keys.sorted()
            val index = values.withIndex().associate { (i, k) -> k to i }

            val keys = textKeys(store)
            val d = encoder.dim
            val sums = HashMap<String, FloatArray>()
            val hashes = HashMap<String, MutableList<String>>()
            store.textVectors.forEach(encoder.id, keys) { id, _, v ->
                val sum = sums.getOrPut(id) { FloatArray(d) }
                for (q in 0 until minOf(d, v.size)) sum[q] += v[q]
            }
            store.textVectors.hashes(encoder.id).forEach { (k, hash) -> if (k.second in keys) hashes.getOrPut(k.first) { ArrayList() } += hash }

            val items = Matrix(ids.size, d)
            ids.forEachIndexed { i, id -> sums[id]?.let { Vectors.meanDirection(listOf(it)) }?.let { items.held.put(it, 0, items.row(i), d) } }
            val names = Matrix(values.size, d)
            nameVectors(store, facet, values, corrections.values.flatten(), encoder).forEach { (key, v) ->
                index[key]?.let { names.held.put(v, 0, names.row(it), d) }
            }

            fun indices(list: Collection<String>) = list.mapNotNull { index[it] }.distinct().toIntArray()
            val task = SuggestionTask(
                items = items,
                values = names,
                assigned = corrected.map(::indices),
                confirmed = ids.map { id -> indices(corrections[id].orEmpty().filter { it.added }.map { it.key }) },
                rejected = ids.map { id -> indices(corrections[id].orEmpty().filter { !it.added }.map { it.key }) },
            )
            val fingerprints = LongArray(ids.size) { i ->
                val id = ids[i]
                fingerprint(site[id].orEmpty(), corrections[id].orEmpty(), hashes[id].orEmpty())
            }
            return FacetData(ids, values, task, fingerprints, corrections.values.sumOf { it.size })
        }

        /** The vectors of the values' names as queries, encoded now where missing or the name changed. */
        private fun nameVectors(
            store: SourceStore, facet: String, values: List<String>, corrections: List<FacetCorrection>, encoder: TextEncoder,
        ): Map<String, FloatArray> {
            val names = store.items.facetNames(facet, values) + corrections.filter { it.name != null }.associate { it.key to it.name!! }
            val stored = store.valueNames.of(facet, encoder.id)
            val out = HashMap<String, FloatArray>()
            val todo = ArrayList<Pair<String, String>>()
            values.forEach { key ->
                val name = names[key] ?: key
                val have = stored[key]
                if (have != null && have.first == Vectors.hash(name)) out[key] = have.second else todo += key to name
            }
            todo.chunked(64).forEach { batch ->
                val vectors = encoder.encode(batch.map { it.second }, TextKind.QUERY)
                val rows = batch.indices.mapNotNull { k -> vectors[k]?.let { Triple(batch[k].first, Vectors.hash(batch[k].second), it) } }
                store.valueNames.save(facet, encoder.id, rows)
                rows.forEach { (key, _, v) -> out[key] = v }
            }
            return out
        }
    }
}
