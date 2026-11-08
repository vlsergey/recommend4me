package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.FacetCorrection
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
     * values go by ([hintNames]): an alias added to the dictionary is a new text to learn from. [cast]: how the site's
     * line of characters and pairings was read
     * ([TaskCast.signature][io.github.vlsergey.recommend4me.cast.TaskCast.signature]), null of a facet that reads none.
     * What every facet's basis reads alike is made once for the pass ([WorksRead.bases]).
     */
    fun of(
        facet: String,
        read: WorksRead,
        values: List<String>,
        grouped: List<ValueGrouping>,
        hintNames: Map<String, List<String>>,
        cast: String?,
    ): Long {
        val ids = read.ids
        val bases = read.bases
        val fingerprints = bases.fingerprints
        return Vectors.keyOf(
            bases.order.joinToString(",") { "${ids[it]}:${fingerprints[it]}" } +
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
                (read.hierarchies[facet]?.signature() ?: "") +
                "\u0002" +
                otherFacets(facet, read) +
                // Only of a facet that reads the line: the bases of the others stay as they were
                (cast?.let { "\u0002cast=$it" } ?: "")
        )
    }

    /**
     * The fingerprint of the other facets' values as the site and the user give them: one of them changed is learnt
     * from again. NOT WHAT THE MODEL GAVE of the other facets: each refit of one changes what the next reads, the
     * circle never settles, and every start would fit every facet again for nothing new. The model's values are read as
     * they are when the chances are made. The values the facet learns from besides the site's (the examples, the
     * drafts) are of the facet itself, left out here: the other facets are the site's and the user's alone.
     */
    private fun otherFacets(facet: String, read: WorksRead): Long {
        val ids = read.ids
        val bases = read.bases
        val facets = bases.facets
        return Vectors.keyOf(
            bases.order.joinToString(",") { i ->
                ids[i] + ":" + facets[i].filter { it.first != facet }.joinToString(";") { it.second }
            }
        )
    }
}
