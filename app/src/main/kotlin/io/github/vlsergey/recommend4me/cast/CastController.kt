package io.github.vlsergey.recommend4me.cast

import io.github.vlsergey.recommend4me.api.CastsApi
import io.github.vlsergey.recommend4me.api.model.MethodChoice
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * THE CHOICE OF THE WAY A FACET READS THE SITE'S LINE OF CHARACTERS AND PAIRINGS, on the page of the models: the way
 * kept for the facet of the source ([CastReading.setting]), and the facet's model fitted from scratch with it in the
 * background ([FacetSuggestions.searchAnew]).
 */
@RestController
class CastController(
    private val stores: Stores,
    private val plugins: Plugins,
    private val settings: Settings,
    private val reading: CastReading,
    private val suggestions: FacetSuggestions,
) : CastsApi {
    override fun chooseFacetCast(
        type: String,
        source: String,
        facet: String,
        methodChoice: MethodChoice,
    ): ResponseEntity<Unit> {
        val store = stores.type(type)?.sources?.firstOrNull { it.id == source }
        // A facet of the source that reads the line, a way installed
        val reads =
            store
                ?.let { s -> suggestions.facetsOf(s).firstOrNull { it.key == facet } }
                ?.let { reading.reads(store, it) }
        val known = plugins.castReaders.any { it.id == methodChoice.method }
        if (store == null || reads != true || !known) return ResponseEntity.notFound().build()
        settings.set(CastReading.setting(store.id, facet), methodChoice.method)
        return if (suggestions.searchAnew(store, facet)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
    }
}
