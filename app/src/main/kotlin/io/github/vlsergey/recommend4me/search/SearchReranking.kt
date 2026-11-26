package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.encoder.TextReranker
import io.github.vlsergey.recommend4me.fulltext.NearWindow
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SearchReranking::class.java)

/**
 * THE WORKS FOUND READ AGAINST THE LINE by the reranker: a vector of a text is the text squeezed before it meets the
 * line, and "a detective in Victorian London" found the works whose texts named a Victor and London; read together with
 * the line, every one is put in the order of what it says. A work with windows among the first [WINDOWS] nearest by
 * meaning is read by those windows, each apart, and has the best of what is said of them: the windows read so put the
 * work a retelling of a scene of it was in among the first 10 works 38 times of 85 among 48 966 works, against 14 of
 * their order by meaning (2026-11-25, the windows' vectors themselves). Every other work is read by its document.
 *
 * The windows are read a portion at a time ([PORTIONS]), the nearest first, and every portion but the last gives the
 * order so far to the one who waits ([reranked]): a work's chapters are read of the disk of the data, a seek each, and
 * of a thousand windows the first hundred are a tenth of the wait.
 */
@Component
class SearchReranking(
    private val plugins: Plugins,
    private val documents: SearchDocuments,
    private val windowTexts: WindowTexts,
) {

    /**
     * Every work found, and every work of the first [WINDOWS] of the [windows] nearest to the line, read against the
     * line by the reranker, in the order of its score; as found without a reranker. The windows read a portion at a
     * time ([PORTIONS]), the documents of the works found without windows with the first; after every portion but the
     * last, the order so far given to [shown] — the works read in the order of their scores, then those not read yet in
     * the order they were found.
     *
     * A work read for the line before of the same texts is not read again ([SearchScoreRepository]): of the same
     * windows of the same document, or of the same document and passage nearest to the line. A document is known the
     * same by its fingerprint the index keeps ([SearchResult.prints]), which holds the hashes of its chapters' texts;
     * of a work the index keeps none of, read by its document, by the text itself, and read by its windows, not at all.
     */
    fun reranked(
        type: TypeStore,
        found: SearchResult,
        windows: List<NearWindow>,
        shown: (SearchResult) -> Unit = {},
    ): SearchResult {
        val reranker = plugins.reranker()
        val first = windows.take(WINDOWS)
        if (reranker == null || found.keys.size + first.size < 2) return found
        val reading = Reading(type, reranker, found, first)
        var from = 0
        for (end in PORTIONS) {
            val last = end >= first.size || end == PORTIONS.last()
            reading.read(first.subList(from, minOf(end, first.size)), from, withDocuments = from == 0)
            from = end
            if (last) break
            shown(reading.result())
        }
        reading.logged()
        return reading.result()
    }

    /** One reading of the works [found] and of the [windows] nearest to the line, a portion at a time. */
    private inner class Reading(
        private val type: TypeStore,
        private val reranker: TextReranker,
        private val found: SearchResult,
        windows: List<NearWindow>,
    ) {
        private val started = System.currentTimeMillis()
        private val ofWindows = windows.groupBy { it.key }
        private val keys = (found.keys + ofWindows.keys).distinct()

        /** The position of the last window of every work among those read: once it is read, the work is read whole. */
        private val lastWindow = windows.withIndex().associate { (i, w) -> w.key to i }

        /** What every work is read by: its windows and its document's fingerprint, else its document and passage. */
        private val printed =
            keys
                .mapNotNull { key ->
                    (if (key in ofWindows) printOf(found, key, ofWindows.getValue(key)) else printOf(found, key))?.let {
                        key to it
                    }
                }
                .toMap()
        private val kept =
            type.searchScores.of(reranker.id, found.line).filter { (key, s) -> printed[key] == s.textsHash }

        /** The best score of every work read so far, of those kept too. */
        private val best = HashMap<ItemKey, Float>(kept.mapValues { it.value.score })
        private var windowsRead = 0
        private var documentsRead = 0
        private var readMs = 0L
        private var rankMs = 0L

        /**
         * The [portion] of the windows read — from the window [from] on —, of the works not kept; with the documents of
         * the works found without windows when [withDocuments]. The scores of the works read whole by now kept.
         */
        fun read(portion: List<NearWindow>, from: Int, withDocuments: Boolean) {
            val readFrom = System.currentTimeMillis()
            val todo = portion.filter { it.key !in kept }
            val texts = windowTexts.of(todo)
            val docs = if (withDocuments) documentsOf(keys.filter { it !in ofWindows && it !in kept }) else emptyMap()
            val readings = todo.filter { it in texts }.map { it.key to texts.getValue(it) } + docs.toList()
            val rankFrom = System.currentTimeMillis()
            readMs += rankFrom - readFrom
            val scores =
                if (readings.isEmpty()) FloatArray(0) else reranker.rank(found.line, readings.map { it.second })
            rankMs += System.currentTimeMillis() - rankFrom
            readings.forEachIndexed { i, (key, _) -> best.merge(key, scores[i], ::maxOf) }
            windowsRead += todo.size
            documentsRead += docs.size
            // The works whose last window is of this portion, and the documents: read whole, their scores kept
            val upTo = from + portion.size
            val whole = readings.map { it.first }.distinct().filter { key -> (lastWindow[key] ?: -1) < upTo }
            type.searchScores.save(
                reranker.id,
                found.line,
                whole
                    .mapNotNull { key ->
                        val score = best[key] ?: return@mapNotNull null
                        val hash = printed[key] ?: docs[key]?.let { Vectors.keyOf(it) } ?: return@mapNotNull null
                        key to StoredScore(hash, score)
                    }
                    .toMap(),
            )
        }

        /**
         * The text every work of [unread] is read by: its document as the reranker reads it ([readable]) — the values
         * the list keeps the model gave it, of its chapters that of its passage nearest alone
         * ([SearchDocuments.shown]); none of a work whose document is gone.
         */
        private fun documentsOf(unread: List<ItemKey>): Map<ItemKey, String> {
            val chapters = unread.associateWith { key ->
                listOfNotNull(found.nearest[key]?.field)
                    .filter { it.startsWith(SearchDocuments.PART) }
                    .mapTo(HashSet()) { it.removePrefix(SearchDocuments.PART) }
            }
            return documents.shown(type, unread, chapters).mapValues { (key, doc) -> readable(doc, found.nearest[key]) }
        }

        /**
         * The works read so far in the order of their scores, then the rest in the order they were found — once every
         * portion is read, a work whose document is gone. What the reranker said of a work is a feature of it the order
         * is learnt of ([SearchWeights]).
         */
        fun result(): SearchResult {
            val byScore = keys.filter { it in best }.sortedByDescending { best.getValue(it) }
            val order = byScore + keys.filter { it !in best }
            val features = keys.associateWith { key ->
                found.features[key].orEmpty() + best[key]?.let { mapOf(RERANKER to it.toDouble()) }.orEmpty()
            }
            return SearchResult(found.line, order, found.state, found.nearest, found.prints, features, found.places)
        }

        fun logged() =
            log.info(
                "'{}' in {}: {} works, {} windows and {} documents read by {} in {} ms: {} ms the texts read, {} ms " +
                    "the reranker",
                found.line,
                type.id,
                keys.size,
                windowsRead,
                documentsRead,
                reranker.id,
                System.currentTimeMillis() - started,
                readMs,
                rankMs,
            )
    }

    /**
     * What the work [key] is read by of its [windows]: the fingerprint of its document the index keeps
     * ([SearchResult.prints]: the hashes of its chapters' texts with it), and where every window is; null of a work the
     * index named no fingerprint of — read again at every search, its windows' texts read a portion at a time.
     */
    private fun printOf(found: SearchResult, key: ItemKey, windows: List<NearWindow>): Long? =
        found.prints[key]?.let { print ->
            Vectors.keyOf(
                (listOf(WINDOWS_READ, "$print") + windows.map { "${it.text} ${it.from} ${it.to}" }).joinToString(
                    WINDOW_SEPARATOR
                )
            )
        }

    /**
     * What the reranker reads of the work [key] known by what it is made of: the fingerprint of its document the index
     * keeps and the passage of it nearest to the line; null of a work the index keeps none of.
     */
    private fun printOf(found: SearchResult, key: ItemKey): Long? =
        found.prints[key]?.let { print ->
            val p = found.nearest[key]
            Vectors.keyOf("$print ${p?.field} ${p?.from} ${p?.to}")
        }

    /**
     * A work as the reranker reads it, as far as it reads: its title; the passage of it nearest to the line — a window
     * of a description, a scene of a chapter — when it was found by meaning; then its fields by their names, the
     * weightiest first. A chapter is read by its passage alone: whole, the chapters are megabytes cut on every search
     * to the few hundred pieces the reranker reaches.
     */
    private fun readable(doc: SearchDocument, nearest: SearchPassage?): String {
        val title = doc.fields.firstOrNull { it.key == SearchDocuments.TITLE }
        val piece = nearest?.let { p ->
            p.text(doc)?.let { text -> doc.fields.first { it.key == p.field }.name + ": " + text }
        }
        val rest =
            doc.fields
                .filter { it !== title && it.key != nearest?.field && !it.key.startsWith(SearchDocuments.PART) }
                .sortedByDescending { it.weight }
        return (listOfNotNull(title?.let { it.name + ": " + it.text }, piece) + rest.map { it.name + ": " + it.text })
            .joinToString("\n")
    }

    companion object {
        /** The feature of a work found: what the reranker said of it against the line. */
        const val RERANKER = "reranker"

        /**
         * The windows nearest by meaning the reranker reads: about 2.7 ms each on the card. Of 85 retellings among 48
         * 966 works the work of the scene was among the first 10 by the windows read 33 times of the first 300, 38 of 1
         * 000, 40 of 2 000 and 43 of 5 000 (2026-11-25).
         */
        const val WINDOWS = 1_000

        /**
         * Where the portions of the windows read end, the nearest first: every one but the last a version of the order
         * shown to the user as the rest is read.
         */
        val PORTIONS = listOf(100, 300, WINDOWS)

        /** What the texts of a work's windows are joined by as their hash is made. */
        private const val WINDOW_SEPARATOR = "\u0000"

        /** The version of what a work read by its windows is known the same by, in its hash. */
        private const val WINDOWS_READ = "windows 2"
    }
}
