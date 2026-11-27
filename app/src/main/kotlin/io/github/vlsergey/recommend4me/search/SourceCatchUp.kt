package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.SourceStore
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(SourceCatchUp::class.java)

/**
 * THE WORKS OF A SOURCE WRITTEN SINCE THEY WERE INDEXED, BROUGHT INTO THE INDEX: a chunk at a time — the chapters and
 * the vectors of thousands of works, a new encoder's, are not held at once —, of each work its fingerprint first, the
 * document made of those whose fingerprint changed alone. Of the site's and the user's values alone: no refit of the
 * models touches the index of the search.
 */
internal class SourceCatchUp(private val documents: SearchDocuments) {
    /** The works [keys] of the source [s] brought into the index of the type [typeId]; how many were written. */
    fun written(provider: SearchProvider, typeId: String, s: SourceStore, keys: List<ItemKey>): Int {
        val started = System.currentTimeMillis()
        val hierarchies = s.hierarchies()
        // Where the time goes, told in the log: the fingerprints, the documents made, the rest the index's
        var printing = 0L
        var making = 0L
        val written =
            keys.chunked(CHUNK).sumOf { chunk ->
                val began = System.currentTimeMillis()
                val prints = documents.printsOf(s, chunk.map { it.id }, hierarchies)
                printing += System.currentTimeMillis() - began
                provider.update(
                    typeId,
                    prints,
                    { asked ->
                        val made = System.currentTimeMillis()
                        val ids = asked.map { it.id }
                        documents.documents(s, ids, models = false, hierarchies = hierarchies).values.toList().also {
                            making += System.currentTimeMillis() - made
                        }
                    },
                    chunk.filter { it !in prints },
                )
            }
        log.info(
            "{}: {} works brought into the index of the search in {} ms: their fingerprints {}, the documents made {}",
            s.id,
            keys.size,
            System.currentTimeMillis() - started,
            printing,
            making,
        )
        return written
    }

    private companion object {
        /**
         * The works indexed again at a time, a commit of the index of the search each — with its sync to the disk —: a
         * turn's ([WorkTexts.PORTION]) of a source at once, one commit a turn, not one for every half of it (of a
         * thousand works of ficbook, the commits took a fifteenth of the turn, 2026-11-27). Memory too: the chapters of
         * so many works held at once.
         */
        const val CHUNK = WorkTexts.PORTION
    }
}
