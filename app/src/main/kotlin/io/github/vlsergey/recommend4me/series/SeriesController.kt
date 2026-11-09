package io.github.vlsergey.recommend4me.series

import io.github.vlsergey.recommend4me.api.SeriesApi
import io.github.vlsergey.recommend4me.api.model.SeriesDetails
import io.github.vlsergey.recommend4me.api.model.SeriesPage
import io.github.vlsergey.recommend4me.item.ItemKey
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class SeriesController(private val views: SeriesViews) : SeriesApi {

    override fun listSeries(
        type: String,
        search: String?,
        with: List<String>?,
        without: List<String>?,
        said: Boolean?,
        offset: Int,
        limit: Int,
    ): ResponseEntity<SeriesPage> =
        views.page(type, SeriesFilter(search, with.orEmpty(), without.orEmpty(), said), offset, limit)?.let {
            ResponseEntity.ok(it)
        } ?: ResponseEntity.notFound().build()

    override fun getSeries(type: String, key: String): ResponseEntity<SeriesDetails> =
        views.one(type, key)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun takeOutOfSeries(type: String, source: String, item: String): ResponseEntity<Unit> =
        if (views.takeOut(type, ItemKey(source, item))) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
}
