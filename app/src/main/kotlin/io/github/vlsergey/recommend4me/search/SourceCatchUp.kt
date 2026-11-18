package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.SourceStore

/**
 * THE WORKS OF A SOURCE WRITTEN SINCE THEY WERE INDEXED, BROUGHT INTO THE INDEX: a chunk at a time — the chapters and
 * the vectors of thousands of works, a new encoder's, are not held at once —, of each work its fingerprint first, the
 * document made of those whose fingerprint changed alone. Of the site's and the user's values alone: no refit of the
 * models touches the index of the search.
 */
internal class SourceCatchUp(private val documents: SearchDocuments) {
    /** The works [keys] of the source [s] brought into the index of the type [typeId]; how many were written. */
    fun written(provider: SearchProvider, typeId: String, s: SourceStore, keys: List<ItemKey>): Int {
        val hierarchies = s.hierarchies()
        return keys.chunked(CHUNK).sumOf { chunk ->
            val prints = documents.printsOf(s, chunk.map { it.id }, hierarchies)
            provider.update(
                typeId,
                prints,
                { asked ->
                    val ids = asked.map { it.id }
                    documents.documents(s, ids, models = false, hierarchies = hierarchies).values.toList()
                },
                chunk.filter { it !in prints },
            )
        }
    }

    private companion object {
        /** The works indexed again at a time: memory, not meaning. */
        const val CHUNK = 500
    }
}
