package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.UniversesApi
import io.github.vlsergey.recommend4me.api.model.CharacterInfo
import io.github.vlsergey.recommend4me.api.model.FoundUniverse as ApiFoundUniverse
import io.github.vlsergey.recommend4me.api.model.Inclusion
import io.github.vlsergey.recommend4me.api.model.LocalName
import io.github.vlsergey.recommend4me.api.model.UniverseClassInfo
import io.github.vlsergey.recommend4me.api.model.UniverseInfo
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import java.time.ZoneOffset
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

private fun catalogueSilent(e: Exception) =
    Refusal.CATALOGUE_SILENT.refuse(
            HttpStatus.BAD_GATEWAY,
            "The catalogue did not answer: ${e.message}",
            "error" to (e.message ?: e.javaClass.simpleName),
        )
        .apply { initCause(e) }

/** The types that have universes — one dictionary of them all; 404 when none has. */
internal fun Stores.withUniverses(): List<TypeStore> =
    types.filter { it.type.universes }.takeIf { it.isNotEmpty() }
        ?: throw Refusal.NO_UNIVERSES.refuse(HttpStatus.NOT_FOUND, "No type has universes")

/** The user's word on a class or an entry of a universe ([choose]) answered; 404 when there is no such universe. */
private fun chosen(choose: () -> Unit): ResponseEntity<Unit> {
    try {
        choose()
    } catch (e: NoSuchElementException) {
        throw Refusal.NO_SUCH_UNIVERSE.refuse(HttpStatus.NOT_FOUND, "No such universe in the dictionary: ${e.message}")
            .apply { initCause(e) }
    }
    return ResponseEntity.noContent().build()
}

private fun StoredUniverse.toApi(works: Int) =
    UniverseInfo(
        catalogue = catalogue,
        universe = id,
        value = value,
        name = name,
        catalogueName = catalogueName,
        localName = localName,
        url = url,
        characters = characters,
        total = entries,
        works = works,
        addedAt = addedAt.atOffset(ZoneOffset.UTC),
        refreshedAt = refreshedAt.atOffset(ZoneOffset.UTC),
        description = description,
    )

/** The dictionary of universes: its universes, their entries and classes, and the user's words on them. */
@RestController
class UniversesController(private val stores: Stores, private val universes: Universes) : UniversesApi {

    override fun listUniverses(): ResponseEntity<List<UniverseInfo>> {
        val types = stores.withUniverses()
        // How many works the user linked to each universe, over every source of every type
        val works = HashMap<String, Int>()
        types
            .flatMap { it.sources }
            .forEach { s ->
                s.corrections
                    .allFacets()
                    .filter { it.facet == UniverseFacet.KEY && it.added }
                    .forEach { works.merge(it.key, 1, Int::plus) }
            }
        return ResponseEntity.ok(types.first().universes.all().map { it.toApi(works[it.value] ?: 0) })
    }

    // A BOUNDARY: the catalogue is a plugin calling its service, which may fail in any way; whatever it throws is
    // answered as the catalogue silent (502)
    @Suppress("TooGenericExceptionCaught")
    override fun addUniverse(universeRef: UniverseRef): ResponseEntity<UniverseInfo> {
        stores.withUniverses()
        val added =
            try {
                universes.add(universeRef.catalogue, universeRef.universe)
            } catch (e: NoSuchElementException) {
                throw Refusal.NOT_IN_CATALOGUE.refuse(
                        HttpStatus.NOT_FOUND,
                        "The catalogue ${universeRef.catalogue} has no '${universeRef.universe}'",
                        "universe" to universeRef.universe,
                    )
                    .apply { initCause(e) }
            } catch (e: Exception) {
                throw catalogueSilent(e)
            }
        return ResponseEntity.ok(added.toApi(0))
    }

    // A BOUNDARY: the catalogues are plugins calling their services, which may fail in any way; whatever they throw
    // is answered as the catalogue silent (502)
    @Suppress("TooGenericExceptionCaught")
    override fun searchUniverses(query: String): ResponseEntity<List<ApiFoundUniverse>> {
        stores.withUniverses()
        val found =
            try {
                universes.search(query.trim())
            } catch (e: Exception) {
                throw catalogueSilent(e)
            }
        return ResponseEntity.ok(
            found.map {
                ApiFoundUniverse(it.catalogue, it.entry.id, it.entry.name, it.entry.url, it.added, it.entry.description)
            }
        )
    }

    override fun removeUniverse(catalogue: String, universe: String): ResponseEntity<Unit> {
        stores.withUniverses()
        universes.remove(catalogue, universe)
        return ResponseEntity.noContent().build()
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
