package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.interest.LikeliestFirst
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
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
 * IN THE QUEUE OF THE COMPUTATIONS ([Workload]), one at a time with every other computation of the application; of a
 * great many texts a portion at a time, the likeliest works first ([LikeliestFirst]).
 */
@Service
class TextVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val workload: Workload,
    private val windows: TextWindows,
    private val likeliest: LikeliestFirst,
) {
    private val whole = ConcurrentHashMap<String, AtomicBoolean>()
    private val queued = ConcurrentHashMap<String, MutableSet<String>>()

    /** The sources whose every text is to be gone through, as on start, by their planned work. */
    private val wholeAsked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var running = false

    fun running(): Boolean = running

    /** The items written encoded a few seconds after the last write, a work of their source in the queue. */
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        queued.computeIfAbsent(event.source) { ConcurrentHashMap.newKeySet() } += event.ids
        planned(event.source, Instant.now().plusSeconds(DELAY_SECONDS), "items", event.ids.size)
    }

    /** Every source gone through on start, each a work of the queue; a source that fails is told in the log. */
    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    fun start() =
        stores.sources.forEach { s ->
            wholeAsked += s.id
            planned(s.id, null, "start", 1)
        }

    /**
     * The work of the [source] planned for [due] for the [reason]: of what it finds asked when it begins, every text of
     * the source and its windows, else the items queued — planned again and again, it does all that was asked. A
     * PORTION AT A TIME: of more texts than a [PORTION], the likeliest works' ([LikeliestFirst]) are encoded and the
     * rest queued again as a work of their own — a million works of a dump never hold the queue for hours, a grade and
     * a training go between the portions (user, 2026-11-17).
     */
    private fun planned(source: String, due: Instant?, reason: String, count: Int): Unit =
        workload.plan(Work(WHAT, source = source), due, reason, count) {
            val store = stores.source(source) ?: return@plan 0
            val batch = queued[source]?.let { ids -> ids.toList().also { ids.removeAll(it.toSet()) } }.orEmpty()
            val encoded =
                if (wholeAsked.remove(source))
                    refreshed(store, null, { _, _ -> }, { false }, PORTION).also { windows.fillWindows(store) }
                else if (batch.isNotEmpty()) refreshed(store, batch, { _, _ -> }, { false }, PORTION) else Encoded.NONE
            if (encoded.left.isNotEmpty()) {
                queued.computeIfAbsent(source) { ConcurrentHashMap.newKeySet() } += encoded.left
                planned(source, null, "portion", encoded.left.size)
            }
            // How many texts it encoded: what a caller of [refresh] joined to this work waiting gets back
            encoded.done
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
            Work(WHAT, source = store.id, item = only?.singleOrNull()),
            mapOf(reason to (only?.size ?: 1)),
            // A batch of ids is its own: another batch of the source is not this one
            joinAlike = only == null || only.size == 1,
        ) {
            refreshed(store, only, progress, cancelled, null).done
        }

    /** Of an encoding, how many texts were encoded, and the items whose texts were left for another portion. */
    private class Encoded(val done: Int, val left: List<String>) {
        companion object {
            val NONE = Encoded(0, emptyList())
        }
    }

    private fun refreshed(
        store: SourceStore,
        only: Collection<String>?,
        progress: (done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean,
        portion: Int?,
    ): Encoded {
        val encoder = plugins.textEncoder()
        val flag = whole.computeIfAbsent(store.id) { AtomicBoolean(false) }
        if (encoder == null || (only == null && !flag.compareAndSet(false, true))) return Encoded.NONE
        running = true
        try {
            val scan = TextScan(store, only, encoder.id)
            val gone = scan.gone()
            gone.forEach { (id, key) -> store.textVectors.delete(id, key) }
            if (gone.isNotEmpty()) events.publishEvent(TextVectorsChanged(store.id, gone.map { it.first }.distinct()))
            return encoded(store, encoder, scan.todo, progress, cancelled, portion)
        } finally {
            if (only == null) flag.set(false)
            running = false
        }
    }

    /**
     * The texts [todo] encoded by [encoder] and kept, with their windows, a batch at a time while not [cancelled], the
     * likeliest works' first ([LikeliestFirst]): the graded first — the model learns from them, and an hour of
     * re-encoding should not leave it without texts until the end. Of more than a [portion], the texts of the first
     * works reaching it, every text of a work together: the other works left.
     */
    private fun encoded(
        store: SourceStore,
        encoder: TextEncoder,
        todo: List<TextToEncode>,
        progress: (done: Int, total: Int) -> Unit,
        cancelled: () -> Boolean,
        portion: Int?,
    ): Encoded {
        if (todo.isEmpty()) return Encoded.NONE
        val byItem = todo.groupBy { it.itemId }
        val items = likeliest.of(store, byItem.keys)
        val ordered = ArrayList<TextToEncode>()
        var cut = items.size
        for ((i, id) in items.withIndex()) {
            if (portion != null && ordered.size >= portion) {
                cut = i
                break
            }
            ordered += byItem.getValue(id)
        }
        val left = items.subList(cut, items.size).toList()
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
            log.info(
                "{}: {} texts encoded in {} ms, the texts of {} works left for the next portion",
                store.id,
                done,
                System.currentTimeMillis() - started,
                left.size,
            )
        return Encoded(done, left)
    }

    /**
     * Whether one of the [items] has none of its texts encoded yet and a text to encode: captured or written just now,
     * its texts waiting in the queue of the computations. Till they are, the models of the facets do not judge it
     * ([FacetData.judged][io.github.vlsergey.recommend4me.suggestion.FacetData]): its chances are still to come.
     */
    fun awaited(items: Collection<ItemKey>): Boolean {
        val encoder = plugins.textEncoder() ?: return false
        return items.groupBy({ it.source }, { it.id }).any { (source, ids) ->
            val store = stores.source(source) ?: return@any false
            val encoded = store.textVectors.kept(encoder.id, ids).filterValues { it != null }.keys.map { it.first }
            val bare = ids - encoded.toSet()
            bare.isNotEmpty() && TextScan(store, bare, encoder.id).todo.isNotEmpty()
        }
    }

    /**
     * The vector of a search line as a query; null without a text encoder. THE ONE COMPUTATION ON THE THREAD OF A
     * REQUEST ([io.github.vlsergey.recommend4me.workload.Requests]): the search of the line asked for is the answer
     * itself, there is nothing to answer before it; one short line through the encoder, milliseconds, out of the queue.
     */
    fun query(line: String): FloatArray? =
        plugins.textEncoder()?.encode(listOf(line), io.github.vlsergey.recommend4me.encoder.TextKind.QUERY)?.first()

    companion object {
        /** The encoding of the texts of a source, as the queue of the works tells it. */
        private const val WHAT = "textVectors"

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

        /**
         * The texts a work of the queue encodes at most before it queues the rest as a work of its own: a few minutes
         * of the card, not the hours of a dump's million works.
         */
        private const val PORTION = 20_000
        private const val DELAY_SECONDS = 5L

        /** Its step of the start: after the kept pages are read again (1), before the pictures' vectors (4). */
        private const val START_ORDER = 3
    }
}
