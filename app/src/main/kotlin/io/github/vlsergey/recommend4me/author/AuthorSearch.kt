package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.api.model.TaskPriority
import io.github.vlsergey.recommend4me.duplicate.PairAnswers
import io.github.vlsergey.recommend4me.laps.Laps
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.BoundEntry
import io.github.vlsergey.recommend4me.tag.TagEntryRef
import io.github.vlsergey.recommend4me.task.Task
import io.github.vlsergey.recommend4me.task.Tasks
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(AuthorSearch::class.java)

/**
 * THE SEARCH OF THE AUTHORS' DUPLICATES, as the works' is (owner, 2026-11-20: "by analogy with the search of the
 * duplicates of the books"; by a button, not by itself): every pair of normalized authors the pairs of their works tell
 * of — one work or a part of the other by the user's word, or the chance of one work the last search of the works' gave
 * the pair — or bound to one entry of a catalogue, with what tells of it ([AuthorEvidence]), and every pair told apart
 * or partly one; kept in place of the last search's ([AuthorCandidateRepository]), what a page of the offers is chosen
 * of; and every author of works with their works counted and their names ([AuthorListingRepository]), what the tab of
 * all the authors is. Planned by the user's pressing, and once at a start of a type never searched.
 */
@Component
class AuthorSearch(private val stores: Stores, private val tasks: Tasks, private val answers: PairAnswers) {

    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        tasks.resumption.resumes(TaskKind.SEARCH_AUTHOR_DUPLICATES) { left, reason ->
            left.work.type?.let(stores::type)?.let { plan(it, reason, left.priority) }
        }
    }

    /** Every type of authors never searched yet: searched once, in the background. */
    @Order(START_ORDER)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.types
            .filter { t -> t.sources.any { authorFacetOf(it) != null } && t.authorCandidates.search() == null }
            .forEach { plan(it, "start") }
    }

    /** Whether a search of the type is planned or running: the pairs are to come. */
    fun isSearching(type: TypeStore) = tasks.due(work(type))

    /** The search of the type planned, in the background — first of all when the user pressed for it ([priority]). */
    fun plan(type: TypeStore, reason: String, priority: TaskPriority? = null) {
        if (tasks.due(work(type), running = false)) return
        tasks.plan(work(type), Instant.now(), reason, priority = priority) { search(type) }
    }

    private fun work(type: TypeStore) = Task(TaskKind.SEARCH_AUTHOR_DUPLICATES, type = type.id)

    /**
     * The pairs of authors of the type: of every work its authors read whole — a search's, never a request's —, every
     * pair of works the user said one work or a part of the other, and every pair of the last search of the works' not
     * answered on, by its chance, streamed; the pairs told apart, partly one, bound to one entry.
     */
    private fun search(type: TypeStore) {
        val laps = Laps()
        val library = AuthorLibrary.of(type)
        laps.lap("works")
        val evidence = AuthorEvidence(library)
        val said = answers.said(type)
        said.forEach { (pair, answer) ->
            if (WorkPair.tells(answer)) evidence.add(WorkPair(pair.first, pair.second, answer, null))
        }
        laps.lap("words")
        // A pair answered since the search of the works' told by the word alone
        type.duplicates.reads.forEachChance { first, second, chance ->
            if (first to second !in said) evidence.add(WorkPair(first, second, null, chance))
        }
        laps.lap("chances")
        val told = type.authors.distinct.told()
        val oneEntry = AuthorEvidence.oneEntry(type.authors.entries.all())
        val known = library.sitesOf
        val pairs =
            (evidence.tallies.keys + oneEntry + told.keys)
                .filter { (a, b) -> a in known && b in known }
                .map { pair ->
                    val tally = evidence.tallies[pair]
                    AuthorCandidate(
                        pair.first,
                        pair.second,
                        pair in oneEntry,
                        tally?.matching ?: 0,
                        tally?.expected ?: 0.0,
                        known[pair.first].orEmpty() + known[pair.second].orEmpty(),
                        told[pair],
                    )
                }
        laps.lap("tallied")
        type.authorCandidates.replace(pairs, Instant.now(), library.writersOf.size)
        laps.lap("written")
        listed(type, library)
        laps.lap("listed")
        log.info(
            "{}: the authors' duplicates looked for among {} works — {} pairs of authors, {} of them told apart or " +
                "partly one: {}",
            type.id,
            library.writersOf.size,
            pairs.size,
            pairs.count { it.partly != null },
            laps,
        )
    }

    /**
     * Every author of works of the [library], kept as the tab of all the authors chooses its page of them
     * ([AuthorListingRepository]): their works counted, whether merged, whether bound to an entry, their names on the
     * sites and of their entries.
     */
    private fun listed(type: TypeStore, library: AuthorLibrary) {
        val bound = type.authors.entries.all()
        val merged = type.authors.merged().keys
        val names = namesOf(type, bound)
        type.authorListing.replace(
            library.worksOf.map { (id, works) ->
                AuthorListed(id, works.size, id in merged, id in bound, names[id].orEmpty().joinToString(NAMES))
            }
        )
    }

    /**
     * Of every author their names, lowercase: of every author of the sites bound to them, and of the entries they are
     * [bound] to — what a name is looked for in.
     */
    private fun namesOf(type: TypeStore, bound: Map<Long, List<TagEntryRef>>): Map<Long, Set<String>> {
        val out = HashMap<Long, MutableSet<String>>()
        type.sources.forEach { s ->
            val facet = authorFacetOf(s) ?: return@forEach
            val ofKey = s.authors.bindingsOf(s.id)
            val names = s.names(facet, ofKey.keys)
            ofKey.forEach { (key, id) -> out.getOrPut(id) { HashSet() } += (names[key] ?: key).lowercase() }
        }
        val kept = type.tags.entries.keptEntries(bound.values.flatten().toSet())
        bound.forEach { (id, refs) ->
            refs.forEach { ref ->
                kept[ref]?.let { e ->
                    out.getOrPut(id) { HashSet() } += BoundEntry(ref.catalogue, e).names.map { it.lowercase() }
                }
            }
        }
        return out
    }

    private companion object {
        /**
         * The place of [start] among the listeners of the application being ready: after the search of the works',
         * whose chances it reads.
         */
        const val START_ORDER = 30

        /** Between two names of an author kept: a character no name holds, so a name looked for spans no two. */
        const val NAMES = "\u0001"
    }
}
