package io.github.vlsergey.recommend4me.tag

import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(HierarchyOffersWorkedOut::class.java)

/**
 * THE TOP OFFERS OF THE TAGS' GRAPH by the names and the works of the tags [n], counted in the same work, the nearest
 * in the catalogue first, of those alike near the ones that change most of the graded works — each only offered, the
 * user says:
 * - a tag under another by what their entries are a kind of ([CatalogueGraph.links]): the graded works of the child
 *   without the parent get it, and those the user said have not the parent have not the child either;
 * - two tags bound to one entry merged: the graded works of the smaller of the two;
 * - a new tag above two tags or more ([OffersAbove]).
 *
 * Of one reading, kept nowhere: [TagRenaming] keeps what it gives.
 */
class HierarchyOffersWorkedOut(private val scope: TagScope, private val n: Naming, languages: List<String>) {
    private val graph = CatalogueGraph(scope, n, languages)

    /** Every offer, the nearest first. */
    fun all(): List<HierarchyOffer> {
        val started = System.currentTimeMillis()
        val tooGeneral = scope.tags.entries.tooGeneral()
        val out = underAnother() + overTheHead() + oneEntry() + OffersAbove(scope, n, graph, tooGeneral).offers()
        // NO TAG ABOVE MORE GENERAL THAN ONE THE USER SAID IS TOO GENERAL: an entry above it in the
        // catalogue is more general still — never offered (user, 2026-10-16). The one said stays,
        // shown when asked, for its word to be taken back
        val aboveTooGeneral = graph.up(tooGeneral.toList()).keys - tooGeneral
        val offered = out.filterNot { o -> isAbove(o) && (o.entry in aboveTooGeneral || repeats(o)) }
        log.info(
            "{}: {} offers of the tags' graph worked out in {} ms",
            scope.id,
            offered.size,
            System.currentTimeMillis() - started,
        )
        return nearestFirst(offered)
    }

    /** A tag under another by their entries. */
    private fun underAnother(): List<HierarchyOffer> =
        graph.links().map { l ->
            val gets = n.graded(l.child) - n.graded(l.parent) - n.rejected(l.parent)
            val loses = n.rejected(l.parent) - n.rejected(l.child)
            HierarchyOffer(
                HierarchyKind.LINK,
                l.way.first().catalogue,
                listOf(l.child, l.parent),
                null,
                l.way,
                (gets + loses).size,
                l.way.size - 1,
            )
        }

    /**
     * A link over the head: a tag right under another it is under through a third already — the link changes no work.
     */
    private fun overTheHead(): List<HierarchyOffer> =
        n.stats.keys.flatMap { child ->
            val parents = n.hierarchy.parentsOf(child)
            parents.mapNotNull { parent ->
                parents
                    .firstOrNull { it != parent && parent in n.hierarchy.ancestors(it) }
                    ?.let { via ->
                        HierarchyOffer(HierarchyKind.UNLINK, null, listOf(child, parent, via), null, emptyList(), 0, 0)
                    }
            }
        }

    /** One entry, two tags: merged, unless the user told them apart. */
    private fun oneEntry(): List<HierarchyOffer> {
        val distinct = scope.tags.relations.distinct()
        return graph.tagsOf.flatMap { (ref, keys) ->
            val sorted = keys.sorted()
            sorted
                .flatMapIndexed { i, a -> sorted.drop(i + 1).map { b -> a to b } }
                .filter { (a, b) -> TagRepository.pair(TagRepository.idOf(a)!!, TagRepository.idOf(b)!!) !in distinct }
                .map { (a, b) ->
                    HierarchyOffer(
                        HierarchyKind.MERGE,
                        ref.catalogue,
                        // The tag of more works first: the one the other is merged into
                        listOf(a, b).sortedByDescending { n.stats[it]?.counts?.works ?: 0 },
                        ref,
                        listOf(ref),
                        minOf(n.graded(a).size, n.graded(b).size),
                        0,
                    )
                }
        }
    }

    /** Whether [o] is of a tag above others, a new one or one bound to the entry. */
    private fun isAbove(o: HierarchyOffer) = o.kind == HierarchyKind.NEW_PARENT || o.kind == HierarchyKind.BIND_PARENT

    /**
     * NOR AN ENTRY UNDER ONE A TAG IS BOUND TO, ABOVE THE VERY TAGS THAT TAG IS ABOVE ALREADY: it says nothing the tag
     * does not (user, 2026-10-16) — one above some of them alone is offered.
     */
    private fun repeats(o: HierarchyOffer): Boolean {
        val entry = o.entry ?: return false
        val tags = o.tags.toSet()
        return (graph.up(listOf(entry)).keys - entry).any { above ->
            graph.tagsOf[above].orEmpty().any { t -> n.hierarchy.childrenOf(t).let { it.isNotEmpty() && it == tags } }
        }
    }

    /**
     * The nearest first — what the entries are right above, not "process" all of them are far up — then the ones
     * changing most. A tag in one new tag alone, the most precise: one sharing a tag with a nearer is left till that is
     * said. One the user said is too general holds no tag: shown when asked, it leaves its tags to the others.
     */
    private fun nearestFirst(offers: List<HierarchyOffer>): List<HierarchyOffer> {
        val sorted =
            offers.sortedWith(
                compareBy<HierarchyOffer> { it.levels }
                    .thenByDescending { it.graded }
                    .thenBy { it.kind }
                    .thenBy { it.tags.joinToString() }
            )
        val placed = HashSet<String>()
        return sorted.filter { o ->
            !isAbove(o) || o.rejected || o.tags.none { it in placed }.also { if (it) placed += o.tags }
        }
    }
}
