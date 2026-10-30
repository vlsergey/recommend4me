package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.model.AuthorOffer
import io.github.vlsergey.recommend4me.api.model.AuthorOfferPage
import io.github.vlsergey.recommend4me.api.model.AuthorSide
import io.github.vlsergey.recommend4me.api.model.AuthorWork
import io.github.vlsergey.recommend4me.api.model.AuthorWorkPair
import io.github.vlsergey.recommend4me.api.model.SiteAuthor
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.duplicate.DuplicateAnswer
import io.github.vlsergey.recommend4me.duplicate.DuplicateModel
import io.github.vlsergey.recommend4me.duplicate.Duplicates
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.Works
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(Authors::class.java)

/**
 * THE NORMALIZED AUTHORS: every author of every site bound to one ([AuthorRepository]), at first of
 * their own — never by the likeness of a name, a name on two sites may be two people and one person
 * two names. The user merges them on the evidence of their works ([offers]): of two authors, the pairs
 * of their works the user said are one work or a part of the other, and those the search of the
 * duplicates kept with the chance they are one. A translation proves no author: its translator is
 * another. Several authors of one site may be one.
 */
@Component
class Authors(private val stores: Stores, private val works: Works, private val duplicates: Duplicates) {

    /** The source's facet of the authors; null when it has none. */
    private fun facetOf(s: SourceStore): String? = s.schema.facets.firstOrNull { it.role == FacetRole.AUTHOR }?.key

    /** The [keys] of the source not bound yet bound; one at a time: two at once would bind one author twice. */
    @Synchronized
    private fun bind(s: SourceStore, keys: Collection<String>) {
        val fresh = keys - s.authors.bindingsOf(s.id).keys
        if (fresh.isEmpty()) return
        s.authors.bindNew(s.id, fresh, Instant.now())
        log.info("{}: {} authors bound to normalized authors of their own", s.id, fresh.size)
    }

