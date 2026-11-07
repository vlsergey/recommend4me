package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.textvector.TextVectors.Companion.TITLE
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(TextWindows::class.java)

/**
 * THE WINDOWS OF THE LONG TEXTS of the items: how many each text whose vector is kept has, and of a text of more than
 * one the vector of every window — what the search finds a piece of a long text by. Made with the text's vector
 * ([TextVectors]), and of the texts encoded before the windows were kept, in the background.
 */
@Component
class TextWindows(
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    private val workload: Workload,
) {

    /** The windows of the texts just encoded — (item, text key, text) — kept. */
    fun keepWindows(store: SourceStore, texts: List<Triple<String, String, String>>) {
        val encoder = plugins.textEncoder() ?: return
        val counts = texts.map { encoder.windowSpans(it.third).size }
        val long = texts.indices.filter { counts[it] > 1 }
        val windows = if (long.isEmpty()) emptyList() else encoder.encodeWindows(long.map { texts[it].third })
        val of = long.withIndex().associate { (k, i) -> i to windows[k] }
        texts.forEachIndexed { i, (id, key, _) ->
            store.textVectors.windows.saveWindows(id, key, counts[i], of[i].orEmpty())
        }
    }

    /**
     * The windows of the texts encoded before their windows were kept, made now, a batch at a time: of the texts as
     * they are now — a text changed since is encoded again by [TextVectors.refresh], its windows with it. Answers how
     * many texts were gone through.
     */
    fun fillWindows(store: SourceStore, cancelled: () -> Boolean = { false }): Int =
        workload.run(Work("textWindows", source = store.id), mapOf("start" to 1)) {
            windowsFilled(store, cancelled)
        }

    private fun windowsFilled(store: SourceStore, cancelled: () -> Boolean): Int {
        val encoder = plugins.textEncoder() ?: return 0
        val keys = store.schema.texts.filter { it.block != null || it.searchByMeaning }.map { it.key }
        var done = 0
        val started = System.currentTimeMillis()
        while (!cancelled()) {
            val todo = store.textVectors.windows.withoutWindows(encoder.id, keys + TITLE, BATCH)
            if (todo.isEmpty()) break
            val ids = todo.map { it.first }.distinct()
            val fields = store.corrections.fieldsOf(ids)
            val site = store.items.textsOf(ids, keys)
            val titles = store.items.heads(ids).mapValues { it.value.title }
            val texts = todo.map { (id, key) ->
                val own = fields[id].orEmpty()
                val all =
                    Corrected.texts(site[id].orEmpty(), own) +
                        listOfNotNull(titles[id]?.let { TITLE to Corrected.title(it, own) })
                // A text gone since: no windows, and the next refresh drops its vector
                Triple(id, key, all[key].orEmpty())
            }
            keepWindows(store, texts)
            // The works searched by the meaning of the windows just kept: indexed again with them
            events.publishEvent(TextVectorsChanged(store.id, ids))
            done += texts.size
        }
        if (done > 0)
            log.info("{}: windows of {} texts counted in {} ms", store.id, done, System.currentTimeMillis() - started)
        return done
    }

    private companion object {
        /** Texts per encoder call; their windows share batches of the graph. */
        const val BATCH = 32
    }
}
