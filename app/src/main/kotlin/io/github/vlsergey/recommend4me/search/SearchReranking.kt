package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.encoder.TextReranker
import io.github.vlsergey.recommend4me.fulltext.NearWindow
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
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
 */
@Component
class SearchReranking(
    private val stores: Stores,
    private val plugins: Plugins,
    private val documents: SearchDocuments,
    private val windowTexts: WindowTexts,
) {

    /**
     * Every work found, and every work of the first [WINDOWS] of the [windows] nearest to the line, read against the
     * line by the reranker, in the order of its score; as found without a reranker. A work read for the line before of
     * the same texts — the same windows, or the same document and passage nearest to the line — is not read again
     * ([SearchScoreRepository]), nor its document made: the next page of a search, the line searched again, make and
     * read nothing but the windows' texts. A document is known the same by its fingerprint the index keeps
     * ([SearchResult.prints]) and its passage; of a work the index has none of, by the text itself.
     */
    fun reranked(type: TypeStore, found: SearchResult, windows: List<NearWindow>): SearchResult {
        val reranker = plugins.reranker()
        val first = windows.take(WINDOWS)
        if (reranker == null || found.keys.size + first.size < 2) return found
        val started = System.currentTimeMillis()
        val texts = windowTexts.of(first)
        val ofWindows = first.filter { it in texts }.groupBy({ it.key }, { texts.getValue(it) })
        val keys = (found.keys + ofWindows.keys).distinct()
        val printed =
            keys
                .mapNotNull { key ->
                    (ofWindows[key]?.let { Vectors.keyOf(it.joinToString(WINDOW_SEPARATOR)) } ?: printOf(found, key))
                        ?.let { key to it }
                }
                .toMap()
        val kept = type.searchScores.of(reranker.id, found.line).filter { (key, s) -> printed[key] == s.textsHash }
        val todo = keys.filter { it !in kept }
        val readings = readingsOf(todo, ofWindows, found)
        val scored =
            scoresOf(reranker, found.line, readings, printed).also {
                type.searchScores.save(reranker.id, found.line, it)
            }
        val scores = kept + scored
        // A work found whose document is gone since is the last
        val order = keys.sortedByDescending { scores[it]?.score ?: Float.NEGATIVE_INFINITY }
        val (byWindows, byDocuments) = readings.filterValues { it.isNotEmpty() }.keys.partition { it in ofWindows }
        log.info(
            "'{}' in {}: {} works, {} windows of {} and {} documents read by {} in {} ms",
            found.line,
            type.id,
            keys.size,
            byWindows.sumOf { readings.getValue(it).size },
            byWindows.size,
            byDocuments.size,
            reranker.id,
            System.currentTimeMillis() - started,
        )
        // What the reranker said of a work is a feature of it the order is learnt of ([SearchWeights])
        val features = keys.associateWith { key ->
            found.features[key].orEmpty() + scores[key]?.let { mapOf(RERANKER to it.score.toDouble()) }.orEmpty()
        }
        return SearchResult(found.line, order, found.state, found.nearest, found.prints, features, found.places)
    }

    /**
     * Every text each work of [todo] is read by: its windows of [ofWindows], else its document as the reranker reads it
     * ([readable]); none of a work whose document is gone.
     */
    private fun readingsOf(
        todo: List<ItemKey>,
        ofWindows: Map<ItemKey, List<String>>,
        found: SearchResult,
    ): Map<ItemKey, List<String>> {
        val read =
            todo
                .filter { it !in ofWindows }
                .groupBy { it.source }
                .flatMap { (source, list) ->
                    stores.source(source)?.let { documents.documents(it, list.map { k -> k.id }).entries }.orEmpty()
                }
                .associate { it.key to it.value }
        return todo.associateWith { key ->
            ofWindows[key] ?: read[key]?.let { listOf(readable(it, found.nearest[key])) }.orEmpty()
        }
    }

    /**
     * The score of every work of [readings] read against the [line] by the [reranker]: the best of its texts, kept with
     * the hash of what it was read by ([printed], else its one text's).
     */
    private fun scoresOf(
        reranker: TextReranker,
        line: String,
        readings: Map<ItemKey, List<String>>,
        printed: Map<ItemKey, Long>,
    ): Map<ItemKey, StoredScore> {
        val all = readings.values.flatten()
        if (all.isEmpty()) return emptyMap()
        val scores = reranker.rank(line, all)
        var at = 0
        return readings
            .mapNotNull { (key, list) ->
                val best = list.indices.maxOfOrNull { scores[at + it] }
                at += list.size
                best?.let { key to StoredScore(printed[key] ?: Vectors.keyOf(list.single()), it) }
            }
            .toMap()
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

        /** What the texts of a work's windows are joined by as their hash is made. */
        private const val WINDOW_SEPARATOR = "\u0000"
    }
}
