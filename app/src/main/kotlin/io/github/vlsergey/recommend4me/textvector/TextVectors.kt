package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import jakarta.annotation.PreDestroy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(TextVectors::class.java)

/** The vectors of the texts of these items of a source were written or dropped. */
data class TextVectorsChanged(val source: String, val ids: List<String>)

/**
 * Vectors of the items' texts — those the model reads
 * ([TextDef.block][io.github.vlsergey.recommend4me.source.TextDef.block]), those searched by meaning, and the title
 * ([TITLE]) — of the text as the user corrected it, kept with its hash and the encoder that made them: a text is
 * encoded again when it or the encoder changed.
 *
 * On start every source is gone through; afterwards the items whose texts were written are encoded a few seconds after
 * the last write — the queue of their ids is the only thing kept here.
 *
 * IN THE QUEUE OF THE COMPUTATIONS ([Workload]), each encoding whole, one at a time with every other computation of the
 * application.
 */
@Service
class TextVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val workload: Workload,
    private val windows: TextWindows,
) {
    private val whole = ConcurrentHashMap<String, AtomicBoolean>()
    private val queued = ConcurrentHashMap<String, MutableSet<String>>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "text-vectors").apply { isDaemon = true }
    }
    private val scheduled = AtomicBoolean(false)

    @Volatile private var running = false

    fun running(): Boolean = running

    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        queued.computeIfAbsent(event.source) { ConcurrentHashMap.newKeySet() } += event.ids
        if (scheduled.compareAndSet(false, true)) worker.schedule(::drain, DELAY_SECONDS, TimeUnit.SECONDS)
    }

    // The worker's loop: a source that fails any way is told in the log and leaves the others to be encoded
    @Suppress("TooGenericExceptionCaught")
    private fun drain() {
        scheduled.set(false)
        queued.keys.forEach { source ->
            val ids = queued[source] ?: return@forEach
            val batch = ids.toList()
            ids.removeAll(batch.toSet())
            val store = stores.source(source) ?: return@forEach
            try {
                refresh(store, "items", only = batch)
            } catch (e: Exception) {
                log.warn("{}: texts of {} items were not encoded: {}", source, batch.size, e.message)
            }
        }
    }

    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    // The worker's pass on start: a source that fails any way is told in the log and leaves the others to be encoded
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        worker.execute {
            stores.sources.forEach { s ->
                try {
                    refresh(s, "start")
                    windows.fillWindows(s)
                } catch (e: Exception) {
                    log.error("{}: text vectors were not refreshed on start", s.id, e)
                }
            }
        }
    }

    /**
     * Encodes every text of the source whose text is new or changed, and drops the vectors of texts that are gone; with
     * [only], of those items. Returns how many texts were encoded. In the queue of the computations, for the [reason] —
     * a key the interface words.
     */
    fun refresh(
        store: SourceStore,
        reason: String,
        only: Collection<String>? = null,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Int =
        workload.run(
            Work("textVectors", source = store.id, item = only?.singleOrNull()),
            mapOf(reason to (only?.size ?: 1)),
            // A batch of ids is its own: another batch of the source is not this one
            joinAlike = only == null || only.size == 1,
        ) {
            refreshed(store, only, progress, cancelled)
        }

    private fun refreshed(
        store: SourceStore,
        only: Collection<String>?,
        progress: (done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean,
    ): Int {
        val encoder = plugins.textEncoder()
        val flag = whole.computeIfAbsent(store.id) { AtomicBoolean(false) }
        if (encoder == null || (only == null && !flag.compareAndSet(false, true))) return 0
        running = true
        try {
            val scan = TextScan(store, only, encoder.id)
            val gone = scan.gone()
            gone.forEach { (id, key) -> store.textVectors.delete(id, key) }
            if (gone.isNotEmpty()) events.publishEvent(TextVectorsChanged(store.id, gone.map { it.first }.distinct()))
            return encoded(store, encoder, scan.todo, progress, cancelled)
        } finally {
            if (only == null) flag.set(false)
            running = false
        }
    }

    /**
     * The texts [todo] encoded by [encoder] and kept, with their windows, a batch at a time while not [cancelled]: the
     * graded items first — the model learns from them, and an hour of re-encoding should not leave it without texts
     * until the end. How many were encoded.
     */
    private fun encoded(
        store: SourceStore,
        encoder: TextEncoder,
        todo: List<TextToEncode>,
        progress: (done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean,
    ): Int {
        if (todo.isEmpty()) return 0
        val graded = store.ratings.gradedItems()
        val ordered = todo.sortedByDescending { it.itemId in graded }
        if (ordered.size > BATCH) log.info("{}: encoding {} texts by {}", store.id, ordered.size, encoder.id)
        val started = System.currentTimeMillis()
        var done = 0
        for (batch in ordered.chunked(BATCH)) {
            if (cancelled()) break
            val vectors = encoder.encode(batch.map { it.text })
            batch.forEachIndexed { i, t ->
                vectors[i]?.let { store.textVectors.save(t.itemId, t.key, encoder.id, t.hash, it) }
            }
            windows.keepWindows(
                store,
                batch.filterIndexed { i, _ -> vectors[i] != null }.map { Triple(it.itemId, it.key, it.text) },
            )
            events.publishEvent(TextVectorsChanged(store.id, batch.map { it.itemId }.distinct()))
            done += batch.size
            progress(done, ordered.size)
        }
        if (ordered.size > BATCH)
            log.info("{}: {} texts encoded in {} ms", store.id, done, System.currentTimeMillis() - started)
        return done
    }

    /** The vector of a search line as a query; null without a text encoder. */
    fun query(line: String): FloatArray? =
        plugins.textEncoder()?.encode(listOf(line), io.github.vlsergey.recommend4me.encoder.TextKind.QUERY)?.first()

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        /** The key the title of an item is kept under among its texts' vectors. */
        const val TITLE = "title"

        /**
         * Every text of the source's items the models read, by its key, with the block of the models' input it is and
         * the key of its label: every text that has vectors — those of the source's own blocks, the lines searched by
         * meaning (the site's line of characters) — and the title.
         */
        fun blocks(source: String, schema: SourceSchema): Map<String, Pair<String, String>> =
            schema.texts
                .filter { it.block != null || it.searchByMeaning }
                .associate { it.key to ((it.block ?: "text:${it.key}") to I18nKeys.textLabel(source, it.key)) } +
                (TITLE to ("text:$TITLE" to TITLE_LABEL))

        /** The key of the label of the title among the texts: "standard:text.title". */
        val TITLE_LABEL = I18nKeys.qualified(I18nKeys.STANDARD, "text.title")

        /**
         * Texts per encoder call; their windows share batches of the graph, sorted by length among them: of a few, the
         * graph's batches ran half full.
         */
        private const val BATCH = 128
        private const val DELAY_SECONDS = 5L

        /** Its step of the start: after the kept pages are read again (1), before the pictures' vectors (4). */
        private const val START_ORDER = 3
    }
}
