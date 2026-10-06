package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.UniversesApi
import io.github.vlsergey.recommend4me.api.model.CharacterInfo
import io.github.vlsergey.recommend4me.api.model.UniverseInfo
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.ZoneOffset
import io.github.vlsergey.recommend4me.api.model.FoundUniverse as ApiFoundUniverse

@RestController
class UniversesController(private val stores: Stores, private val universes: Universes) : UniversesApi {

    private fun typeOf(type: String) = stores.type(type)?.takeIf { it.type.universes }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "У типа «$type» нет вселенных")

    override fun listUniverses(type: String): ResponseEntity<List<UniverseInfo>> {
        val t = typeOf(type)
        // How many works the user linked to each universe, over every source of the type
        val works = HashMap<String, Int>()
        t.sources.forEach { s -> s.corrections.allFacets().filter { it.facet == UniverseFacet.KEY && it.added }.forEach { works.merge(it.key, 1, Int::plus) } }
        return ResponseEntity.ok(t.universes.all().map { it.toApi(works[it.value] ?: 0) })
    }

    override fun addUniverse(type: String, universeRef: UniverseRef): ResponseEntity<UniverseInfo> {
        typeOf(type)
        val added = try {
            universes.add(type, universeRef.catalogue, universeRef.universe)
        } catch (e: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "В справочнике нет «${universeRef.universe}»")
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Справочник не ответил: ${e.message}")
        }
        return ResponseEntity.ok(added.toApi(0))
    }

    override fun searchUniverses(type: String, query: String): ResponseEntity<List<ApiFoundUniverse>> {
        typeOf(type)
        val found = try {
            universes.search(type, query.trim())
        } catch (e: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Справочник не ответил: ${e.message}")
        }
        return ResponseEntity.ok(found.map { ApiFoundUniverse(it.catalogue, it.entry.id, it.entry.name, it.entry.url, it.added, it.entry.description) })
    }

    override fun removeUniverse(type: String, catalogue: String, universe: String): ResponseEntity<Unit> {
        typeOf(type)
        universes.remove(type, catalogue, universe)
        return ResponseEntity.noContent().build()
    }

    override fun listCharacters(type: String, catalogue: String, universe: String): ResponseEntity<List<CharacterInfo>> =
        ResponseEntity.ok(typeOf(type).universes.characters(catalogue, universe).map { CharacterInfo(it.id, it.names, it.url, it.description) })

    private fun StoredUniverse.toApi(works: Int) = UniverseInfo(
        catalogue = catalogue,
        universe = id,
        value = value,
        name = name,
        url = url,
        characters = characters,
        works = works,
        addedAt = addedAt.atOffset(ZoneOffset.UTC),
        refreshedAt = refreshedAt.atOffset(ZoneOffset.UTC),
        description = description,
    )
}
