package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.FiltersApi
import io.github.vlsergey.recommend4me.api.model.FacetFilter
import io.github.vlsergey.recommend4me.api.model.FilterValues
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/** The filters of the list of a content type ([ListFilters]): its filter facets and the values of each. */
@RestController
class FiltersController(private val filters: ListFilters) : FiltersApi {

    override fun listFacets(type: String): ResponseEntity<List<FacetFilter>> = ResponseEntity.ok(filters.facets(type))

    override fun listFilterValues(type: String, facet: String): ResponseEntity<FilterValues> =
        filters.filterValues(type, facet)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
}
