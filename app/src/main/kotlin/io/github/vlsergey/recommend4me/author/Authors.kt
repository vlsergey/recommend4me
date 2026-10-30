package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.model.AuthorKind
import io.github.vlsergey.recommend4me.api.model.AuthorOffer
import io.github.vlsergey.recommend4me.api.model.AuthorOfferPage
import io.github.vlsergey.recommend4me.api.model.AuthorPage
import io.github.vlsergey.recommend4me.api.model.AuthorRef
import io.github.vlsergey.recommend4me.api.model.AuthorSaid
import io.github.vlsergey.recommend4me.api.model.AuthorSide
import io.github.vlsergey.recommend4me.api.model.AuthorWork
import io.github.vlsergey.recommend4me.api.model.AuthorWorkPair
import io.github.vlsergey.recommend4me.api.model.FoundAuthorEntry
import io.github.vlsergey.recommend4me.api.model.SiteAuthor
import io.github.vlsergey.recommend4me.api.model.TagEntryInfo
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.duplicate.DuplicateAnswer
import io.github.vlsergey.recommend4me.duplicate.DuplicateModel
import io.github.vlsergey.recommend4me.duplicate.Duplicates
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.BoundEntry
import io.github.vlsergey.recommend4me.tag.TagEntryRef
import io.github.vlsergey.recommend4me.work.Works
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
 * another. Several authors of one site may be one. Two authors may be partly one: a part of the works
 * of one is the other's — an account putting up the works of others, two writing together — neither
 * merged nor offered again. An author may be bound to the entries of the catalogues of tags (Wikidata);
 * two bound to one entry are offered first.
 */