    /** The authors of the works [event] names bound at once, before anything reads the works. */
    @Order(-100)
    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        val s = stores.source(event.source) ?: return
        val facet = facetOf(s) ?: return
        val keys = s.items.facetsOf(event.ids).values.flatMap { it[facet].orEmpty() }.toSet()
        if (keys.isNotEmpty()) bind(s, keys)
    }

    /** Before anything reads the authors: every author of every site not bound yet bound. */
    @Order(-10)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        stores.sources.forEach { s ->
            try {
                facetOf(s)?.let { bind(s, s.items.valueUses(it).keys) }
            } catch (e: Exception) {
                log.error("{}: the authors were not bound", s.id, e)
            }
        }
    }

    /** Two works of two authors, what is known of them: the user's word they match by, the chance they are one. */
    private class WorkPair(val first: ItemKey, val second: ItemKey, val answer: DuplicateAnswer?, val chance: Double?)

    /** The works and the authors of a type, read for one request: of every work its normalized authors, of every author their works. */
    private class Library(val authorsOf: Map<ItemKey, Set<Long>>) {
        val worksOf: Map<Long, List<ItemKey>> = HashMap<Long, MutableList<ItemKey>>().also { out ->
            authorsOf.forEach { (work, authors) -> authors.forEach { out.getOrPut(it) { ArrayList() } += work } }
        }
    }

    private fun library(type: TypeStore): Library {
        val authorsOf = HashMap<ItemKey, Set<Long>>()
        type.sources.forEach { s ->
            val facet = facetOf(s) ?: return@forEach
            val bound = s.authors.bindingsOf(s.id)
            s.items.forEachFacet(facet) { id, keys -> authorsOf[ItemKey(s.id, id)] = keys.mapNotNullTo(HashSet()) { bound[it] } }
        }
        return Library(authorsOf)
    }

    /**
     * The pairs of works that tell of their authors: those the user said are one work — of one site too
     * — or a part of the other, and the rest the search kept, with their chance. A translation and
     * a pair said to be different tell nothing.
     */
    private fun workPairs(type: TypeStore): List<WorkPair> {
        val said = duplicates.said(type)
        val model = DuplicateModel(type.duplicates.search()?.weights)
        val out = ArrayList<WorkPair>()
        val kept = type.duplicates.all()
        val found = kept.associateBy { it.pair }
        fun tells(answer: DuplicateAnswer?) = answer == null || answer == DuplicateAnswer.SAME || answer == DuplicateAnswer.PARTIAL
        kept.forEach { c -> if (tells(said[c.pair])) out += WorkPair(c.first, c.second, said[c.pair], model.chance(c)) }
        said.filterKeys { it !in found }.forEach { (pair, answer) -> if (tells(answer)) out += WorkPair(pair.first, pair.second, answer, null) }
        // Two works of one site one work: a link of its own, never a pair of the search
        works.of(type).linkedWorks().forEach { members ->
            members.forEachIndexed { k, a ->
                members.drop(k + 1).filter { it.source == a.source }.forEach { b -> out += WorkPair(a, b, DuplicateAnswer.SAME, null) }
            }
        }
        return out
    }

    /** What is known of two authors by the pairs of their works. */
    private class Tally {
        var matching = 0
        var expected = 0.0
    }

    /**
     * The two authors every pair of works tells of, as a pair of ids the smaller first, with the pair of
     * works first of the first: of every author of one work and every author of the other, but
     * those who wrote one of the two together — two writers of one work are not one by it.
     */
    private fun forEachAuthors(pair: WorkPair, library: Library, action: (Pair<Long, Long>, Boolean) -> Unit) {
        val ofFirst = library.authorsOf[pair.first].orEmpty()
        val ofSecond = library.authorsOf[pair.second].orEmpty()
        for (a in ofFirst) for (b in ofSecond) {
            if (a == b || b in ofFirst || a in ofSecond) continue
            action(AuthorRepository.pair(a, b), a <= b)
        }
    }

    /**
     * A page of the pairs of authors of the type: those that may be one, the most works matching
     * first, then the most expected to — none hidden: of a chance however small — or, of [distinct],
     * the pairs the user told apart. Read for the request and let go. Null of no such type.
     */
    fun offers(typeId: String, distinct: Boolean, offset: Int, limit: Int): AuthorOfferPage? {
        val type = stores.type(typeId) ?: return null
        val library = library(type)
        val pairs = workPairs(type)
        val told = type.authors.distinct()
        val tallies = HashMap<Pair<Long, Long>, Tally>()
        pairs.forEach { p ->
            forEachAuthors(p, library) { authors, _ ->
                val tally = tallies.getOrPut(authors) { Tally() }
                if (p.answer != null) tally.matching++ else tally.expected += p.chance ?: 0.0
            }
        }
        val known = library.worksOf.keys
        val rows = (if (distinct) told.filter { it.first in known && it.second in known } else (tallies.keys - told)).toList()
        val page = rows.sortedWith(
            compareByDescending<Pair<Long, Long>> { tallies[it]?.matching ?: 0 }.thenByDescending { tallies[it]?.expected ?: 0.0 }.thenBy { it.first }.thenBy { it.second },
        ).drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, MAX_PAGE))
        val wanted = page.toSet()
        val ofOffer = HashMap<Pair<Long, Long>, MutableList<WorkPair>>()
        pairs.forEach { p ->
            forEachAuthors(p, library) { authors, inOrder ->
                if (authors in wanted) ofOffer.getOrPut(authors) { ArrayList() } += if (inOrder) p else WorkPair(p.second, p.first, p.answer, p.chance)
            }
        }
        val ids = page.flatMap { listOf(it.first, it.second) }
        val titles = titles(type, ids.flatMap { library.worksOf[it].orEmpty() } + ofOffer.values.flatten().flatMap { listOf(it.first, it.second) })
        val sites = sites(type, ids)
        fun work(key: ItemKey) = AuthorWork(key.source, key.id, titles[key] ?: key.id)
        return AuthorOfferPage(
            total = rows.size,
            offers = page.map { authors ->
                val own = ofOffer[authors].orEmpty().sortedWith(compareByDescending<WorkPair> { it.answer != null }.thenByDescending { it.chance ?: 0.0 })
                AuthorOffer(
                    first = side(authors.first, sites, library, own.map { it.first }.toSet(), ::work),
                    second = side(authors.second, sites, library, own.map { it.second }.toSet(), ::work),
                    matching = tallies[authors]?.matching ?: 0,
                    expected = (tallies[authors]?.expected ?: 0.0).toBigDecimal(),
                    pairs = own.map { p ->
                        AuthorWorkPair(work(p.first), work(p.second), p.answer?.let { AuthorWorkPair.Answer.valueOf(it.name) }, p.chance?.toBigDecimal())
                    },
                )
            },
        )
    }

    /** The normalized authors of several authors of the sites of the type, of the most works first; null of no such type. */
    fun merged(typeId: String): List<AuthorSide>? {
        val type = stores.type(typeId) ?: return null
        val library = library(type)
        val sources = type.sources.map { it.id }.toSet()
        val ids = type.authors.merged().filterValues { refs -> refs.any { it.source in sources } }.keys
        val titles = titles(type, ids.flatMap { library.worksOf[it].orEmpty() })
        val sites = sites(type, ids)
        return ids.map { side(it, sites, library, emptySet()) { key -> AuthorWork(key.source, key.id, titles[key] ?: key.id) } }.sortedByDescending { it.works }
    }

    private fun side(id: Long, sites: Map<Long, List<SiteAuthor>>, library: Library, inPairs: Set<ItemKey>, work: (ItemKey) -> AuthorWork): AuthorSide {
        val own = library.worksOf[id].orEmpty()
        return AuthorSide(id = id, sites = sites[id].orEmpty(), works = own.size, rest = own.filter { it !in inPairs }.map(work))
    }

    /** The authors of the sites of the normalized authors [ids]: their names and how many works each has, of every source in one go. */
    private fun sites(type: TypeStore, ids: Collection<Long>): Map<Long, List<SiteAuthor>> {
        val refs = type.authors.sitesOf(ids)
        val inSources = refs.values.flatten().groupBy { it.source }
        val shown = HashMap<SiteAuthorRef, SiteAuthor>()
        inSources.forEach { (source, own) ->
            val s = type.source(source) ?: return@forEach
            val facet = facetOf(s) ?: return@forEach
            val names = s.names(facet, own.map { it.author })
            val uses = s.items.valueUses(facet)
            own.forEach { shown[it] = SiteAuthor(source, it.author, names[it.author] ?: it.author, uses[it.author] ?: 0) }
        }
        return refs.mapValues { (_, list) -> list.mapNotNull { shown[it] } }
    }

    /** The titles of the works as the user sees them: each source's read in one go. */
    private fun titles(type: TypeStore, keys: Collection<ItemKey>): Map<ItemKey, String> {
        val out = HashMap<ItemKey, String>()
        keys.distinct().groupBy { it.source }.forEach { (source, own) ->
            val s = type.source(source) ?: return@forEach
            val ids = own.map { it.id }
            val heads = s.items.heads(ids)
            val fields = s.corrections.fieldsOf(ids)
            ids.forEach { id -> heads[id]?.let { out[ItemKey(source, id)] = Corrected.title(it.title, fields[id].orEmpty()) } }
        }
        return out
    }

    /** The two authors one writer: merged, the smaller id kept. False when either is no more. */
    fun merge(a: Long, b: Long): Boolean {
        val type = stores.types.firstOrNull() ?: return false
        if (a == b || !type.authors.exists(a) || !type.authors.exists(b)) return false
        type.authors.merge(maxOf(a, b), minOf(a, b), Instant.now())
        return true
    }

    /** The two authors told apart, or the word taken back. False when either is no more. */
    fun setDistinct(a: Long, b: Long, different: Boolean): Boolean {
        val type = stores.types.firstOrNull() ?: return false
        if (a == b || !type.authors.exists(a) || !type.authors.exists(b)) return false
        type.authors.setDistinct(a, b, different, Instant.now())
        return true
    }

    /** The author of a site taken out of the normalized author [id], to one of their own. False when it is not of it. */
    fun takeOut(id: Long, source: String, author: String): Boolean {
        val type = stores.types.firstOrNull() ?: return false
        if (SiteAuthorRef(source, author) !in type.authors.sitesOf(listOf(id))[id].orEmpty()) return false
        type.authors.takeOut(SiteAuthorRef(source, author), id, Instant.now())
        return true
    }

    companion object {
        /** The pairs a page holds at most. */
        private const val MAX_PAGE = 200
    }
}
