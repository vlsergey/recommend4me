package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.correction.FacetCorrection
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
     * works' texts: the models of the characters and the pairings fitted again without them; 8 — the chances calibrated
     * by the user's answers, of what the folds said of them ([Calibration]): every facet fitted again to learn it; 9 —
     * of a work all named, the values rejected to the model not taken for the user's answers; 10 — the calibration of
     * the user's answers as said alone, not spread over the values' hierarchy; 11 — the noes of a work all named among
     * them, the calibration on again; 12 — the works read a portion at a time, their fingerprints streamed in the order
     * of their ids ([WorksPrint]), the values of the facets before kept of the same pass.
     */
    private const val KEPT = 12

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
     * The fingerprint of the whole facet of every work, of what its [works] printed of them. The values chosen from
     * ([values]) and their groups ([grouped]) are part of what is learnt: a universe added or removed, a sex learnt of
     * a character, fits again. What is kept of the chances is part of it too: a new rule of what to keep makes them
     * again. And the names the values go by ([hintNames]): an alias added to the dictionary is a new text to learn
     * from. [hierarchy]: the tags under others of the facet — a tag put under another is learnt again. [cast]: how the
     * site's line of characters and pairings was read
     * ([TaskCast.signature][io.github.vlsergey.recommend4me.cast.TaskCast.signature]), null of a facet that reads none.
     */
    fun of(
        works: WorksPrint,
        values: List<String>,
        grouped: List<ValueGrouping>,
        hintNames: Map<String, List<String>>,
        hierarchy: TagHierarchy?,
        cast: String?,
    ): Long =
        Vectors.keyOf(
            works.works().toString() +
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
                "\u0002" +
                (hierarchy?.signature() ?: "") +
                "\u0002" +
                works.others() +
                // Only of a facet that reads the line: the bases of the others stay as they were
                (cast?.let { "\u0002cast=$it" } ?: "")
        )
}

/**
 * THE FINGERPRINTS OF EVERY WORK OF A PASS OVER A FACET ([FacetBasis.of]), given a portion after another in the order
 * of the works' ids ([add]) and never held: every work's own ([FacetBasis.fingerprint]) and its other facets' values as
 * the site and the user give them — one of them changed is learnt from again. NOT WHAT THE MODEL GAVE of the other
 * facets: each refit of one changes what the next reads, the circle never settles, and every start would fit every
 * facet again for nothing new. The values the facet learns from besides the site's (the examples, the drafts) are of
 * the facet itself, left out of the others: they are the site's and the user's alone. The same whatever the portions:
 * the works one after another as if read at once.
 */
internal class WorksPrint(private val facet: String) {
    private val works = Vectors.keyHash()
    private val others = Vectors.keyHash()
    private var first = true

    /** The works of a portion [read], in its order. */
    fun add(read: WorksRead) {
        val ids = read.ids
        val bases = read.bases
        val fingerprints = bases.fingerprints
        val facets = bases.facets
        ids.indices.forEach { i ->
            if (!first) {
                works.update(SEPARATOR)
                others.update(SEPARATOR)
            }
            first = false
            works.update("${ids[i]}:${fingerprints[i]}".toByteArray())
            others.update(
                (ids[i].toString() + ":" + facets[i].filter { it.first != facet }.joinToString(";") { it.second })
                    .toByteArray()
            )
        }
    }

    /** The print of every work's own fingerprint: asked once, at the end of the walk. */
    fun works(): Long = Vectors.keyOf(works)

    /** The print of every work's other facets: asked once, at the end of the walk. */
    fun others(): Long = Vectors.keyOf(others)

    private companion object {
        /** Between two works. */
        val SEPARATOR = ",".toByteArray()
    }
}
