package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.universe.LayerDraft
import io.github.vlsergey.recommend4me.vector.Vectors

/**
 * THE BASIS OF A FACET'S MODEL: the fingerprint of everything it learns from, of every work's ([fingerprint]) and of
 * the facet's whole ([of]). A model fitted on another basis is fitted again.
 */
internal object FacetBasis {
    /**
     * The rule of what is made of an item's chances and read of the others: 2 — the likeliest others offered besides
     * the likely ones; 3 — the rejected ones too; 4 — the site's examples too; 5 — of a facet of limited values, not
     * every value the work may have: the likely and the offered alone; 6 — the chain: a facet reads the values the
     * model gives of the facets before it alone, made in the same pass; 7 — no counts of the values' names in the
     * works' texts: the models of the characters and the pairings fitted again without them.
     */
    private const val KEPT = 7

    /**
     * What an item's chances are made of: every value of it, the user's answers — the facets said to be all named
     * ([complete]) among them — its texts' hashes, its chapters.
     */
    fun fingerprint(
        site: Map<String, List<String>>,
        corrections: List<FacetCorrection>,
        textHashes: Collection<String>,
        windows: Int,
        complete: Collection<String>,
        drafts: Collection<LayerDraft>,
    ): Long =
        Vectors.keyOf(
            site.entries
                .sortedBy { it.key }
                .joinToString("\u0001") { (f, keys) -> "$f=" + keys.sorted().joinToString(",") } +
                "\u0002" +
                corrections.sortedWith(compareBy({ it.facet }, { it.key })).joinToString("\u0001") {
                    "${it.facet}:${it.key}=${it.added}"
                } +
                "\u0002" +
                textHashes.sorted().joinToString("\u0001") +
                "\u0002" +
                windows +
                // Only when said, only when made: the fingerprints of the items without stay as they were
                (if (complete.isEmpty()) "" else "\u0002complete=" + complete.sorted().joinToString(",")) +
                (if (drafts.isEmpty()) ""
                else "\u0002drafts=" + drafts.map { "${it.facet}:${it.key}" }.sorted().joinToString(","))
        )

    /**
     * The fingerprint of the whole [facet] of every work of [read]. The values chosen from ([values]) and their groups
     * ([grouped]) are part of what is learnt: a universe added or removed, a sex learnt of a character, fits again.
     * What is kept of the chances is part of it too: a new rule of what to keep makes them again. And the names the
     * values go by ([hintNames]): an alias added to the dictionary is a new text to learn from. [learnt]: the values
     * each work learns from.
     */
    fun of(
        store: SourceStore,
        facet: String,
        encoder: TextEncoder,
        read: WorksRead,
        values: List<String>,
        grouped: List<ValueGrouping>,
        hintNames: Map<String, List<String>>,
        learnt: Map<String, Map<String, List<String>>>,
    ): Long {
        val ids = read.ids
        val fingerprints = fingerprints(store, encoder, read)
        return Vectors.keyOf(
            ids.indices.sortedBy { ids[it] }.joinToString(",") { "${ids[it]}:${fingerprints[it]}" } +
                "\u0002" +
                values.joinToString("\u0001") +
                "\u0002" +
                grouped.joinToString("\u0001") { g ->
                    g.name + "=" + g.of.joinToString(",") { groups -> groups.joinToString("+") { g.groups[it] } }
                } +
                "\u0002" +
                KEPT +
                "\u0002" +
                Vectors.keyOf(
                    hintNames.entries
                        .sortedBy { it.key }
                        .joinToString("\u0001") { (v, names) -> v + "=" + names.joinToString("|") }
                ) +
                // The tags under others: a tag put under another is learnt again
                "\u0002" +
                (store.hierarchies()[facet]?.signature() ?: "") +
                "\u0002" +
                otherFacets(store, facet, read, learnt)
        )
    }

    /** Every work's [fingerprint], in the order of the works of [read]. */
    private fun fingerprints(store: SourceStore, encoder: TextEncoder, read: WorksRead): LongArray {
        val ids = read.ids
        val hashes = HashMap<String, MutableList<String>>()
        store.textVectors.hashes(encoder.id).forEach { (k, hash) -> hashes.getOrPut(k.first) { ArrayList() } += hash }
        val windowCounts = store.parts.windowCounts(encoder.id)
        return LongArray(ids.size) { i ->
            val id = ids[i]
            fingerprint(
                read.site[id].orEmpty(),
                read.corrections[id].orEmpty(),
                hashes[id].orEmpty(),
                windowCounts[id] ?: 0,
                read.complete[id].orEmpty(),
                read.drafts[id].orEmpty(),
            )
        }
    }

    /**
     * The fingerprint of the other facets' values as the site and the user give them: one of them changed is learnt
     * from again. NOT WHAT THE MODEL GAVE of the other facets: each refit of one changes what the next reads, the
     * circle never settles, and every start would fit every facet again for nothing new. The model's values are read as
     * they are when the chances are made.
     */
    private fun otherFacets(
        store: SourceStore,
        facet: String,
        read: WorksRead,
        learnt: Map<String, Map<String, List<String>>>,
    ): Long {
        val ids = read.ids
        val schema = store.schema
        val hierarchies = store.hierarchies()
        val facets = schema.facets.map { it.key }.toSet()
        return Vectors.keyOf(
            ids.indices
                .sortedBy { ids[it] }
                .joinToString(",") { i ->
                    val id = ids[i]
                    id +
                        ":" +
                        TagHierarchy.corrected(
                                ModelValues.of(schema, learnt[id].orEmpty(), null),
                                read.corrections[id].orEmpty(),
                                hierarchies,
                                store.singleFacets,
                            )
                            .filterKeys { it != facet && it in facets }
                            .entries
                            .sortedBy { it.key }
                            .joinToString(";") { (f, keys) -> f + "=" + keys.sorted().joinToString("+") }
                }
        )
    }
}
