package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.api.SuggestionsApi
import io.github.vlsergey.recommend4me.api.model.FacetSuggestions as ApiFacetSuggestions
import io.github.vlsergey.recommend4me.api.model.SuggestedValue
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

fun ItemFacetSuggestions.toApi() = ApiFacetSuggestions(
    facet = facet.key,
    label = facet.label,
    suggested = suggested.map { SuggestedValue(it.key, names[it.key] ?: it.key, it.chance) },
)

@RestController
class SuggestionsController(private val stores: Stores, private val suggestions: FacetSuggestions) : SuggestionsApi {

    override fun getSuggestions(source: String, item: String): ResponseEntity<List<ApiFacetSuggestions>> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.items.find(item) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(suggestions.ofItem(store, item).map { it.toApi() })
    }
}
