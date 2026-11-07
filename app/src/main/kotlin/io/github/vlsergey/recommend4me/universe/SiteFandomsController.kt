package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.SiteFandomsApi
import io.github.vlsergey.recommend4me.api.model.FandomBinding
import io.github.vlsergey.recommend4me.api.model.SiteFandom
import io.github.vlsergey.recommend4me.draft.LayerDrafts
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * The sites' fandoms and the universes of the dictionary the user bound them to ([FandomBindings]): a work of a bound
 * fandom is drafted with its universes ([LayerDrafts]).
 */
@RestController
class SiteFandomsController(private val stores: Stores, private val drafts: LayerDrafts) : SiteFandomsApi {

    override fun listSiteFandoms(): ResponseEntity<List<SiteFandom>> {
        val types = stores.withUniverses()
        val dictionary = types.first().universes
        val fandoms =
            types
                .flatMap { it.sources }
                .flatMap { s ->
                    val facet = UniverseFacet.fandomFacet(s.schema) ?: return@flatMap emptyList()
                    val bound = dictionary.fandoms.universesOf(s.id)
                    val names = s.items.facetNames(facet)
                    s.items.valueUses(facet).map { (key, works) ->
                        SiteFandom(s.id, key, names[key] ?: key, works, bound[key].orEmpty().sorted())
                    }
                }
        return ResponseEntity.ok(fandoms.sortedWith(compareByDescending<SiteFandom> { it.works }.thenBy { it.name }))
    }

    override fun bindSiteFandom(fandomBinding: FandomBinding): ResponseEntity<Unit> {
        val b = fandomBinding
        val types = stores.withUniverses()
        val store =
            types
                .flatMap { it.sources }
                .firstOrNull { it.id == b.source }
                ?.takeIf { UniverseFacet.fandomFacet(it.schema) != null }
                ?: throw Refusal.NO_FANDOMS.refuse(HttpStatus.NOT_FOUND, "No source ${b.source} with fandoms")
        val universe = UniverseFacet.parse(b.universe)?.let { (catalogue, id) -> UniverseKey(catalogue, id) }
        if (
            universe == null ||
                !types.first().universes.fandoms.bind(store.id, b.fandom, universe, b.bound, Instant.now())
        ) {
            throw Refusal.NO_SUCH_UNIVERSE.refuse(
                HttpStatus.NOT_FOUND,
                "No such universe in the dictionary: ${b.universe}",
            )
        }
        // The works of the fandom drafted with the universe, or no longer, in the background
        drafts.fandomsBound(store.id)
        return ResponseEntity.noContent().build()
    }
}
