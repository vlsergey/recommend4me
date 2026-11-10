package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(Universes::class.java)

/**
 * THE UNIVERSES AND THE ENTRIES OF THE CATALOGUE THEY ARE BOUND TO, every content type's: an entry made a universe or
 * bound to one, a universe's characters read or not, the user's words on the entries' names, classes and characters.
 * The catalogue is asked only when the user asks — an entry bound, a universe's characters turned on or read again —
 * and what it answered is kept ([EntryReading]).
 *
 * The names of the universes and of their characters are the names of the values of the facets "universe" and
 * "characters" in every source of every type that has universes, so the works name them as any value of a facet.
 */
@Service
class Universes(
    private val stores: Stores,
    private val reading: EntryReading,
    private val events: ApplicationEventPublisher,
) {
    /** The one dictionary, and every source of every type that has universes: what reads it. */
    private class Scope(val universes: UniverseRepository, val sources: List<SourceStore>) {
        val fandoms: FandomRepository
            get() = universes.fandoms
    }

    private fun scope(): Scope {
        val types = stores.types.filter { it.type.universes }
        val first = types.firstOrNull() ?: throw NoSuchElementException("No type has universes")
        return Scope(first.universes, types.flatMap { it.sources })
    }

    /**
     * The entry of the catalogue as a universe: the universe bound to it — the first, of several — or a new one of it
     * whose characters are read; the entry read again of the catalogue. Its id.
     */
    fun add(catalogueId: String, id: String): Long {
        val scope = scope()
        val key = UniverseKey(catalogueId, id)
        val bound = scope.fandoms.entries.fandomsOf(key).minOrNull()
        if (bound != null) {
            readOf(bound)
            return bound
        }
        reading.read(key, true)
        val now = Instant.now()
        val made =
            scope.fandoms.transaction { f -> f.create(now, characters = true).also { f.entries.bind(it, key, now) } }
        log.info("The entry {} made the universe {}, its characters read", key.value, made)
        dictionaryChanged(scope)
        return made
    }

    /**
     * The universe [fandom] bound to the entry [key] — read of the catalogue now, its characters too when a universe
     * bound to it reads them — or the binding taken back: an entry bound to no universe then is gone with its
     * characters.
     */
    fun bindEntry(fandom: Long, key: UniverseKey, bound: Boolean) {
        val scope = scope()
        val row = scope.fandoms.one(fandom) ?: throw NoSuchElementException("No universe $fandom")
        if (bound) {
            reading.read(key, row.characters || scope.fandoms.entries.charactersRead(key))
            scope.fandoms.entries.bind(fandom, key, Instant.now())
        } else scope.fandoms.entries.unbind(fandom, key)
        log.info("The universe {} {} the entry {}", fandom, if (bound) "bound to" else "no more bound to", key.value)
        dictionaryChanged(scope)
    }

    /**
     * Whether the characters of the entries of the universe [fandom] are read and given to its works; turned on, its
     * entries and their characters read of the catalogue now. Turned off, the characters kept stay, given to none.
     */
    fun readCharacters(fandom: Long, read: Boolean) {
        val scope = scope()
        scope.fandoms.one(fandom) ?: throw NoSuchElementException("No universe $fandom")
        scope.fandoms.characters(fandom, read)
        log.info("The characters of the universe {} {}", fandom, if (read) "read" else "no more read")
        if (read) readOf(fandom) else dictionaryChanged(scope)
    }

    /** The entries of the universe [fandom] read again of the catalogue, their characters too when it reads them. */
    fun readOf(fandom: Long) {
        val scope = scope()
        scope.fandoms.one(fandom) ?: throw NoSuchElementException("No universe $fandom")
        scope.fandoms.entries.of(fandom).forEach { key ->
            reading.read(key, scope.fandoms.entries.charactersRead(key))
        }
        dictionaryChanged(scope)
    }

    /**
     * The name the user gives an entry or a character of [catalogue] ([id]) over the catalogue's — shown everywhere in
     * its place, read in the texts as one more name of it; null or blank takes it back. A character is named in every
     * entry it is in.
     */
    fun setLocalName(catalogueId: String, id: String, name: String?) {
        val scope = scope()
        scope.universes.names.setLocalName(catalogueId, id, name, Instant.now())
        log.info(
            "{} named {}",
            UniverseFacet.valueOf(catalogueId, id),
            name?.trim()?.takeIf { it.isNotEmpty() } ?: "as the catalogue names it",
        )
        dictionaryChanged(scope)
    }

    /** The user's word on a class of the entry's entries — kept, left out, or null: as the catalogue says. */
    fun chooseClass(catalogueId: String, id: String, cls: String, included: Boolean?) {
        val scope = scope()
        scope.universes.findEntry(catalogueId, id) ?: throw NoSuchElementException("No entry $catalogueId:$id")
        scope.universes.entries.chooseClass(catalogueId, id, cls, included)
        dictionaryChanged(scope)
    }

    /** The user's word on one entry of the entry — kept, left out, or null: as its classes say. */
    fun chooseEntry(catalogueId: String, id: String, character: String, included: Boolean?) {
        val scope = scope()
        scope.universes.findEntry(catalogueId, id) ?: throw NoSuchElementException("No entry $catalogueId:$id")
        scope.universes.entries.chooseEntry(catalogueId, id, character, included)
        dictionaryChanged(scope)
    }

    /** The dictionary is what the model chooses from: every source that reads it is worked out again. */
    private fun dictionaryChanged(scope: Scope) {
        scope.sources.forEach { events.publishEvent(ItemsChanged(it.id, emptyList())) }
    }
}
