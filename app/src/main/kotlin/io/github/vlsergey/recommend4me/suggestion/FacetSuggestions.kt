package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.encoder.TextKind
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.part.PartsSaved
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.vector.Vectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = LoggerFactory.getLogger(FacetSuggestions::class.java)

/** The model gave items of a source other values of a facet it works out: what reads them reads them again. */
data class FacetChancesChanged(val source: String, val ids: List<String>)

/** The values of a facet an item lacks and more likely has than not, as the interface shows them. */
class ItemFacetSuggestions(val facet: FacetDef, val suggested: List<Chance>, val names: Map<String, String>)

/**
 * WHAT THE MODEL SAYS OF THE VALUES OF FACETS, by the suggester plugin, for every facet a source
 * marks [suggest][FacetDef.suggest] or [infer][FacetDef.infer]: the chance of every value a work
 * has, and of every value it lacks that it more likely has than not. Kept in the model database of
 * the content type with what each item's chances were made of — every value of the item, the
 * user's answers, its texts, its chapters — as a fingerprint.
 *
 * The suggester is fitted again whenever anything it learns from has changed (the fingerprint of
 * the whole facet), and every item is then worked out again. A page that asks for an item that
 * never had chances gets them at once, by the weights fitted last.
 *
 * NOTHING IS KEPT BETWEEN OPERATIONS: each one reads the source's items, values and vectors in one
 * pass, and drops them at its end. The sources with changes waiting are the only thing kept.
 */
@Service
class FacetSuggestions(private val stores: Stores, private val plugins: Plugins, private val events: ApplicationEventPublisher) {

