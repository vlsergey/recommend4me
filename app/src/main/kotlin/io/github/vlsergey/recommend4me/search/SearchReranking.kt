package io.github.vlsergey.recommend4me.search

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
 * the line, every one is put in the order of what it says.
 */
@Component
class SearchReranking(
    private val stores: Stores,
    private val plugins: Plugins,
    private val documents: SearchDocuments,
) {

    /**
     * Every work found, read against the line by the reranker, in the order of its score; as found without a reranker.
     * A work read for the line before, its text the same, is not read again ([SearchScoreRepository]): the next page of
     * a search, the line searched again, read nothing.
     */
    fun reranked(type: TypeStore, found: SearchResult): SearchResult {
        val reranker = plugins.reranker()
        if (reranker == null || found.keys.size < 2) return found
        val started = System.currentTimeMillis()
        val read =
            found.keys
                .groupBy { it.source }
                .flatMap { (source, list) ->
                    stores
                        .source(source)
                        ?.let { documents.documents(it, list.map { k -> k.id }, passages = false).entries }
                        .orEmpty()
                }
                .associate { it.key to it.value }
        val texts = found.keys.associateWith { key -> read[key]?.let { readable(it, found.nearest[key]) }.orEmpty() }
        val hashes = texts.mapValues { Vectors.keyOf(it.value) }
        val stored = type.searchScores.of(reranker.id, found.line).filter { (key, s) -> hashes[key] == s.textsHash }
        val todo = found.keys.filter { it !in stored }
        val scored =
            if (todo.isEmpty()) emptyMap()
            else {
                val scores = reranker.rank(found.line, todo.map { texts.getValue(it) })
                todo.indices
                    .associate { todo[it] to StoredScore(hashes.getValue(todo[it]), scores[it]) }
                    .also { type.searchScores.save(reranker.id, found.line, it) }
            }
        val scores = stored + scored
        val order = found.keys.sortedByDescending { scores.getValue(it).score }
        log.info(
            "'{}' in {}: {} works, {} read by {} in {} ms",
            found.line,
            type.id,
            found.keys.size,
            todo.size,
            reranker.id,
            System.currentTimeMillis() - started,
        )
        return SearchResult(found.line, order, found.state, found.nearest)
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
}
