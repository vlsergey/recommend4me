package io.github.vlsergey.recommend4me.tag

import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/** What an offer of the tags' graph is ([HierarchyOffers]). */
enum class HierarchyKind {
    /** The first tag under the second. */
    LINK,

    /** The two tags one: bound to one entry. */
    MERGE,

    /** A new tag of [HierarchyOffer.entry] above the tags. */
    NEW_PARENT,

    /** The tag [HierarchyOffer.existing], of the entry's name, bound to it and put above the tags. */
    BIND_PARENT,

    /** The first tag right under the second no more: it is under it through the third already. */
    UNLINK,
}

/**
 * An offer of the tags' graph: its [kind], the tags it is of, the entry of [catalogue] it is by — none of the user's
 * graph alone ([HierarchyKind.UNLINK]) — the way up from the first tag's entry, how many graded works it changes, and
 * how many levels up the catalogue the tags meet — of a new tag, the farther of the two of its nearest pair.
 */
class HierarchyOffer(
    val kind: HierarchyKind,
    val catalogue: String?,
    val tags: List<String>,
    val entry: TagEntryRef?,
    val way: List<TagEntryRef>,
    val graded: Int,
    val levels: Int,
    /** Of [HierarchyKind.BIND_PARENT], the tag of the entry's name to bind and put above. */
    val existing: String? = null,
    /**
     * Of a tag above ([HierarchyKind.NEW_PARENT], [HierarchyKind.BIND_PARENT]), the user said its entry is too general.
     */
    val rejected: Boolean = false,
)

/**
 * The offers of the tags' graph as kept ([HierarchyOffers.kept]), and whether they are to be worked out anew — the
 * names and the works of the tags planned, waiting or worked out now: a word said since is not in them yet.
 */
class KeptOffers(val offers: List<HierarchyOffer>, val pending: Boolean)

/**
 * THE TOP OFFERS OF THE TAGS' GRAPH, as kept: worked out with the names of the tags, in the queue of the computations
 * ([TagRenaming], [HierarchyOffersWorkedOut]) — they want the graded works of every tag, a pass over every work — and
 * kept: a request reads them as kept, never waits for them (user, 2026-10-16).
 */
@Service
class HierarchyOffers(private val renaming: TagRenaming, private val json: JsonMapper) {

    /** The offers kept, the user's word that an entry is too general told on them at once. */
    fun kept(scope: TagScope): KeptOffers {
        val kept = scope.tags.relations.offers()
        // None worked out since the tags were made: worked out with their names, soon
        if (kept == null) renaming.schedule("offers")
        val tooGeneral = scope.tags.entries.tooGeneral()
        // The user's word that an entry is too general is shown at once, the offers worked out anew after the quiet
        val offers =
            kept
                ?.let { json.readValue<List<HierarchyOffer>>(it) }
                .orEmpty()
                .map { o ->
                    val rejected = o.entry != null && o.entry in tooGeneral
                    if (rejected == o.rejected) o
                    else
                        HierarchyOffer(
                            o.kind,
                            o.catalogue,
                            o.tags,
                            o.entry,
                            o.way,
                            o.graded,
                            o.levels,
                            o.existing,
                            rejected,
                        )
                }
        return KeptOffers(offers, renaming.due())
    }

    /** The names of the tags of [offers] and their entries as kept: of one reading. */
    fun namesOf(scope: TagScope, offers: List<HierarchyOffer>): Pair<Map<String, String>, Map<TagEntryRef, TagEntry>> =
        scope.tags.stats.shown(offers.flatMap { it.tags + listOfNotNull(it.existing) }.toSet()) to
            scope.tags.entries.keptEntries(offers.flatMap { it.way }.toSet())
}
