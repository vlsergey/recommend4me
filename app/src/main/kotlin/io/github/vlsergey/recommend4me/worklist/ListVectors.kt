package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component

/**
 * THE VECTORS THE DOCUMENTS OF THE LIST HOLD BROUGHT IN STEP WITH THOSE THE SOURCES KEEP ([ListTexts]), as the stage of
 * the vectors goes over its works ([io.github.vlsergey.recommend4me.textvector.TextVectors]): of every work of its turn
 * whose document holds other vectors of its texts than its source keeps — made by the turn, or as the user opened the
 * work — the document written anew with them, everything else of it as written: its stages and the dates of what they
 * made. A document of a change the index has not written yet — of its item, of its work, or of every item of its source
 * — is left to that writing, which reads the vectors with the rest: written here, the change would come into the index
 * past the stages that take the works by it ([StageSweeps.written]). Nothing planned, nothing told: the stage dates the
 * vectors it made as the other stages date what they make ([StageSweeps.made]).
 */
@Component
class ListVectors(private val works: Works, private val indexes: ListIndexes, private val plugins: Plugins) {

    /** The documents of the items [keys] of the [type] holding other vectors than kept written anew: how many. */
    fun bring(type: TypeStore, keys: Collection<ItemKey>): Int {
        val encoder = plugins.textEncoder()?.id ?: return 0
        val index = indexes.of(type.id)
        val since = ListIndexing.writtenOf(index)
        val clusters = lazy { works.of(type) }
        return keys.groupBy({ it.source }, { it.id }).entries.sumOf { (sourceId, ids) ->
            val s = type.source(sourceId)
            if (s == null || s.changes.everySince(since)) 0 else bring(s, ids, encoder, index, clusters)
        }
    }

    private fun bring(
        s: SourceStore,
        ids: List<String>,
        encoder: String,
        index: ListIndex,
        clusters: Lazy<WorkClusters>,
    ): Int {
        val vectorKeys = ListTexts.vectorKeys(s.schema)
        val fields = vectorKeys.associateWith { ListDocuments.vectorField(encoder, it) }
        val kept =
            s.textVectors.halvesOf(ids, encoder, vectorKeys).mapValues { (_, own) ->
                own.entries.associate { (key, halves) -> fields.getValue(key) to ListTexts.packedHalves(halves) }
            }
        val held = index.written { ListTexts.of(it, ids.map { id -> ItemKey(s.id, id) }, fields.values.toList()) }
        val other = ids.filter { id -> !alike(kept[id].orEmpty(), held[ItemKey(s.id, id)].orEmpty()) }
        if (other.isEmpty()) return 0
        val (predicted, written) =
            index.written { ListPredictions.of(it, s.id, other) to ListKeptRows.of(it, s.id, other) }
        val rows =
            ListRows.read(s, other, clusters.value, ListModelValues.of(index, s.id, other), predicted, encoder)
                .mapNotNull { r -> written[r.key.id]?.takeIf { !r.datedOtherThan(it) }?.let { r.over(it) } }
        index.replace(rows)
        return rows.size
    }

    /** Whether two sets of vectors, by their fields, are the same bytes. */
    private fun alike(a: Map<String, ByteArray>, b: Map<String, ByteArray>): Boolean =
        a.keys == b.keys && a.all { (field, bytes) -> bytes.contentEquals(b.getValue(field)) }
}
