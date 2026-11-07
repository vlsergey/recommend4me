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
import io.github.vlsergey.recommend4me.tag.BoundEntry
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
     * or bound to an entry; of [search], those a name of theirs on a site or of their entry holds it. Their works are
     * not listed. Null of no such type.
     */
    fun list(typeId: String, kind: AuthorKind, search: String?, offset: Int, limit: Int): AuthorPage? {
        val type = stores.type(typeId) ?: return null
        val library = AuthorLibrary.of(type)
        val bound = type.authors.entries.all()
        val ofKind: Set<Long> =
            when (kind) {
                AuthorKind.ALL -> library.worksOf.keys
                AuthorKind.MERGED -> library.worksOf.keys intersect type.authors.merged().keys
                AuthorKind.CATALOGUED -> library.worksOf.keys intersect bound.keys
            }
        val needle = search?.trim()?.lowercase().orEmpty()
        val found = if (needle.isEmpty()) ofKind else ofKind intersect named(type, needle, bound)
        val page =
            found
                .sortedWith(compareByDescending<Long> { library.worksOf[it]?.size ?: 0 }.thenBy { it })
                .drop(offset.coerceAtLeast(0))
                .take(limit.coerceIn(1, MAX_PAGE))
        val sites = sides.sites(type, page)
        val entries = sides.entryInfos(type, bound.filterKeys { it in page })
        return AuthorPage(
            total = found.size,
            authors = page.map { sides.side(it, sites, entries, library, emptyList()) },
        )
    }

    /** The authors a name of theirs holds [needle], lowercase: of the sites and of the entries they are bound to. */
    private fun named(type: TypeStore, needle: String, bound: Map<Long, List<TagEntryRef>>): Set<Long> {
        val out = HashSet<Long>()
        type.sources.forEach { s ->
            val facet = authorFacetOf(s) ?: return@forEach
            val ofKey = s.authors.bindingsOf(s.id)
            val names = s.names(facet, ofKey.keys)
            ofKey.forEach { (key, id) -> if ((names[key] ?: key).lowercase().contains(needle)) out += id }
        }
        val kept = type.tags.keptEntries(bound.values.flatten().toSet())
        bound.forEach { (id, refs) ->
            if (
                refs.any { ref ->
                    kept[ref]?.let { e ->
                        BoundEntry(ref.catalogue, e).names.any { it.lowercase().contains(needle) }
                    } == true
                }
            )
                out += id
        }
        return out
    }

    /** The author [id] with every work of theirs; null of no such type or author. */
    fun one(typeId: String, id: Long): AuthorSide? {
        val type = stores.type(typeId)?.takeIf { it.authors.exists(id) } ?: return null
        val library = AuthorLibrary.of(type)
        val own = library.worksOf[id].orEmpty()
        val shown = ShownWorks(type, library, own)
        val entries = sides.entryInfos(type, type.authors.entries.all().filterKeys { it == id })
        return sides.side(id, sides.sites(type, listOf(id)), entries, library, own.map { shown.of(it, id) })
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
                AuthorAnswer.Verdict.DIFFERENT -> authors.tell(a, b, false, now)
                AuthorAnswer.Verdict.PARTIAL -> authors.tell(a, b, true, now)
                AuthorAnswer.Verdict.NONE -> authors.takeBack(a, b)
            }
        }
        return true
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
            val ofEntry = HashMap<TagEntryRef, MutableList<Long>>()
            type.authors.entries.all().forEach { (id, refs) ->
                refs.forEach { ofEntry.getOrPut(it) { ArrayList() } += id }
            }
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
            type.tags.keepEntry(catalogue, read, now)
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
