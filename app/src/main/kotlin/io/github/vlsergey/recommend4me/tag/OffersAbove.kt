package io.github.vlsergey.recommend4me.tag

/**
 * THE OFFERS OF A TAG ABOVE TWO TAGS OR MORE, of one reading: a new tag of an entry no tag is bound to where two of
 * them meet first — of the entries both are a kind of, the nearest to the farther of the two: the graded works of any
 * of them get it. Not where a tag is there already: that tag is offered instead. Of several such entries of a pair, the
 * most precise alone: one none of the others is under, of those the nearer by the two ways together. Never of two tags
 * one under the other by the user's word; above the roots of the user's trees alone, a tag under another of them left
 * out. Of two tags under a tag of the user's both, only where they meet under its entry: a new tag between them and it,
 * never one over its head — nor of a tag above both bound to no entry. A tag in one such offer alone, the nearest: one
 * sharing a tag with a nearer one is not offered till the nearer is said — then it is offered above the new tag
 * ([HierarchyOffersWorkedOut]).
 *
 * An entry the user said is too general ([tooGeneral]) is offered marked so.
 */
class OffersAbove(
    private val scope: TagScope,
    private val n: Naming,
    private val graph: CatalogueGraph,
    private val tooGeneral: Set<TagEntryRef>,
) {
    /** Where pairs of tags meet: the entry, the tags meeting there, and the fewest levels up any pair meets. */
    private class Meetings {
        val under = HashMap<TagEntryRef, MutableSet<String>>()
        val levels = HashMap<TagEntryRef, Int>()
    }

    /** Every offer of a tag above: a new one, or one of the entry's name bound to it. */
    fun offers(): List<HierarchyOffer> {
        val meetings = meetings()
        // A tag of the entry's label already — of a site, the user's — bound to it not: that tag offered above, not a
        // new one
        val texts = scope.tags.entries.keptEntries(meetings.under.keys)
        val byName = if (meetings.under.isEmpty()) emptyMap() else tagsByName()
        return meetings.under.mapNotNull { (ref, all) ->
            // Its labels alone: an alias is too loose — "action" is one of "activity", and no "Экшн" stands for it
            val names = texts[ref]?.labels?.values.orEmpty().map { it.trim().lowercase() }
            val named =
                names
                    .flatMap { byName[it].orEmpty() }
                    .distinct()
                    .filter { it !in all }
                    .maxByOrNull { n.stats[it]?.counts?.works ?: 0 }
            val level = meetings.levels.getValue(ref)
            if (named != null) bindParent(ref, all, named, level) else newParent(ref, all, level)
        }
    }

    /**
     * A new tag above: where two tags meet first — the entries both reach the nearest, by the farther of the two ways —
     * when no tag is bound to one of them; every pair meeting there under it.
     */
    private fun meetings(): Meetings {
        val out = Meetings()
        val keys = graph.reached.keys.sorted()
        keys.forEachIndexed { i, a ->
            // One under the other already: no tag above the two, the upper one stands for both
            val relatedA = n.hierarchy.ancestors(a) + n.hierarchy.descendants(a)
            keys
                .drop(i + 1)
                .filter { it !in relatedA }
                .forEach { b ->
                    val (precise, nearest) = meetingOf(a, b) ?: return@forEach
                    out.under.getOrPut(precise) { HashSet() } += listOf(a, b)
                    out.levels.merge(precise, nearest - 1, ::minOf)
                }
        }
        return out
    }

    /**
     * Where the tags [a] and [b] meet first, the most precise of the places, and how many levels up by the farther of
     * the two ways; null where a tag is bound to one of the entries they meet at first, or where it would be over the
     * head of a tag of the user's both are under.
     */
    private fun meetingOf(a: String, b: String): Pair<TagEntryRef, Int>? {
        val upA = graph.reached.getValue(a)
        val upB = graph.reached.getValue(b)
        fun farther(ref: TagEntryRef) = maxOf(upA.getValue(ref).size, upB.getValue(ref).size)
        val common = upA.keys.filter { it in upB }
        val nearest = common.minOfOrNull(::farther) ?: return null
        val meet = common.filter { farther(it) == nearest }
        val precise = if (meet.any { it in graph.tagsOf }) null else mostPrecise(meet, upA, upB)
        return precise?.takeIf { underSharedOnly(a, b, it) }?.let { it to nearest }
    }

    /**
     * One place a pair: the most precise of where it meets [meet] — none of the others under it, the nearer of the two
     * ways together. A catalogue's classes may go round: then all of them are alike precise.
     */
    private fun mostPrecise(
        meet: List<TagEntryRef>,
        upA: Map<TagEntryRef, List<TagEntryRef>>,
        upB: Map<TagEntryRef, List<TagEntryRef>>,
    ): TagEntryRef =
        meet
            .filter { e -> meet.none { other -> other != e && e in graph.up(listOf(other)) } }
            .ifEmpty { meet }
            .minWith(compareBy<TagEntryRef> { upA.getValue(it).size + upB.getValue(it).size }.thenBy { it.entry })

    /**
     * Under a tag of the user's both already: a new one only between — its entry [precise] under every such tag's entry
     * — never over its head.
     */
    private fun underSharedOnly(a: String, b: String, precise: TagEntryRef): Boolean {
        val shared = n.hierarchy.ancestors(a).intersect(n.hierarchy.ancestors(b))
        if (shared.isEmpty()) return true
        val aboveIt = graph.up(listOf(precise)).keys
        return shared.none { c -> graph.refsOf[c].orEmpty().none { it in aboveIt } }
    }

    /** Every tag by every name it goes by, written small: of its sites, the user's, its entries', the one shown. */
    private fun tagsByName(): Map<String, Set<String>> {
        val byName = HashMap<String, MutableSet<String>>()
        n.stats.values.forEach { t ->
            (n.allNames(t) + n.shown(t.key)).forEach { byName.getOrPut(it.trim().lowercase()) { HashSet() } += t.key }
        }
        return byName
    }

    /** Above the roots of the user's trees of [all]: a tag under another of them is under the new one through it. */
    private fun rootsOf(all: Set<String>): List<String> = all.filter { t ->
        n.hierarchy.ancestors(t).none { it in all }
    }

    /** The tag [named] of the entry [ref]'s name bound to it and put above the tags [all] it is not related to. */
    private fun bindParent(ref: TagEntryRef, all: Set<String>, named: String, level: Int): HierarchyOffer? {
        val related = n.hierarchy.ancestors(named) + n.hierarchy.descendants(named)
        val tags = rootsOf(all).filter { it !in related }.sortedByDescending { n.graded(it).size }
        val gets = all.flatMap(n::graded).toSet() - n.graded(named)
        return if (tags.isEmpty()) null
        else
            HierarchyOffer(
                HierarchyKind.BIND_PARENT,
                ref.catalogue,
                tags,
                ref,
                listOf(ref),
                gets.size,
                level,
                named,
                ref in tooGeneral,
            )
    }

    /** A new tag of the entry [ref] above the tags [all]: of two roots at least. */
    private fun newParent(ref: TagEntryRef, all: Set<String>, level: Int): HierarchyOffer? {
        val roots = rootsOf(all)
        return if (roots.size < 2) null
        else
            HierarchyOffer(
                HierarchyKind.NEW_PARENT,
                ref.catalogue,
                roots.sortedByDescending { n.graded(it).size },
                ref,
                listOf(ref),
                all.flatMap(n::graded).toSet().size,
                level,
                rejected = ref in tooGeneral,
            )
    }
}
