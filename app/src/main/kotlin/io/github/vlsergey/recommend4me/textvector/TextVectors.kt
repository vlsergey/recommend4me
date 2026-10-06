package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.vector.Vectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger(TextVectors::class.java)

/** The vectors of the texts of these items of a source were written or dropped. */
data class TextVectorsChanged(val source: String, val ids: List<String>)

/**
 * Vectors of the items' texts — those the model reads ([TextDef.block][io.github.vlsergey.recommend4me.source.TextDef.block])
 * and those searched by meaning — of the text as the user corrected it, kept with its hash and the
 * encoder that made them: a text is encoded again when it or the encoder changed.
 *
 * On start every source is gone through; afterwards the items whose texts were written are encoded
 * a few seconds after the last write — the queue of their ids is the only thing kept here.
 *
 * NO LOCK OVER AN ENCODING: re-encoding a catalogue after a change of encoder takes an hour; only
 * one WHOLE refresh of a source runs at a time, while refreshes of single items always run.
 */
@Service
class TextVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
) {
    private val whole = ConcurrentHashMap<String, AtomicBoolean>()
    private val queued = ConcurrentHashMap<String, MutableSet<String>>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "text-vectors").apply { isDaemon = true } }
    private val scheduled = AtomicBoolean(false)

    @Volatile
    private var running = false

    fun running(): Boolean = running

    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        queued.computeIfAbsent(event.source) { ConcurrentHashMap.newKeySet() } += event.ids
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
                refresh(store, only = batch)
            } catch (e: Exception) {
                log.warn("{}: texts of {} items were not encoded: {}", source, batch.size, e.message)
            }
        }
    }

    @Order(3)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        worker.execute {
            stores.sources.forEach { s ->
                try {
                    refresh(s)
                } catch (e: Exception) {
                    log.error("{}: text vectors were not refreshed on start", s.id, e)
                }
            }
        }
    }

    /**
     * Encodes every text of the source whose text is new or changed, and drops the vectors of texts
     * that are gone; with [only], of those items. Returns how many texts were encoded.
     */
    fun refresh(
        store: SourceStore,
        only: Collection<String>? = null,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Int {
        val encoder = plugins.textEncoder() ?: return 0
        val flag = whole.computeIfAbsent(store.id) { AtomicBoolean(false) }
        if (only == null && !flag.compareAndSet(false, true)) return 0
        running = true
        try {
            val keys = store.schema.texts.filter { it.block != null || it.searchByMeaning }.map { it.key }
            if (keys.isEmpty()) return 0
            val stored = if (only == null) store.textVectors.hashes(encoder.id) else only.flatMap { store.textVectors.hashes(encoder.id, it).entries }.associate { it.toPair() }
            val known = if (only == null) store.textVectors.keys() else only.flatMap { store.textVectors.keys(it) }.toSet()
            val fields = if (only == null) store.corrections.allFields() else store.corrections.fieldsOf(only)

            class Todo(val itemId: String, val key: String, val text: String, val hash: String)
            val todo = ArrayList<Todo>()
            val present = HashSet<Pair<String, String>>()
            fun consider(itemId: String, site: Map<String, String>) {
                val texts = Corrected.texts(site, fields[itemId].orEmpty())
                keys.forEach { key ->
                    val text = texts[key] ?: return@forEach
                    present += itemId to key
                    val hash = Vectors.hash(text)
                    if (stored[itemId to key] != hash) todo += Todo(itemId, key, text, hash)
                }
            }
            if (only == null) store.items.forEachTexts(keys) { id, texts -> consider(id, texts) }
            else store.items.textsOf(only, keys).let { all -> only.forEach { id -> consider(id, all[id].orEmpty()) } }
            val gone = known.filter { it !in present && (only == null || it.first in only) }
            gone.forEach { (id, key) -> store.textVectors.delete(id, key) }
            if (gone.isNotEmpty()) events.publishEvent(TextVectorsChanged(store.id, gone.map { it.first }.distinct()))
            if (todo.isEmpty()) return 0

            // The graded items first: the model learns from them, and an hour of re-encoding should
            // not leave it without texts until the end
            val graded = store.ratings.gradedItems()
            todo.sortByDescending { it.itemId in graded }
            if (todo.size > BATCH) log.info("{}: encoding {} texts by {}", store.id, todo.size, encoder.id)
            val started = System.currentTimeMillis()
            var done = 0
            for (batch in todo.chunked(BATCH)) {
                if (cancelled()) break
                val vectors = encoder.encode(batch.map { it.text })
                batch.forEachIndexed { i, t ->
                    vectors[i]?.let { store.textVectors.save(t.itemId, t.key, encoder.id, t.hash, it) }
                }
                events.publishEvent(TextVectorsChanged(store.id, batch.map { it.itemId }.distinct()))
                done += batch.size
                progress(done, todo.size)
            }
            if (todo.size > BATCH) log.info("{}: {} texts encoded in {} ms", store.id, done, System.currentTimeMillis() - started)
            return done
        } finally {
            if (only == null) flag.set(false)
            running = false
        }
    }

    /** The vector of a search line as a query; null without a text encoder. */
    fun query(line: String): FloatArray? =
        plugins.textEncoder()?.encode(listOf(line), io.github.vlsergey.recommend4me.encoder.TextKind.QUERY)?.first()

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        /** Texts per encoder call; their windows share batches of the graph. */
        private const val BATCH = 32
        private const val DELAY_SECONDS = 5L
    }
}
