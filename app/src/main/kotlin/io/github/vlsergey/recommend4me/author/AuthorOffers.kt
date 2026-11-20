package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.model.AuthorOffer
import io.github.vlsergey.recommend4me.api.model.AuthorOfferPage
import io.github.vlsergey.recommend4me.api.model.AuthorSaid
import io.github.vlsergey.recommend4me.api.model.AuthorSide
import io.github.vlsergey.recommend4me.api.model.AuthorWorkPair
import io.github.vlsergey.recommend4me.duplicate.DuplicateAnswer
import io.github.vlsergey.recommend4me.duplicate.PairModels
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import java.time.ZoneOffset
import org.springframework.stereotype.Component

/**
 * THE OFFERS OF TWO AUTHORS WHO MAY BE ONE, on the evidence of their works, as the last search of the authors'
 * duplicates found them ([AuthorSearch]): a page of them chosen in the database by its filter and its order
 * ([AuthorCandidateRepository.page]), and of its authors alone their works and the pairs of them read by their keys —
 * those the user said are one work or a part of the other, and those the search of the works' duplicates kept with the
 * chance they are one (owner, 2026-11-20: "the tab of the authors' duplicates reads a bounded amount of the database").
 * A translation proves no author: its translator is another. Several authors of one site may be one. Two told apart, or
 * partly one, leave the offers at once.
 */
@Component
class AuthorOffers(
    private val stores: Stores,
    private val sides: AuthorSides,
    private val models: PairModels,
    private val search: AuthorSearch,
) {

    /** A page of the pairs of authors of the type the [filter] lets, of what was [said] of them. Null of no type. */
    fun offers(typeId: String, said: AuthorSaid, filter: OfferFilter, offset: Int, limit: Int): AuthorOfferPage? {
        val type = stores.type(typeId) ?: return null
        val (total, rows) =
            type.authorCandidates.page(said, filter, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))
        val made = type.authorCandidates.search()
        return AuthorOfferPage(
            total = total,
            offers = offersOf(type, rows),
            pending = search.isSearching(type),
            searchedAt = made?.madeAt?.atOffset(ZoneOffset.UTC),
            works = made?.works,
        )
    }

    /**
     * The offers of the [rows]: each side with its other works, and the pairs of works that tell of the two, those the
     * user said first, then the likeliest; their works, the authors of the sites and the entries read by their keys.
     */
    private fun offersOf(type: TypeStore, rows: List<AuthorCandidate>): List<AuthorOffer> {
        val ids = rows.flatMap { listOf(it.first, it.second) }.distinct()
        val library = AuthorLibrary.ofAuthors(type, ids)
        val evidence = AuthorEvidence(library)
        val wanted = rows.map { it.pair }.toSet()
        val ofOffer = HashMap<Pair<Long, Long>, MutableList<WorkPair>>()
        workPairs(type, library).forEach { p ->
            evidence.add(p)
            evidence.forEachAuthors(p) { authors, inOrder ->
                if (authors in wanted)
                    ofOffer.getOrPut(authors) { ArrayList() } +=
                        if (inOrder) p else WorkPair(p.second, p.first, p.answer, p.chance)
            }
        }
        val shown =
            ShownWorks(
                type,
                library,
                ids.flatMap { library.worksOf[it].orEmpty() } +
                    ofOffer.values.flatten().flatMap { listOf(it.first, it.second) },
            )
        val sites = sides.sites(type, ids)
        val entries = sides.entryInfos(type, type.authors.entries.of(ids))
        fun side(id: Long, inPairs: Set<ItemKey>) =
            sides.side(
                id,
                sites,
                entries,
                library,
                library.worksOf[id].orEmpty().filter { it !in inPairs }.map { shown.of(it, id) },
            )
        return rows.map { row ->
            val own =
                ofOffer[row.pair]
                    .orEmpty()
                    .sortedWith(
                        compareByDescending<WorkPair> { it.answer != null }.thenByDescending { it.chance ?: 0.0 }
                    )
            offer(row, own, evidence.tallies[row.pair], shown, ::side)
        }
    }

    /**
     * The pairs of the works of the [library] that tell of their authors: two items of one work — of one site too —,
     * and those the search of the works' duplicates kept, by the word kept with them — one work or a part of the other
     * — or by the chance the models give them now. A translation and a pair said to be different tell nothing.
     */
    private fun workPairs(type: TypeStore, library: AuthorLibrary): List<WorkPair> {
        val works = library.writersOf.keys
        val oneWork = HashSet<Pair<ItemKey, ItemKey>>()
        type.links.groupsOf(works).forEach { members ->
            val own = members.filter { it in works }
            own.forEachIndexed { k, a -> own.drop(k + 1).forEach { b -> oneWork += type.links.ordered(a, b) } }
        }
        val kept = type.duplicates.reads.among(works)
        val words = type.duplicates.reads.wordsOf(kept.map { it.pair })
        val chances = models.of(type)
        val out = oneWork.map { WorkPair(it.first, it.second, DuplicateAnswer.SAME, null) }.toMutableList()
        kept
            .filter { it.pair !in oneWork }
            .forEach { c ->
                val answer = words[c.pair]
                if (WorkPair.tells(answer))
                    out += WorkPair(c.first, c.second, answer, if (answer == null) chances?.same(c) else null)
            }
        return out
    }

    /** The offer of the pair of authors [row] with the pairs of their works [own], counted in [tally]. */
    private fun offer(
        row: AuthorCandidate,
        own: List<WorkPair>,
        tally: Tally?,
        shown: ShownWorks,
        side: (Long, Set<ItemKey>) -> AuthorSide,
    ) =
        AuthorOffer(
            first = side(row.first, own.map { it.first }.toSet()),
            second = side(row.second, own.map { it.second }.toSet()),
            matching = tally?.matching ?: 0,
            expected = (tally?.expected ?: 0.0).toBigDecimal(),
            pairs =
                own.map { p ->
                    AuthorWorkPair(
                        shown.of(p.first, row.first),
                        shown.of(p.second, row.second),
                        p.answer?.let { AuthorWorkPair.Answer.valueOf(it.name) },
                        p.chance?.toBigDecimal(),
                    )
                },
            oneEntry = row.oneEntry,
            said = row.partly?.let { partly -> if (partly) AuthorOffer.Said.PARTIAL else AuthorOffer.Said.DIFFERENT },
        )

    private companion object {
        /** The pairs a page holds at most. */
        const val MAX_PAGE = 200
    }
}
