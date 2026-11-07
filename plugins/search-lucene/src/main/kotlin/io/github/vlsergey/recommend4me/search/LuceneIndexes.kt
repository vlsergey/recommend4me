package io.github.vlsergey.recommend4me.search

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** The Lucene index of every content type, in `<data-dir>/types/<type>/search-index`, opened when first asked for. */
internal class LuceneIndexes(private val dataDir: Path) : AutoCloseable {

    /** The open indexes — files, not data: a writer is opened once and held. */
    private val open = ConcurrentHashMap<String, LuceneIndex>()

    /** The index of [contentType]. */
    fun of(contentType: String): LuceneIndex =
        open.computeIfAbsent(contentType) { LuceneIndex(dataDir.resolve("types").resolve(it).resolve("search-index")) }

    override fun close() {
        open.values.forEach { it.close() }
    }
}
