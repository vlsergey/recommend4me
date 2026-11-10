package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.UniversesApi
import io.github.vlsergey.recommend4me.api.model.SimilarUniverse
import io.github.vlsergey.recommend4me.api.model.SiteFandomBinding
import io.github.vlsergey.recommend4me.api.model.UniverseCharacters
import io.github.vlsergey.recommend4me.api.model.UniverseEntryBinding
import io.github.vlsergey.recommend4me.api.model.UniverseInfo
import io.github.vlsergey.recommend4me.api.model.UniverseKeyRef
import io.github.vlsergey.recommend4me.api.model.UniverseName
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.api.model.UniverseRelationRequest
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

internal fun catalogueSilent(e: Exception) =
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

/** No such universe: 404. */
private fun noSuchUniverse(value: String) =
    Refusal.NO_SUCH_UNIVERSE.refuse(HttpStatus.NOT_FOUND, "No such universe in the dictionary: $value")

/** The id of the universe [value]; 404 of a value that is no universe. */
private fun idOf(value: String): Long = FandomRepository.idOf(value) ?: throw noSuchUniverse(value)

/**
 * The universes — the normalized fandoms — their sites' fandoms, the entries of the catalogue they are bound to, the
 * entries' characters and classes, and the user's words on them.
 */
@RestController
class UniversesController(
    private val stores: Stores,
    private val universes: Universes,
    private val editing: FandomEditing,
    private val views: UniverseViews,
) : UniversesApi {

    override fun listUniverses(): ResponseEntity<List<UniverseInfo>> {
        stores.withUniverses()
        return ResponseEntity.ok(views.all())
    }

    override fun getUniverse(universe: String): ResponseEntity<UniverseInfo> {
        stores.withUniverses()
        return now(universe)
    }

    override fun listSimilarUniverses(
        universe: String,
        query: String?,
        limit: Int,
    ): ResponseEntity<List<SimilarUniverse>> {
        stores.withUniverses()
        return ResponseEntity.ok(views.similar(universe, query, limit) ?: throw noSuchUniverse(universe))
    }

    /** The universe [value] as it is now; 404 of no such universe. */
    private fun now(value: String): ResponseEntity<UniverseInfo> =
        ResponseEntity.ok(views.one(value) ?: throw noSuchUniverse(value))

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
        return now(FandomRepository.value(added))
    }

    override fun nameUniverse(universeName: UniverseName): ResponseEntity<UniverseInfo> {
        stores.withUniverses()
        if (!editing.name(idOf(universeName.universe), universeName.name)) throw noSuchUniverse(universeName.universe)
        return now(universeName.universe)
    }

    // A BOUNDARY: turned on, the catalogue is asked — a plugin calling its service, which may fail in any way
    @Suppress("TooGenericExceptionCaught")
    override fun chooseUniverseCharacters(universeCharacters: UniverseCharacters): ResponseEntity<UniverseInfo> {
        stores.withUniverses()
        val id = idOf(universeCharacters.universe)
        try {
            universes.readCharacters(id, universeCharacters.read)
        } catch (e: NoSuchElementException) {
            throw noSuchUniverse(universeCharacters.universe).apply { initCause(e) }
        } catch (e: Exception) {
            throw catalogueSilent(e)
        }
        return now(universeCharacters.universe)
    }

    // A BOUNDARY: bound, the entry is read of the catalogue — a plugin calling its service, which may fail in any way
    @Suppress("TooGenericExceptionCaught")
    override fun bindUniverseEntry(universeEntryBinding: UniverseEntryBinding): ResponseEntity<UniverseInfo> {
        stores.withUniverses()
        val b = universeEntryBinding
        try {
            universes.bindEntry(idOf(b.universe), UniverseKey(b.catalogue, b.entry), b.bound)
        } catch (e: NoSuchElementException) {
            throw Refusal.NOT_IN_CATALOGUE.refuse(HttpStatus.NOT_FOUND, "${e.message}", "universe" to b.entry).apply {
                initCause(e)
            }
        } catch (e: Exception) {
            throw catalogueSilent(e)
        }
        return now(b.universe)
    }

    // A BOUNDARY: the catalogue is asked — a plugin calling its service, which may fail in any way
    @Suppress("TooGenericExceptionCaught")
    override fun refreshUniverse(universeKeyRef: UniverseKeyRef): ResponseEntity<UniverseInfo> {
        stores.withUniverses()
        try {
            universes.readOf(idOf(universeKeyRef.universe))
        } catch (e: NoSuchElementException) {
            throw noSuchUniverse(universeKeyRef.universe).apply { initCause(e) }
        } catch (e: Exception) {
            throw catalogueSilent(e)
        }
        return now(universeKeyRef.universe)
    }

    override fun relateUniverses(universeRelationRequest: UniverseRelationRequest): ResponseEntity<UniverseInfo> {
        stores.withUniverses()
        val r = universeRelationRequest
        if (!editing.relate(idOf(r.universe), idOf(r.other), r.relation)) throw noSuchUniverse(r.other)
        return now(r.universe)
    }

    override fun bindSiteFandom(siteFandomBinding: SiteFandomBinding): ResponseEntity<UniverseInfo> {
        stores.withUniverses()
        val b = siteFandomBinding
        val to =
            editing.bind(SiteFandomRef(b.source, b.key), b.universe?.let(::idOf), b.under == true)
                ?: throw Refusal.NO_FANDOMS.refuse(
                    HttpStatus.NOT_FOUND,
                    "No fandom ${b.key} of ${b.source} bound, or no such universe",
                )
        return now(FandomRepository.value(to))
    }
}
