package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(EntryReading::class.java)

/** An entry a catalogue found, and the universe it is bound to when the dictionary has it ([bound]). */
class FoundUniverse(val catalogue: String, val entry: UniverseEntry, val bound: String?)

/**
 * THE ENTRIES OF THE CATALOGUE AS THE CATALOGUE GIVES THEM, kept in the dictionary: what it finds by a name ([search]),
 * an entry read and kept — with the characters and whatever else it says is in it, each with its classes, when a
 * universe bound to it reads them ([read]) — and the entries asked in other languages or for other statements than now
 * asked again after the start ([askInNewLanguages]).
 */
@Component
class EntryReading(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
    /** The languages the names of universes and characters are asked in, the user's first. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) {
    private fun universes(): UniverseRepository =
        stores.types.firstOrNull { it.type.universes }?.universes
            ?: throw NoSuchElementException("No type has universes")

    private fun catalogue(id: String): UniverseCatalogue =
        plugins.catalogues.firstOrNull { it.id == id } ?: throw NoSuchElementException("No catalogue $id")

    /** What every catalogue finds by [query]: the user picks among them. */
    fun search(query: String): List<FoundUniverse> {
        val dictionary = universes().dictionary()
        return plugins.catalogues.flatMap { c ->
            c.findUniverses(query, languages).map {
                FoundUniverse(
                    c.id,
                    it,
                    dictionary.ofEntry(UniverseFacet.valueOf(c.id, it.id)).minByOrNull { v -> v.toLong() },
                )
            }
        }
    }

    /**
     * The entry [key] read of its catalogue and kept — with its characters as the catalogue gives them now when
     * [characters], else with none read: those kept before stay.
     */
    fun read(key: UniverseKey, characters: Boolean) {
        val universes = universes()
        val catalogue = catalogue(key.catalogue)
        val properties = wantedProperties(key.catalogue)
        val entry =
            catalogue.universe(key.id, languages, properties)
                ?: throw NoSuchElementException("${key.catalogue} has no ${key.id}")
        if (!characters) {
            universes.saveEntry(key.catalogue, entry, properties, Instant.now())
            log.info("The entry {} ({}) read, its characters not", entry.name, key.value)
            return
        }
        val read = catalogue.characters(key.id, languages, properties)
        val classes = catalogue.classes(read.flatMap { it.classes }.toSet(), languages)
        universes.save(key.catalogue, entry, read, classes, properties, Instant.now())
        val stored = universes.findEntry(key.catalogue, key.id)!!
        log.info(
            "The entry {} ({}) has {} entries of {} classes, {} of them kept as characters",
            entry.name,
            key.value,
            read.size,
            classes.size,
            stored.characters,
        )
    }

    /**
     * The statements of the catalogue's characters every plugin wants
     * ([Source.catalogueProperties][io.github.vlsergey.recommend4me.source.Source.catalogueProperties]).
     */
    private fun wantedProperties(catalogueId: String): Set<String> =
        plugins.sources.flatMap { it.catalogueProperties[catalogueId].orEmpty() }.toSet()

    /**
     * The entries asked of their catalogue in other languages than the user's now — or before the texts were kept by
     * language — or for other statements of their characters than the plugins want now, asked again, one by one, in the
     * background — with their characters when a universe bound to them reads them: their names and descriptions are
     * then there in every language of the user, and their characters' statements the plugins'.
     */
    @Suppress("TooGenericExceptionCaught")
    @EventListener(ApplicationReadyEvent::class)
    fun askInNewLanguages() {
        if (stores.types.none { it.type.universes }) return
        val universes = universes()
        val stale =
            universes.allEntries().filter {
                it.languages != languages || it.properties != wantedProperties(it.catalogue)
            }
        if (stale.isEmpty()) return
        Thread(
                {
                    stale.forEach { u ->
                        try {
                            val key = UniverseKey(u.catalogue, u.id)
                            read(key, universes.fandoms.entries.charactersRead(key))
                            stores.types
                                .filter { it.type.universes }
                                .flatMap { it.sources }
                                .forEach { events.publishEvent(ItemsChanged(it.id, emptyList())) }
                        } catch (e: Exception) {
                            // A BOUNDARY: an entry asked again of its catalogue's plugin and service in the
                            // background may fail in any way; the failure is logged and the next entry asked
                            log.warn("The entry {} was not asked again in {}: {}", u.value, languages, e.message)
                        }
                    }
                },
                "universes-languages",
            )
            .apply { isDaemon = true }
            .start()
    }
}
