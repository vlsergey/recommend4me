package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.api.model.AuthorAnswer
import io.github.vlsergey.recommend4me.api.model.AuthorKind
import io.github.vlsergey.recommend4me.api.model.AuthorPage
import io.github.vlsergey.recommend4me.api.model.AuthorRef
import io.github.vlsergey.recommend4me.api.model.AuthorSide
import io.github.vlsergey.recommend4me.api.model.FoundAuthorEntry
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.TagEntryRef
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(Authors::class.java)

/**
 * THE NORMALIZED AUTHORS: every author of every site bound to one ([AuthorRepository], [AuthorBinding]), at first of
 * their own — never by the likeness of a name, a name on two sites may be two people and one person two names. The user
 * merges them on the evidence of their works ([AuthorOffers]). Two authors may be partly one: a part of the works of
 * one is the other's — an account putting up the works of others, two writing together — neither merged nor offered
 * again. An author may be bound to the entries of the catalogues of tags (Wikidata).
 */
@Component
class Authors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val sides: AuthorSides,
    /**
     * The languages an entry is asked in, the user's first: of its texts, those of the first that has them are shown.
     */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) {

    /**
     * A page of the authors of the type with works, the most works first: every one, of several authors of the sites,
     * or bound to an entry; of [search], those a name of theirs on a site or of their entry holds it — as the last
     * search of the authors' duplicates read them, chosen in the database ([AuthorListingRepository]), the page's alone
     * read whole. Their works are not listed. Null of no such type.
     */
    fun list(typeId: String, kind: AuthorKind, search: String?, offset: Int, limit: Int): AuthorPage? {
        val type = stores.type(typeId) ?: return null
        val needle = search?.trim()?.lowercase()
        val (total, rows) = type.authorListing.page(kind, needle, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))
        val page = rows.map { it.author }
        val sites = sides.sites(type, page)
        val entries = sides.entryInfos(type, type.authors.entries.of(page))
        return AuthorPage(
            total = total,
            authors = rows.map { sides.side(it.author, sites, entries, it.works, emptyList()) },
        )
    }

    /** The author [id] with every work of theirs; null of no such type or author. */
    fun one(typeId: String, id: Long): AuthorSide? {
        val type = stores.type(typeId)?.takeIf { it.authors.exists(id) } ?: return null
        val library = AuthorLibrary.ofAuthors(type, listOf(id))
        val own = library.worksOf[id].orEmpty()
        val shown = ShownWorks(type, library, own)
        val entries = sides.entryInfos(type, type.authors.entries.of(listOf(id)))
        return sides.side(id, sides.sites(type, listOf(id)), entries, own.size, own.map { shown.of(it, id) })
    }

    /**
     * The user's word on the two authors of the sites, of the normalized authors they are bound to now: so a row read
     * before a merge stays good. One writer: merged, the smaller id kept; different, or partly one; or the word said of
     * them taken back. Of two who are one by now, one writer and a word taken back need nothing, and different or
     * partly one is refused: only an author of a site taken out parts them. False when either is no author of a site.
     */
    fun answer(first: SiteAuthorRef, second: SiteAuthorRef, verdict: AuthorAnswer.Verdict): Boolean {
        val authors = stores.types.firstOrNull()?.authors
        val a = authors?.of(first)
        val b = authors?.of(second)
        if (authors == null || a == null || b == null) return false
        if (a == b) {
            if (verdict == AuthorAnswer.Verdict.DIFFERENT || verdict == AuthorAnswer.Verdict.PARTIAL) {
                throw Refusal.AUTHORS_ONE.refuse(
                    HttpStatus.CONFLICT,
                    "$first and $second are one normalized author, $a",
                )
            }
        } else {
            val now = Instant.now()
            when (verdict) {
                AuthorAnswer.Verdict.SAME -> authors.merge(maxOf(a, b), minOf(a, b), now)
                AuthorAnswer.Verdict.DIFFERENT -> authors.distinct.tell(a, b, false, now)
                AuthorAnswer.Verdict.PARTIAL -> authors.distinct.tell(a, b, true, now)
                AuthorAnswer.Verdict.NONE -> authors.distinct.takeBack(a, b)
            }
            struck(a, b, verdict)
        }
        return true
    }

    /**
     * The pair of the authors [a] and [b] of the [verdict] struck out of the open offers of every type at once — or put
     * back —, till the next search of the authors' duplicates; of one writer, the author merged away gone.
     */
    private fun struck(a: Long, b: Long, verdict: AuthorAnswer.Verdict) =
        stores.types.forEach { t ->
            when (verdict) {
                AuthorAnswer.Verdict.SAME -> {
                    t.authorCandidates.gone(maxOf(a, b))
                    t.authorListing.merged(maxOf(a, b), minOf(a, b))
                }
                AuthorAnswer.Verdict.DIFFERENT -> t.authorCandidates.said(a, b, false)
                AuthorAnswer.Verdict.PARTIAL -> t.authorCandidates.said(a, b, true)
                AuthorAnswer.Verdict.NONE -> t.authorCandidates.said(a, b, null)
            }
        }

    /**
     * The entries every catalogue of tags finds by [text] — an id or a page's address is the entry itself — each with
     * the authors of the type bound to it already.
     */
    fun findEntries(typeId: String, text: String): List<FoundAuthorEntry>? {
        val type = stores.type(typeId) ?: return null
        return if (text.isBlank()) emptyList()
        else {
            val found = plugins.tagCatalogues.flatMap { c -> c.find(text, languages).map { c.id to it } }
            val ofEntry = type.authors.entries.boundTo(found.map { (c, e) -> TagEntryRef(c, e.id) })
            val sites = sides.sites(type, found.flatMap { (c, e) -> ofEntry[TagEntryRef(c, e.id)].orEmpty() })
            found.map { (c, e) ->
                val authors =
                    ofEntry[TagEntryRef(c, e.id)].orEmpty().map { id ->
                        AuthorRef(id, sites[id].orEmpty().maxByOrNull { it.works }?.name ?: id.toString())
                    }
                FoundAuthorEntry(c, e.id, e.url, authors, sides.firstOf(e.labels), sides.firstOf(e.descriptions))
            }
        }
    }

    /**
     * The author [id] bound to the entry [entry] of [catalogue] besides the ones they are, the entry read of the
     * catalogue now and kept — or, [bound] false, no more to it. False when the author, the catalogue or the entry is
     * no more.
     */
    fun bindEntry(id: Long, catalogue: String, entry: String, bound: Boolean): Boolean {
        val type = stores.types.firstOrNull()?.takeIf { it.authors.exists(id) } ?: return false
        val now = Instant.now()
        if (!bound) type.authors.entries.bind(id, TagEntryRef(catalogue, entry), false, now)
        return !bound || bindRead(type, id, catalogue, entry, now)
    }

    /**
     * The author [id] bound to the entry [entry] of [catalogue] as the catalogue reads it now, the entry kept. False
     * when the catalogue or the entry is no more.
     */
    private fun bindRead(type: TypeStore, id: Long, catalogue: String, entry: String, now: Instant): Boolean {
        val read =
            plugins.tagCatalogues.firstOrNull { it.id == catalogue }?.entries(listOf(entry), languages)?.firstOrNull()
        if (read != null) {
            type.tags.entries.keepEntry(catalogue, read, now)
            type.authors.entries.bind(id, TagEntryRef(catalogue, read.id), true, now)
            log.info("author {} bound to {}:{} ({})", id, catalogue, read.id, sides.firstOf(read.labels))
        }
        return read != null
    }

    /**
     * The author of a site taken out of the normalized author [id], to one of their own. False when it is not of it.
     */
    fun takeOut(id: Long, source: String, author: String): Boolean {
        val type = stores.types.firstOrNull() ?: return false
        val site = SiteAuthorRef(source, author)
        val ofIt = site in type.authors.sitesOf(listOf(id))[id].orEmpty()
        if (ofIt) type.authors.takeOut(site, id, Instant.now())
        return ofIt
    }

    private companion object {
        /** The authors a page holds at most. */
        const val MAX_PAGE = 200
    }
}
