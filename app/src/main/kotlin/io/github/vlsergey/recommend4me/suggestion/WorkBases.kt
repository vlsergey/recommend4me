package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import java.util.stream.IntStream

/**
 * WHAT THE BASES OF THE FACETS OF A PASS READ ALIKE of its works ([read]): every work's fingerprint, the works in the
 * order of their ids, and every work's facets as the site and the user give them, one part a facet. Made once for every
 * facet of the pass ([WorksRead.bases]), each facet's basis taking what it needs ([FacetBasis.of]): one facet's own
 * values alone tell its basis from another's.
 */
internal class WorkBases(
    private val store: SourceStore,
    private val encoder: TextEncoder,
    private val read: WorksRead,
) {
    /** The places of the works of [read] in the order of their ids. */
    val order: IntArray by lazy { read.ids.indices.sortedBy { read.ids[it] }.toIntArray() }

    /** Every work's fingerprint ([FacetBasis.fingerprint]), in the order of the works of [read]. */
    val fingerprints: LongArray by lazy {
        val ids = read.ids
        val hashes = HashMap<String, MutableList<String>>()
        store.textVectors.hashes(encoder.id).forEach { (k, hash) -> hashes.getOrPut(k.first) { ArrayList() } += hash }
        val windowCounts = store.parts.windows.windowCounts(encoder.windowsId)
        val site = read.site
        val corrections = read.corrections
        val complete = read.complete
        val drafts = read.drafts
        val out = LongArray(ids.size)
        IntStream.range(0, ids.size).parallel().forEach { i ->
            val id = ids[i]
            out[i] =
                FacetBasis.fingerprint(
                    site[id].orEmpty(),
                    corrections[id].orEmpty(),
                    hashes[id].orEmpty(),
                    windowCounts[id] ?: 0,
                    complete[id].orEmpty(),
                    drafts[id].orEmpty(),
                )
        }
        out
    }

    /**
     * Of every work, its facets as the site and the user give them — the tags with those above and under them — in the
     * order of their keys: the facet and its part "facet=key+key", the keys in order. NOT WHAT THE MODEL GAVE (see
     * [FacetBasis.of]).
     */
    val facets: Array<List<Pair<String, String>>> by lazy {
        val ids = read.ids
        val schema = store.schema
        val hierarchies = read.hierarchies
        val keys = schema.facets.map { it.key }.toSet()
        val site = read.site
        val corrections = read.corrections
        val out = arrayOfNulls<List<Pair<String, String>>>(ids.size)
        IntStream.range(0, ids.size).parallel().forEach { i ->
            val id = ids[i]
            out[i] =
                TagHierarchy.corrected(
                        ModelValues.of(schema, site[id].orEmpty(), null),
                        corrections[id].orEmpty(),
                        hierarchies,
                        store.singleFacets,
                    )
                    .filterKeys { it in keys }
                    .entries
                    .sortedBy { it.key }
                    .map { (f, values) -> f to f + "=" + values.sorted().joinToString("+") }
        }
        Array(ids.size) { out[it]!! }
    }
}