    private val waiting: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "suggestions").apply { isDaemon = true } }
    private val scheduled = AtomicBoolean(false)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun facetsOf(store: SourceStore): List<FacetDef> = store.schema.facets.filter { it.suggest || it.infer }

    @EventListener
    fun itemsChanged(event: ItemsChanged) = changed(event.source)

    @EventListener
    fun textVectorsChanged(event: TextVectorsChanged) = changed(event.source)

    @EventListener
    fun partsSaved(event: PartsSaved) = changed(event.source)

    /** Something the source's chances are made of changed: the source is refreshed after the quiet that follows. */
    private fun changed(source: String) {
        val store = stores.source(source) ?: return
        if (facetsOf(store).isEmpty()) return
        waiting += source
        if (scheduled.compareAndSet(false, true)) worker.schedule(::drain, DELAY_SECONDS, TimeUnit.SECONDS)
    }

    private fun drain() {
        scheduled.set(false)
        waiting.toList().forEach { source ->
            waiting -= source
            val store = stores.source(source) ?: return@forEach
            try {
                refresh(store)
            } catch (e: Exception) {
                log.warn("{}: chances were not made: {}", source, e.message, e)
            }
        }
    }

    @Order(4)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        // What was worked out of facets a source no longer has the model work on is forgotten
        stores.sources.forEach { s -> s.suggestions.keepOnly(facetsOf(s).map { it.key }) }
        worker.execute {
            stores.sources.filter { facetsOf(it).isNotEmpty() }.forEach { s ->
                try {
                    refresh(s)
                } catch (e: Exception) {
                    log.error("{}: chances were not made on start", s.id, e)
                }
            }
        }
    }

    /**
     * Fits the suggester of every facet of the source again when what it learns from changed, and
     * works out every item whose fingerprint changed — every item after a fitting.
     */
    fun refresh(store: SourceStore) {
        val suggester = plugins.suggester ?: return
        val encoder = plugins.textEncoder() ?: return
        locks.computeIfAbsent(store.id) { ReentrantLock() }.withLock {
            facetsOf(store).forEach { facet -> refreshFacet(store, facet, suggester, encoder, null) }
        }
    }

    /**
     * The chances of one item made now, for a page that asks for them, by the weights fitted last
     * (fitted now only when there are none); not waiting long for a refresh under way.
     */
    private fun refreshNow(store: SourceStore, itemId: String) {
        val suggester = plugins.suggester ?: return
        val encoder = plugins.textEncoder() ?: return
        val lock = locks.computeIfAbsent(store.id) { ReentrantLock() }
        if (!lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS)) {
            changed(store.id)
            return
        }
        try {
            facetsOf(store).forEach { facet -> refreshFacet(store, facet, suggester, encoder, itemId) }
        } finally {
            lock.unlock()
        }
    }

    /** The facet worked out: of every item whose fingerprint changed — of the item [only], by the weights fitted last, when given. */
    private fun refreshFacet(store: SourceStore, facet: FacetDef, suggester: FacetSuggester, encoder: TextEncoder, only: String?) {
        val started = System.currentTimeMillis()
        val vocabulary = universeVocabulary(store, facet)
        val data = FacetData.load(store, facet, encoder, vocabulary?.values.orEmpty(), ofValues = vocabulary == null)
        if (data.values.isEmpty()) return
        val now = Instant.now()
        val stored = store.suggestions.suggester(facet.key)?.takeIf { it.suggester == suggester.id }
        val kept = stored?.let { suggester.unpack(it.content, data.task) }
        val current = kept != null && stored.basis == data.basis
        val fitted = when {
            current || (only != null && kept != null) -> kept!!
            else -> {
                val made = suggester.fit(data.task) ?: return
                store.suggestions.saveSuggester(facet.key, suggester.id, now, data.basis, made.pack())
                log.info("{}: the suggester of {} fitted on {} items, {} values in {} ms", store.id, facet.key, data.ids.size, data.values.size, System.currentTimeMillis() - started)
                made
            }
        }
        // Fitted on other data: the whole facet is fitted again and worked out in the background
        if (!current && only != null) changed(store.id)
        val refitted = !current && only == null
        val known = store.suggestions.fingerprints(facet.key)
        val rows = data.ids.indices.filter { i ->
            val id = data.ids[i]
            refitted || ((only == null || id == only) && known[id] != data.fingerprints[i])
        }
        if (rows.isEmpty()) return
        val scores = fitted.on(data.task)
        val before = if (facet.infer) store.suggestions.ofItems(rows.map { data.ids[it] }) else emptyMap()
        val changedIds = ArrayList<String>()
        rows.chunked(CHUNK).forEach { chunk ->
            val chances = scores.of(chunk.toIntArray())
            val made = HashMap<String, Long>()
            val out = ArrayList<Chance>()
            chunk.forEachIndexed { k, i ->
                val id = data.ids[i]
                made[id] = data.fingerprints[i]
                val own = chancesOf(id, facet.key, chances[k], data, i, vocabulary?.allowed?.invoke(id))
                out += own
                if (facet.infer && ModelValues.given(own.associateBy { it.key }) != ModelValues.given(before[id]?.get(facet.key).orEmpty())) changedIds += id
            }
            store.suggestions.replace(facet.key, made, out, now)
        }
        if (changedIds.isNotEmpty()) events.publishEvent(FacetChancesChanged(store.id, changedIds))
        if (rows.size > 1) log.info("{}: chances of {} of {} items made in {} ms", store.id, facet.key, rows.size, System.currentTimeMillis() - started)
    }

    /**
     * What is kept of item [i]'s chances: of every value it has, and of every value it lacks, has
     * not been denied and more likely has than not — of those [allowed] to it, when the facet says
     * which: a work's characters are of its universes, its pairings of its characters.
     */
    private fun chancesOf(id: String, facet: String, chances: FloatArray, data: FacetData, i: Int, allowed: Set<String>?): List<Chance> {
        val has = data.task.assigned[i].toHashSet()
        val rejected = data.task.rejected[i].toHashSet()
        return chances.indices.mapNotNull { v ->
            when {
                v in has -> Chance(id, facet, data.values[v], true, chances[v].toDouble())
                v !in rejected && chances[v] > ModelValues.LIKELY && (allowed == null || data.values[v] in allowed) ->
                    Chance(id, facet, data.values[v], false, chances[v].toDouble())
                else -> null
            }
        }
    }

    /**
     * The values of a facet of the universes besides the items' own, with the text their names are
     * encoded from, and the values each item may be given; null for any other facet.
     */
    private class Vocabulary(val values: Map<String, String>, val allowed: ((String) -> Set<String>)?)

    private fun universeVocabulary(store: SourceStore, facet: FacetDef): Vocabulary? {
        val type = stores.typeOf(store.id)
        if (!type.type.universes) return null
        return when (facet.key) {
            UniverseFacets.KIND -> {
                store.items.nameFacetValues(UniverseFacets.KIND, UniverseFacet.KINDS)
                Vocabulary(UniverseFacet.KINDS, null)
            }
            // Every universe of the dictionary, linked to a work or not yet — to a work not known to be original
            UniverseFacets.UNIVERSE -> {
                val kinds = HashMap<String, List<String>>()
                store.items.forEachFacet(UniverseFacets.KIND) { id, keys -> kinds[id] = keys }
                store.corrections.allFacets().filter { it.facet == UniverseFacets.KIND }.groupBy { it.itemId }.forEach { (id, own) ->
                    kinds[id] = Corrected.facets(mapOf(UniverseFacets.KIND to kinds[id].orEmpty()), own)[UniverseFacets.KIND].orEmpty()
                }
                val all = type.universes.all().associate { it.value to it.name }
                Vocabulary(all) { id -> if (kinds[id] == listOf(UniverseFacets.ORIGINAL)) emptySet() else all.keys }
            }
            UniverseFacets.CHARACTERS -> {
                // Every character of every universe — by all its names: the fans write the one they like —
                // and the original characters; a work is given those of its own universes
                val ofUniverse = HashMap<String, Set<String>>()
                val values = HashMap<String, String>(UniverseFacet.ORIGINAL_CHARACTERS)
                type.universes.all().forEach { u ->
                    val characters = type.universes.characters(u.catalogue, u.id)
                    ofUniverse[u.value] = characters.map { UniverseFacet.valueOf(u.catalogue, it.id) }.toSet()
                    characters.forEach { c -> values[UniverseFacet.valueOf(u.catalogue, c.id)] = c.names.joinToString(" / ") }
                }
                store.items.nameFacetValues(UniverseFacets.CHARACTERS, UniverseFacet.ORIGINAL_CHARACTERS)
                val universes = linked(store, UniverseFacets.UNIVERSE)
                Vocabulary(values) { id -> universes[id].orEmpty().flatMap { ofUniverse[it].orEmpty() }.toSet() + UniverseFacet.ORIGINAL_CHARACTERS.keys }
            }
            UniverseFacets.PAIRINGS -> {
                // Every pair of a work's characters: the confirmed ones and those the model gives it
                val characters = linked(store, UniverseFacets.CHARACTERS).toMutableMap()
                store.suggestions.ofFacets(listOf(UniverseFacets.CHARACTERS)).forEach { (id, chances) ->
                    val given = ModelValues.given(chances[UniverseFacets.CHARACTERS].orEmpty())
                    if (given.isNotEmpty()) characters[id] = characters[id].orEmpty() + given
                }
                val rejected = store.corrections.allFacets().filter { it.facet == UniverseFacets.CHARACTERS && !it.added }
                    .groupBy({ it.itemId }, { it.key })
                val pairs = characters.mapValues { (id, own) ->
                    val kept = (own - rejected[id].orEmpty().toSet()).distinct().sorted()
                    kept.flatMapIndexed { k, a -> kept.drop(k + 1).map { b -> UniverseFacet.pairing(a, b) } }.toSet()
                }
                val names = store.items.facetNames(UniverseFacets.CHARACTERS, pairs.values.flatten().flatMap { p -> UniverseFacet.members(p)?.toList().orEmpty() })
                val values = pairs.values.flatten().toSet().associateWith { p ->
                    UniverseFacet.members(p)!!.let { (a, b) -> "${names[a] ?: a} / ${names[b] ?: b}" }
                }
                store.items.nameFacetValues(UniverseFacets.PAIRINGS, values)
                Vocabulary(values) { id -> pairs[id].orEmpty() }
            }
            else -> null
        }
    }

    /** The values of a facet the user linked every item to: item to values. */
    private fun linked(store: SourceStore, facet: String): Map<String, List<String>> =
        store.corrections.allFacets().filter { it.facet == facet && it.added }.groupBy({ it.itemId }, { it.key })

    /**
     * The values to suggest for an item, every facet the source suggests values of, read now with
     * the user's corrections on top: a value the user has answered on is left out. An item that
     * never had chances gets them now; one whose data changed is refreshed in the background.
     */
    fun ofItem(store: SourceStore, itemId: String): List<ItemFacetSuggestions> {
        val facets = facetsOf(store)
        if (facets.isEmpty() || store.items.find(itemId) == null) return emptyList()
        val corrections = store.corrections.facetsOf(itemId)
        val site = store.items.facets(itemId)
        val encoder = plugins.textEncoder()
        if (encoder != null) {
            val fingerprint = FacetData.fingerprint(
                site, corrections, store.textVectors.hashes(encoder.id, itemId).values, store.parts.windowCount(itemId, encoder.id),
            )
            val known = facets.map { store.suggestions.fingerprint(itemId, it.key) }
            if (known.any { it == null }) refreshNow(store, itemId)
            else if (known.any { it != fingerprint }) changed(store.id)
        }
        val chances = store.suggestions.ofItems(listOf(itemId))[itemId].orEmpty()
        return facets.filter { it.suggest && !it.infer }.map { facet ->
            val answered = corrections.filter { it.facet == facet.key }.map { it.key }.toSet()
            val current = site[facet.key].orEmpty().toSet()
            val suggested = chances[facet.key].orEmpty().values
                .filter { !it.had && it.key !in answered && it.key !in current }
                .sortedByDescending { it.chance }
            ItemFacetSuggestions(facet, suggested, store.items.facetNames(facet.key, suggested.map { it.key }))
        }
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        /** The quiet after the last change before a source is refreshed: a page sends itself several times as it grows. */
        private const val DELAY_SECONDS = 5L

        /** How long a page waits for a refresh under way before its item is left to it. */
        private const val WAIT_SECONDS = 5L

        /** Items scored at once and written in one transaction: memory, not meaning. */
        private const val CHUNK = 2_000
    }
}

