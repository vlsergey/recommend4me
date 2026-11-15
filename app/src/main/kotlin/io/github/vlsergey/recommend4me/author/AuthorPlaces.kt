package io.github.vlsergey.recommend4me.author

import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(AuthorPlaces::class.java)

/**
 * THE PLACES ON THE WEB AN AUTHOR'S PAGE LISTS AS THEIRS (user, 2026-11-15) — "Места в сети" of fanfics.me: their pages
 * on other sites, in the author's own word. An address every source is asked of ([Source.authorOf]
 * [io.github.vlsergey.recommend4me.source.Source.authorOf]) is an author of another site: the two are one person,
 * merged as the user would merge them, marked as merged by the place of whose page ([MergedBy.Place]) — never two the
 * user told apart or said are partly one, and the user's ✕ taking one out tells them apart. The two are bound at once,
 * a work of either known or not (user, 2026-11-15): the author there is the one the page says.
 */
@Component
class AuthorPlaces(private val stores: Stores, private val binding: AuthorBinding) {

    /** The places the page of [author] of [source] lists now, kept; whatever they make one, merged. */
    fun said(source: String, author: String, urls: List<String>) {
        val authors = stores.source(source)?.authors ?: return
        val owner = SiteAuthorRef(source, author)
        val places = urls.distinct().associateWith { placeOf(owner, it) }
        authors.places.set(owner, places, Instant.now())
        bind(listOf(owner) + places.values.filterNotNull())
        merge(authors)
    }

    /** The authors of the sites [refs] not bound yet bound, each to a normalized author of their own. */
    private fun bind(refs: Collection<SiteAuthorRef>) =
        refs.groupBy({ it.source }, { it.author }).forEach { (source, keys) ->
            stores.source(source)?.let { binding.bind(it, keys) }
        }

    /** The author of another source than [owner]'s the address [url] is the page of; null of none. */
    private fun placeOf(owner: SiteAuthorRef, url: String): SiteAuthorRef? =
        stores.sources
            .filter { it.id != owner.source }
            .firstNotNullOfOrNull { s -> s.source.authorOf(url)?.let { SiteAuthorRef(s.id, it) } }

    /**
     * Every place kept, of every author: what each address is read anew — a source may know more of its addresses than
     * it did — and whatever they make one merged. After the authors are bound ([AuthorBinding.start]).
     */
    @Order(AuthorBinding.BEFORE_READERS + 1)
    @EventListener(ApplicationReadyEvent::class)
    // A step of the start: whatever fails is told in the log, and the authors stay as they were
    @Suppress("TooGenericExceptionCaught")
    fun start() {
        try {
            val authors = stores.types.firstOrNull()?.authors ?: return
            val places = authors.places.all()
            val read = places.map { p ->
                val now = placeOf(p.owner, p.url)
                if (now != p.place) authors.places.resolve(p.owner, p.url, now)
                p.copy(place = now)
            }
            bind(read.flatMap { listOfNotNull(it.owner, it.place) })
            merge(authors)
        } catch (e: Exception) {
            log.error("The places of the authors were not read", e)
        }
    }

    /**
     * Every two authors of the sites a place of one is the other: their normalized authors merged — the place's into
     * its owner's — unless one already, or told apart or said to be partly one. One at a time: two at once would merge
     * one author twice.
     */
    @Synchronized
    private fun merge(authors: AuthorRepository) {
        val places = authors.places.all().filter { it.place != null }
        if (places.isEmpty()) return
        var told = authors.distinct.told()
        places.forEach { p ->
            val owner = authors.of(p.owner)
            val place = authors.of(p.place!!)?.takeIf { it != owner }
            if (owner != null && place != null && AuthorRepository.pair(owner, place) !in told) {
                authors.merge(place, owner, Instant.now(), MergedBy.Place(p.owner))
                log.info("{} merged into {}: a place {} lists", p.place, p.owner, p.url)
                // The pairs told apart of the one merged are of the other now
                told = authors.distinct.told()
            }
        }
    }
}
