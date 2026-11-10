package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.UniverseEntriesApi
import io.github.vlsergey.recommend4me.api.model.CharacterInfo
import io.github.vlsergey.recommend4me.api.model.FoundUniverse as ApiFoundUniverse
import io.github.vlsergey.recommend4me.api.model.Inclusion
import io.github.vlsergey.recommend4me.api.model.LocalName
import io.github.vlsergey.recommend4me.api.model.UniverseClassInfo
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** The user's word on a class or an entry of an entry ([choose]) answered; 404 when there is no such entry. */
private fun chosen(choose: () -> Unit): ResponseEntity<Unit> {
    try {
        choose()
    } catch (e: NoSuchElementException) {
        throw Refusal.NO_SUCH_UNIVERSE.refuse(HttpStatus.NOT_FOUND, "No such entry in the dictionary: ${e.message}")
            .apply { initCause(e) }
    }
    return ResponseEntity.noContent().build()
}

/**
 * THE ENTRIES OF THE CATALOGUE THE UNIVERSES ARE BOUND TO: what the catalogue finds by a name, the entries' characters
 * and classes, the names the user gives the entries and the characters, and the user's words on what is kept.
 */
@RestController
class UniverseEntriesController(
    private val stores: Stores,
    private val universes: Universes,
    private val reading: EntryReading,
) : UniverseEntriesApi {

    // A BOUNDARY: the catalogues are plugins calling their services, which may fail in any way; whatever they throw
    // is answered as the catalogue silent (502)
    @Suppress("TooGenericExceptionCaught")
    override fun searchUniverses(query: String): ResponseEntity<List<ApiFoundUniverse>> {
        stores.withUniverses()
        val found =
            try {
                reading.search(query.trim())
            } catch (e: Exception) {
                throw catalogueSilent(e)
            }
        return ResponseEntity.ok(
            found.map {
                ApiFoundUniverse(
                    catalogue = it.catalogue,
                    universe = it.entry.id,
                    name = it.entry.name,
                    url = it.entry.url,
                    added = it.bound != null,
                    description = it.entry.description,
                    bound = it.bound,
                )
            }
        )
    }

    override fun listCharacters(catalogue: String, universe: String): ResponseEntity<List<CharacterInfo>> =
        ResponseEntity.ok(
            stores.withUniverses().first().universes.entries.of(catalogue, universe).map { e ->
                val c = e.character
                CharacterInfo(
                    character = c.id,
                    value = UniverseFacet.valueOf(catalogue, c.id),
                    names = c.names,
                    url = c.url,
                    classes = c.classes,
                    included = e.included,
                    description = c.description,
                    choice = e.choice,
                    localName = e.localName,
                )
            }
        )

    override fun nameUniverseEntry(catalogue: String, entry: String, localName: LocalName): ResponseEntity<Unit> {
        stores.withUniverses()
        universes.setLocalName(catalogue, entry, localName.name)
        return ResponseEntity.noContent().build()
    }

    override fun listUniverseClasses(catalogue: String, universe: String): ResponseEntity<List<UniverseClassInfo>> =
        ResponseEntity.ok(
            stores.withUniverses().first().universes.entries.classes(catalogue, universe).map {
                UniverseClassInfo(
                    propertyClass = it.id,
                    name = it.name,
                    character = it.character,
                    count = it.entries,
                    included = it.included,
                    choice = it.choice,
                )
            }
        )

    override fun chooseUniverseClass(
        catalogue: String,
        universe: String,
        propertyClass: String,
        inclusion: Inclusion,
    ): ResponseEntity<Unit> = chosen { universes.chooseClass(catalogue, universe, propertyClass, inclusion.included) }

    override fun resetUniverseClass(catalogue: String, universe: String, propertyClass: String): ResponseEntity<Unit> =
        chosen {
            universes.chooseClass(catalogue, universe, propertyClass, null)
        }

    override fun chooseUniverseEntry(
        catalogue: String,
        universe: String,
        character: String,
        inclusion: Inclusion,
    ): ResponseEntity<Unit> = chosen { universes.chooseEntry(catalogue, universe, character, inclusion.included) }

    override fun resetUniverseEntry(catalogue: String, universe: String, character: String): ResponseEntity<Unit> =
        chosen {
            universes.chooseEntry(catalogue, universe, character, null)
        }
}