/**
 * Everything of one facet of a source a suggester is handed, read in one pass per table: every
 * item with its texts in every view and its values, every value with the vector of its name, the
 * values of the item's other facets as its context.
 */
internal class FacetData(
    val ids: List<String>,
    val values: List<String>,
    val task: SuggestionTask,
    val fingerprints: LongArray,
    /** The fingerprint of the whole facet: of every item's. */
    val basis: Long,
) {
    companion object {
        /** What an item's chances are made of: every value of it, the user's answers, its texts' hashes, its chapters. */
        fun fingerprint(site: Map<String, List<String>>, corrections: List<FacetCorrection>, textHashes: Collection<String>, windows: Int): Long =
            Vectors.keyOf(
                site.entries.sortedBy { it.key }.joinToString("\u0001") { (f, keys) -> "$f=" + keys.sorted().joinToString(",") } + "\u0002" +
                    corrections.sortedWith(compareBy({ it.facet }, { it.key })).joinToString("\u0001") { "${it.facet}:${it.key}=${it.added}" } + "\u0002" +
                    textHashes.sorted().joinToString("\u0001") + "\u0002" + windows,
            )

        /**
         * [known]: values of the facet the application knows besides the items' — the universes and
         * characters of the dictionary — with the text their names are encoded from. [ofValues]: the
         * facet's line on the site ([FacetDef.original]) is the facet's own values written out.
         */
        fun load(store: SourceStore, facet: FacetDef, encoder: TextEncoder, known: Map<String, String> = emptyMap(), ofValues: Boolean = true): FacetData {
            val schema = store.schema
            val ids = store.items.keys().map { it.id }
            val site = HashMap<String, HashMap<String, MutableList<String>>>()
            store.items.forEachFacetValue { id, f, key -> site.getOrPut(id) { HashMap() }.getOrPut(f) { ArrayList() } += key }
            val corrections = store.corrections.allFacets().groupBy { it.itemId }
            val corrected = ids.associateWith { id -> Corrected.facets(site[id].orEmpty(), corrections[id].orEmpty()) }

            // Every value of the facet the site or the user gave any item, and every value known besides
            val values = (corrected.values.flatMap { it[facet.key].orEmpty() } +
                corrections.values.flatten().filter { it.facet == facet.key }.map { it.key } + known.keys).distinct().sorted()
            val index = values.withIndex().associate { (i, k) -> k to i }

            // The other facets' values as the context — of the facets the source has now
            val facets = schema.facets.map { it.key }.toSet()
            val contextIndex = HashMap<String, Int>()
            val context = ids.map { id ->
                corrected[id].orEmpty().filterKeys { it != facet.key && it in facets }
                    .flatMap { (f, keys) -> keys.map { "$f\u0001$it" } }
                    .map { contextIndex.getOrPut(it) { contextIndex.size } }.distinct().toIntArray()
            }

            val d = encoder.dim
            val described = schema.texts.filter { it.block != null && it.key != facet.original }.map { it.key }
            val views = ArrayList<TextView>()
            views += TextView("description", mean(ids, d) { action -> store.textVectors.forEach(encoder.id, described) { id, _, v -> action(id, v) } })
            if (schema.partsLabel != null) {
                val windows = store.parts.windows(store.parts.windowCounts(encoder.id).keys.toList(), encoder.id)
                views += TextView("parts", mean(ids, d) { action -> windows.forEach { (id, list) -> list.forEach { action(id, it) } } })
            }
            facet.original?.let { key ->
                views += TextView("original", mean(ids, d) { action -> store.textVectors.forEach(encoder.id, listOf(key)) { id, _, v -> action(id, v) } }, ofValues)
            }

            val names = Matrix(values.size, d)
            nameVectors(store, facet.key, values, corrections.values.flatten(), known, encoder).forEach { (key, v) ->
                index[key]?.let { names.held.put(v, 0, names.row(it), d) }
            }

            fun indices(list: Collection<String>) = list.mapNotNull { index[it] }.distinct().toIntArray()
            val task = SuggestionTask(
                views = views,
                values = names,
                assigned = ids.map { indices(corrected[it]?.get(facet.key).orEmpty()) },
                confirmed = ids.map { id -> indices(corrections[id].orEmpty().filter { it.facet == facet.key && it.added }.map { it.key }) },
                rejected = ids.map { id -> indices(corrections[id].orEmpty().filter { it.facet == facet.key && !it.added }.map { it.key }) },
                context = context,
                contextCount = contextIndex.size,
            )
            val hashes = HashMap<String, MutableList<String>>()
            store.textVectors.hashes(encoder.id).forEach { (k, hash) -> hashes.getOrPut(k.first) { ArrayList() } += hash }
            val windowCounts = store.parts.windowCounts(encoder.id)
            val fingerprints = LongArray(ids.size) { i ->
                val id = ids[i]
                fingerprint(site[id].orEmpty(), corrections[id].orEmpty(), hashes[id].orEmpty(), windowCounts[id] ?: 0)
            }
            // The values chosen from are part of what is learnt: a universe added or removed fits again
            val basis = Vectors.keyOf(ids.indices.sortedBy { ids[it] }.joinToString(",") { "${ids[it]}:${fingerprints[it]}" } + "\u0002" + values.joinToString("\u0001"))
            return FacetData(ids, values, task, fingerprints, basis)
        }

        /** The mean direction of the vectors [read] hands over for each item, a row each; zeros for an item without any. */
        private fun mean(ids: List<String>, d: Int, read: ((String, FloatArray) -> Unit) -> Unit): Matrix {
            val sums = HashMap<String, FloatArray>()
            read { id, v ->
                val sum = sums.getOrPut(id) { FloatArray(d) }
                for (q in 0 until minOf(d, v.size)) sum[q] += v[q]
            }
            val out = Matrix(ids.size, d)
            ids.forEachIndexed { i, id -> sums[id]?.let { Vectors.meanDirection(listOf(it)) }?.let { out.held.put(it, 0, out.row(i), d) } }
            return out
        }

        /** The vectors of the values' names as queries — of [known]'s texts for those it has — encoded now where missing or the name changed. */
        private fun nameVectors(
            store: SourceStore, facet: String, values: List<String>, corrections: List<FacetCorrection>, known: Map<String, String>, encoder: TextEncoder,
        ): Map<String, FloatArray> {
            val names = store.items.facetNames(facet, values) + corrections.filter { it.facet == facet && it.name != null }.associate { it.key to it.name!! } + known
            val stored = store.valueNames.of(facet, encoder.id)
            val out = HashMap<String, FloatArray>()
            val todo = ArrayList<Pair<String, String>>()
            values.forEach { key ->
                val name = names[key] ?: key
                val have = stored[key]
                if (have != null && have.first == Vectors.hash(name)) out[key] = have.second else todo += key to name
            }
            todo.chunked(ENCODE_BATCH).forEach { batch ->
                val vectors = encoder.encode(batch.map { it.second }, TextKind.QUERY)
                val rows = batch.indices.mapNotNull { k -> vectors[k]?.let { Triple(batch[k].first, Vectors.hash(batch[k].second), it) } }
                store.valueNames.save(facet, encoder.id, rows)
                rows.forEach { (key, _, v) -> out[key] = v }
            }
            return out
        }

        /** Names encoded in one call: memory, not meaning. */
        private const val ENCODE_BATCH = 64
    }
}