@Component
class Authors(
    private val stores: Stores,
    private val works: Works,
    private val duplicates: Duplicates,
    private val plugins: Plugins,
    /** The languages an entry is asked in, the user's first: of its texts, those of the first that has them are shown. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) {

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
     * A page of the pairs of authors of the type — of the [author] alone, when asked — by what was
     * [said] of them: those that may be one, the two bound to one entry first, then the most works
     * matching, then the most expected to — none hidden: of a chance however small — those told apart,
     * those partly one, or all of them. Read for the request and let go. Null of no such type.
     */
    fun offers(typeId: String, said: AuthorSaid, author: Long?, offset: Int, limit: Int): AuthorOfferPage? {
        val type = stores.type(typeId) ?: return null
        val library = library(type)
        val pairs = workPairs(type)
        val told = type.authors.told()
        val bound = type.authors.entries()
        val sharing = HashMap<TagEntryRef, MutableList<Long>>()
        bound.forEach { (id, refs) -> refs.forEach { sharing.getOrPut(it) { ArrayList() } += id } }
        val oneEntry = sharing.values.flatMap { ids -> ids.flatMapIndexed { k, a -> ids.drop(k + 1).map { b -> AuthorRepository.pair(a, b) } } }.toSet()
        val tallies = HashMap<Pair<Long, Long>, Tally>()
        pairs.forEach { p ->
            forEachAuthors(p, library) { authors, _ ->
                val tally = tallies.getOrPut(authors) { Tally() }
                if (p.answer != null) tally.matching++ else tally.expected += p.chance ?: 0.0
            }
        }
        val known = library.worksOf.keys
        val answered = told.filterKeys { it.first in known && it.second in known }
        val offered = tallies.keys + oneEntry.filter { it.first in known && it.second in known } - told.keys
        val rows = when (said) {
            AuthorSaid.OFFERED -> offered
            AuthorSaid.DIFFERENT -> answered.filterValues { !it }.keys
            AuthorSaid.PARTIAL -> answered.filterValues { it }.keys
            AuthorSaid.ALL -> offered + answered.keys
        }.filter { author == null || it.first == author || it.second == author }
        val page = rows.sortedWith(
            compareByDescending<Pair<Long, Long>> { it in oneEntry }.thenByDescending { tallies[it]?.matching ?: 0 }.thenByDescending { tallies[it]?.expected ?: 0.0 }
                .thenBy { it.first }.thenBy { it.second },
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
        val entries = entryInfos(type, bound.filterKeys { it in ids })
        fun work(key: ItemKey) = work(type, titles, key)
        fun side(id: Long, inPairs: Set<ItemKey>) = side(id, sites, entries, library, library.worksOf[id].orEmpty().filter { it !in inPairs }.map(::work))
        return AuthorOfferPage(
            total = rows.size,
            offers = page.map { authors ->
                val own = ofOffer[authors].orEmpty().sortedWith(compareByDescending<WorkPair> { it.answer != null }.thenByDescending { it.chance ?: 0.0 })
                AuthorOffer(
                    first = side(authors.first, own.map { it.first }.toSet()),
                    second = side(authors.second, own.map { it.second }.toSet()),
                    matching = tallies[authors]?.matching ?: 0,
                    expected = (tallies[authors]?.expected ?: 0.0).toBigDecimal(),
                    pairs = own.map { p ->
                        AuthorWorkPair(work(p.first), work(p.second), p.answer?.let { AuthorWorkPair.Answer.valueOf(it.name) }, p.chance?.toBigDecimal())
                    },
                    oneEntry = authors in oneEntry,
                    said = told[authors]?.let { partly -> if (partly) AuthorOffer.Said.PARTIAL else AuthorOffer.Said.DIFFERENT },
                )
            },
        )
    }

    /**
     * A page of the authors of the type with works, the most works first: every one, of several authors
     * of the sites, or bound to an entry; of [search], those a name of theirs on a site or of their
     * entry holds it. Their works are not listed. Null of no such type.
     */
    fun list(typeId: String, kind: AuthorKind, search: String?, offset: Int, limit: Int): AuthorPage? {
        val type = stores.type(typeId) ?: return null
        val library = library(type)
        val bound = type.authors.entries()
        val ofKind: Set<Long> = when (kind) {
            AuthorKind.ALL -> library.worksOf.keys
            AuthorKind.MERGED -> library.worksOf.keys intersect type.authors.merged().keys
            AuthorKind.CATALOGUED -> library.worksOf.keys intersect bound.keys
        }
        val needle = search?.trim()?.lowercase().orEmpty()
        val found = if (needle.isEmpty()) ofKind else ofKind intersect named(type, needle, bound)
        val page = found.sortedWith(compareByDescending<Long> { library.worksOf[it]?.size ?: 0 }.thenBy { it }).drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, MAX_PAGE))
        val sites = sites(type, page)
        val entries = entryInfos(type, bound.filterKeys { it in page })
        return AuthorPage(total = found.size, authors = page.map { side(it, sites, entries, library, emptyList()) })
    }

    /** The authors a name of theirs holds [needle], lowercase: of the sites and of the entries they are bound to. */
    private fun named(type: TypeStore, needle: String, bound: Map<Long, List<TagEntryRef>>): Set<Long> {
        val out = HashSet<Long>()
        type.sources.forEach { s ->
            val facet = facetOf(s) ?: return@forEach
            val ofKey = s.authors.bindingsOf(s.id)
            val names = s.names(facet, ofKey.keys)
            ofKey.forEach { (key, id) -> if ((names[key] ?: key).lowercase().contains(needle)) out += id }
        }
        val kept = type.tags.keptEntries(bound.values.flatten().toSet())
        bound.forEach { (id, refs) ->
            if (refs.any { ref -> kept[ref]?.let { e -> BoundEntry(ref.catalogue, e).names.any { it.lowercase().contains(needle) } } == true }) out += id
        }
        return out
    }

    /** The author [id] with every work of theirs; null of no such type or author. */
    fun one(typeId: String, id: Long): AuthorSide? {
        val type = stores.type(typeId) ?: return null
        if (!type.authors.exists(id)) return null
        val library = library(type)
        val own = library.worksOf[id].orEmpty()
        val titles = titles(type, own)
        val entries = entryInfos(type, type.authors.entries().filterKeys { it == id })
        return side(id, sites(type, listOf(id)), entries, library, own.map { work(type, titles, it) })
    }

    private fun work(type: TypeStore, titles: Map<ItemKey, String>, key: ItemKey) =
        AuthorWork(key.source, key.id, titles[key] ?: key.id, type.source(key.source)?.source?.itemUrl(key.id).orEmpty())

    private fun side(id: Long, sites: Map<Long, List<SiteAuthor>>, entries: Map<Long, List<TagEntryInfo>>, library: Library, rest: List<AuthorWork>) =
        AuthorSide(id = id, sites = sites[id].orEmpty(), works = library.worksOf[id].orEmpty().size, rest = rest, propertyEntries = entries[id].orEmpty())

    /** The entries [bound] as kept, by the author. */
    private fun entryInfos(type: TypeStore, bound: Map<Long, List<TagEntryRef>>): Map<Long, List<TagEntryInfo>> {
        val kept = type.tags.keptEntries(bound.values.flatten().toSet())
        return bound.mapValues { (_, refs) ->
            refs.mapNotNull { ref ->
                kept[ref]?.let { e -> TagEntryInfo(ref.catalogue, e.id, e.url, BoundEntry(ref.catalogue, e).names, e.labels.first(), e.descriptions.first()) }
            }
        }
    }

    private fun Map<String, String>.first(): String? = languages.firstNotNullOfOrNull { this[it] }

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

    /** The two authors told apart — or, [partly], a part of the works of one the other's. False when either is no more. */
    fun tell(a: Long, b: Long, partly: Boolean): Boolean {
        val type = stores.types.firstOrNull() ?: return false
        if (a == b || !type.authors.exists(a) || !type.authors.exists(b)) return false
        type.authors.tell(a, b, partly, Instant.now())
        return true
    }

    /** The word said of the two authors taken back. False when either is no more. */
    fun takeBack(a: Long, b: Long): Boolean {
        val type = stores.types.firstOrNull() ?: return false
        if (a == b || !type.authors.exists(a) || !type.authors.exists(b)) return false
        type.authors.takeBack(a, b)
        return true
    }

    /** The entries every catalogue of tags finds by [text] — an id or a page's address is the entry itself — each with the authors of the type bound to it already. */
    fun findEntries(typeId: String, text: String): List<FoundAuthorEntry>? {
        val type = stores.type(typeId) ?: return null
        if (text.isBlank()) return emptyList()
        val found = plugins.tagCatalogues.flatMap { c -> c.find(text, languages).map { c.id to it } }
        val ofEntry = HashMap<TagEntryRef, MutableList<Long>>()
        type.authors.entries().forEach { (id, refs) -> refs.forEach { ofEntry.getOrPut(it) { ArrayList() } += id } }
        val sites = sites(type, found.flatMap { (c, e) -> ofEntry[TagEntryRef(c, e.id)].orEmpty() })
        return found.map { (c, e) ->
            val authors = ofEntry[TagEntryRef(c, e.id)].orEmpty().map { id -> AuthorRef(id, sites[id].orEmpty().maxByOrNull { it.works }?.name ?: id.toString()) }
            FoundAuthorEntry(c, e.id, e.url, authors, e.labels.first(), e.descriptions.first())
        }
    }

    /**
     * The author [id] bound to the entry [entry] of [catalogue] besides the ones they are, the entry
     * read of the catalogue now and kept — or, [bound] false, no more to it. False when the author, the
     * catalogue or the entry is no more.
     */
    fun bindEntry(id: Long, catalogue: String, entry: String, bound: Boolean): Boolean {
        val type = stores.types.firstOrNull() ?: return false
        if (!type.authors.exists(id)) return false
        val now = Instant.now()
        if (!bound) {
            type.authors.bindEntry(id, TagEntryRef(catalogue, entry), false, now)
            return true
        }
        val source = plugins.tagCatalogues.firstOrNull { it.id == catalogue } ?: return false
        val read = source.entries(listOf(entry), languages).firstOrNull() ?: return false
        type.tags.keepEntry(catalogue, read, now)
        type.authors.bindEntry(id, TagEntryRef(catalogue, read.id), true, now)
        log.info("author {} bound to {}:{} ({})", id, catalogue, read.id, read.labels.first())
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
