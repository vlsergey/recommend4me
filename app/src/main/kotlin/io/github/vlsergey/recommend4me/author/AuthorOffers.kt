package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.model.AuthorOffer
import io.github.vlsergey.recommend4me.api.model.AuthorOfferPage
import io.github.vlsergey.recommend4me.api.model.AuthorSaid
import io.github.vlsergey.recommend4me.api.model.AuthorSide
import io.github.vlsergey.recommend4me.api.model.AuthorWorkPair
import io.github.vlsergey.recommend4me.duplicate.DuplicateAnswer
import io.github.vlsergey.recommend4me.duplicate.DuplicateModel
import io.github.vlsergey.recommend4me.duplicate.Duplicates
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.TagEntryRef
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component

/**
 * THE OFFERS OF TWO AUTHORS WHO MAY BE ONE, on the evidence of their works: of two authors, the pairs of their works
 * the user said are one work or a part of the other, and those the search of the duplicates kept with the chance they
 * are one. A translation proves no author: its translator is another. Several authors of one site may be one. Two bound
 * to one entry of a catalogue are offered first. Two told apart, or partly one, are offered no more.
 */
@Component
class AuthorOffers(
    private val stores: Stores,
    private val works: Works,
    private val duplicates: Duplicates,
    private val sides: AuthorSides,
) {

    /** Two works of two authors, what is known of them: the user's word they match by, the chance they are one. */
    private class WorkPair(val first: ItemKey, val second: ItemKey, val answer: DuplicateAnswer?, val chance: Double?)

    /** What is known of two authors by the pairs of their works. */
    private class Tally {
        var matching = 0
        var expected = 0.0
    }

    /**
     * What tells of the pairs of authors of a type, read for one request: the works and their authors, the pairs of
     * works that tell of their authors, the pairs of authors told apart or partly one, the entries the authors are
     * bound to — and of these, the pairs bound to one entry and what the pairs of works tell of every pair of authors.
     */
    private class Evidence(
        val library: AuthorLibrary,
        val pairs: List<WorkPair>,
        val told: Map<Pair<Long, Long>, Boolean>,
        val bound: Map<Long, List<TagEntryRef>>,
    ) {
        val oneEntry: Set<Pair<Long, Long>> =
            HashMap<TagEntryRef, MutableList<Long>>()
                .also { sharing ->
                    bound.forEach { (id, refs) -> refs.forEach { sharing.getOrPut(it) { ArrayList() } += id } }
                }
                .values
                .flatMap { ids ->
                    ids.flatMapIndexed { k, a -> ids.drop(k + 1).map { b -> AuthorRepository.pair(a, b) } }
                }
                .toSet()

        val tallies: Map<Pair<Long, Long>, Tally> =
            HashMap<Pair<Long, Long>, Tally>().also { tallies ->
                pairs.forEach { p ->
                    forEachAuthors(p) { authors, _ ->
                        val tally = tallies.getOrPut(authors) { Tally() }
                        if (p.answer != null) tally.matching++ else tally.expected += p.chance ?: 0.0
                    }
                }
            }

        /**
         * The two authors every pair of works tells of, as a pair of ids the smaller first, with the pair of works
         * first of the first: of every author of one work and every author of the other, but those who wrote one of the
         * two together — two writers of one work are not one by it.
         */
        fun forEachAuthors(pair: WorkPair, action: (Pair<Long, Long>, Boolean) -> Unit) {
            val ofFirst = library.authorsOf[pair.first].orEmpty()
            val ofSecond = library.authorsOf[pair.second].orEmpty()
            for (a in ofFirst) for (b in ofSecond) {
                if (a == b || b in ofFirst || a in ofSecond) continue
                action(AuthorRepository.pair(a, b), a <= b)
            }
        }
    }

    /**
     * A page of the pairs of authors of the type — of the [author] alone, when asked — by what was [said] of them:
     * those that may be one, the two bound to one entry first, then the most works matching, then the most expected to
     * — none hidden: of a chance however small — those told apart, those partly one, or all of them. Read for the
     * request and let go. Null of no such type.
     */
    fun offers(typeId: String, said: AuthorSaid, author: Long?, offset: Int, limit: Int): AuthorOfferPage? {
        val type = stores.type(typeId) ?: return null
        val evidence =
            Evidence(AuthorLibrary.of(type), workPairs(type), type.authors.told(), type.authors.entries.all())
        val rows = rowsOf(evidence, said).filter { author == null || it.first == author || it.second == author }
        val page =
            rows
                .sortedWith(
                    compareByDescending<Pair<Long, Long>> { it in evidence.oneEntry }
                        .thenByDescending { evidence.tallies[it]?.matching ?: 0 }
                        .thenByDescending { evidence.tallies[it]?.expected ?: 0.0 }
                        .thenBy { it.first }
                        .thenBy { it.second }
                )
                .drop(offset.coerceAtLeast(0))
                .take(limit.coerceIn(1, MAX_PAGE))
        return AuthorOfferPage(total = rows.size, offers = offersOf(type, evidence, page))
    }

    /**
     * The pairs of works that tell of their authors: those the user said are one work — of one site too — or a part of
     * the other, and the rest the search kept, with their chance. A translation and a pair said to be different tell
     * nothing.
     */
    private fun workPairs(type: TypeStore): List<WorkPair> {
        val said = duplicates.said(type)
        val model = DuplicateModel(type.duplicates.search()?.weights)
        val out = ArrayList<WorkPair>()
        val kept = type.duplicates.all()
        val found = kept.associateBy { it.pair }
        fun tells(answer: DuplicateAnswer?) =
            answer == null || answer == DuplicateAnswer.SAME || answer == DuplicateAnswer.PARTIAL
        kept.forEach { c -> if (tells(said[c.pair])) out += WorkPair(c.first, c.second, said[c.pair], model.chance(c)) }
        said
            .filterKeys { it !in found }
            .forEach { (pair, answer) -> if (tells(answer)) out += WorkPair(pair.first, pair.second, answer, null) }
        // Two works of one site one work: a link of its own, never a pair of the search
        works.of(type).linkedWorks().forEach { members ->
            members.forEachIndexed { k, a ->
                members
                    .drop(k + 1)
                    .filter { it.source == a.source }
                    .forEach { b -> out += WorkPair(a, b, DuplicateAnswer.SAME, null) }
            }
        }
        return out
    }

    /**
     * The pairs of authors who have works, by what was [said] of them: those that may be one — the pairs of works tell
     * of them, or they are bound to one entry — and not told apart nor partly one; those told apart; those partly one;
     * or all of them.
     */
    private fun rowsOf(evidence: Evidence, said: AuthorSaid): Set<Pair<Long, Long>> {
        val known = evidence.library.worksOf.keys
        val answered = evidence.told.filterKeys { it.first in known && it.second in known }
        val offered =
            evidence.tallies.keys + evidence.oneEntry.filter { it.first in known && it.second in known } -
                evidence.told.keys
        return when (said) {
            AuthorSaid.OFFERED -> offered
            AuthorSaid.DIFFERENT -> answered.filterValues { !it }.keys
            AuthorSaid.PARTIAL -> answered.filterValues { it }.keys
            AuthorSaid.ALL -> offered + answered.keys
        }
    }

    /**
     * The offers of the [page] of pairs of authors: each side with its other works, and the pairs of works that tell of
     * the two, those the user said first, then the likeliest; the works, the authors of the sites and the entries read
     * in one go.
     */
    private fun offersOf(type: TypeStore, evidence: Evidence, page: List<Pair<Long, Long>>): List<AuthorOffer> {
        val wanted = page.toSet()
        val ofOffer = HashMap<Pair<Long, Long>, MutableList<WorkPair>>()
        evidence.pairs.forEach { p ->
            evidence.forEachAuthors(p) { authors, inOrder ->
                if (authors in wanted)
                    ofOffer.getOrPut(authors) { ArrayList() } +=
                        if (inOrder) p else WorkPair(p.second, p.first, p.answer, p.chance)
            }
        }
        val library = evidence.library
        val ids = page.flatMap { listOf(it.first, it.second) }
        val shown =
            ShownWorks(
                type,
                library,
                ids.flatMap { library.worksOf[it].orEmpty() } +
                    ofOffer.values.flatten().flatMap { listOf(it.first, it.second) },
            )
        val sites = sides.sites(type, ids)
        val entries = sides.entryInfos(type, evidence.bound.filterKeys { it in ids })
        fun side(id: Long, inPairs: Set<ItemKey>) =
            sides.side(
                id,
                sites,
                entries,
                library,
                library.worksOf[id].orEmpty().filter { it !in inPairs }.map { shown.of(it, id) },
            )
        return page.map { authors ->
            val own =
                ofOffer[authors]
                    .orEmpty()
                    .sortedWith(
                        compareByDescending<WorkPair> { it.answer != null }.thenByDescending { it.chance ?: 0.0 }
                    )
            offer(authors, own, evidence, shown, ::side)
        }
    }

    /** The offer of the two [authors] with the pairs of their works [own]. */
    private fun offer(
        authors: Pair<Long, Long>,
        own: List<WorkPair>,
        evidence: Evidence,
        shown: ShownWorks,
        side: (Long, Set<ItemKey>) -> AuthorSide,
    ) =
        AuthorOffer(
            first = side(authors.first, own.map { it.first }.toSet()),
            second = side(authors.second, own.map { it.second }.toSet()),
            matching = evidence.tallies[authors]?.matching ?: 0,
            expected = (evidence.tallies[authors]?.expected ?: 0.0).toBigDecimal(),
            pairs =
                own.map { p ->
                    AuthorWorkPair(
                        shown.of(p.first, authors.first),
                        shown.of(p.second, authors.second),
                        p.answer?.let { AuthorWorkPair.Answer.valueOf(it.name) },
                        p.chance?.toBigDecimal(),
                    )
                },
            oneEntry = authors in evidence.oneEntry,
            said =
                evidence.told[authors]?.let { partly ->
                    if (partly) AuthorOffer.Said.PARTIAL else AuthorOffer.Said.DIFFERENT
                },
        )

    private companion object {
        /** The pairs a page holds at most. */
        const val MAX_PAGE = 200
    }
}
