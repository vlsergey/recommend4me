package io.github.vlsergey.recommend4me.page

import io.github.vlsergey.recommend4me.api.PagesApi
import io.github.vlsergey.recommend4me.api.model.CardDecorInfo
import io.github.vlsergey.recommend4me.api.model.CardView
import io.github.vlsergey.recommend4me.api.model.CardsRequest
import io.github.vlsergey.recommend4me.api.model.FacetDecorInfo
import io.github.vlsergey.recommend4me.api.model.PageDecorInfo
import io.github.vlsergey.recommend4me.api.model.PageItem
import io.github.vlsergey.recommend4me.api.model.PagePicture
import io.github.vlsergey.recommend4me.api.model.PageView
import io.github.vlsergey.recommend4me.api.model.ReviewDecorInfo
import io.github.vlsergey.recommend4me.item.ItemCards
import io.github.vlsergey.recommend4me.item.ItemDetailsReader
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.toApi
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.PageDecor
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.toApi
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.net.URI

/**
 * THE SITE AS THE APPLICATION'S INTERFACE: what the browser extension shows on a page the user
 * opens — on a work's page the work as the application knows it, on a list the prediction of every
 * card. The extension changes things through the same calls as the application's own interface.
 */
@RestController
class PagesController(
    private val stores: Stores,
    private val plugins: Plugins,
    private val cards: ItemCards,
    private val details: ItemDetailsReader,
    private val recommendations: Recommendations,
    private val suggestions: FacetSuggestions,
) : PagesApi {

    /** The source whose site the address is on: one that wants the page, or one of the same host. */
    private fun sourceOf(url: String): SourceStore? {
        stores.sources.firstOrNull { s -> s.source.capturePatterns.any { it.matches(url) } }?.let { return it }
        val host = hostOf(url) ?: return null
        return stores.sources.firstOrNull { hostOf(it.source.homepage) == host }
    }

    override fun getPage(url: String): ResponseEntity<PageView> {
        val store = sourceOf(url) ?: return ResponseEntity.notFound().build()
        val decor = store.source.pageDecor
        val itemId = runCatching { store.source.itemIdOf(url) }.getOrNull()
        return ResponseEntity.ok(
            PageView(
                source = store.id,
                sourceTitle = store.source.title,
                decor = decor?.toApi() ?: PageDecorInfo(emptyList()),
                itemId = itemId,
                item = itemId?.takeIf { store.items.find(it) != null }?.let { item(store, it, decor) },
            ),
        )
    }

    private fun item(store: SourceStore, itemId: String, decor: PageDecor?): PageItem? {
        val key = ItemKey(store.id, itemId)
        val type = stores.typeOf(store.id)
        val summary = cards.cards(type, listOf(key)).firstOrNull() ?: return null
        val wanted = decor?.facets.orEmpty().map { it.facet }.toSet() + store.source.schema.facets.filter { it.suggest }.map { it.key }
        val site = store.items.facets(itemId)
        val corrections = store.corrections.facetsOf(itemId)
        val names = wanted.associateWith { facet ->
            store.items.facetNames(facet, site[facet].orEmpty() + corrections.filter { it.facet == facet }.map { it.key })
        }
        val pictureMarks = store.marks.pictureMarksOf(itemId)
        return PageItem(
            summary = summary,
            facets = cards.facets(store, site, corrections, names) { it in wanted },
            suggestions = suggestions.ofItem(store, itemId).map { it.toApi() },
            explanation = recommendations.explanation(key).take(EXPLAINED).map { it.toApi() },
            grades = type.type.grades,
            pictures = store.pictures.ofItem(itemId, plugins.imageEncoder()?.id).map { p ->
                PagePicture(p.position, p.url, pictureMarks.firstOrNull { it.position == p.position && it.url == p.url }?.mark)
            },
            reviewMarks = store.marks.reviewMarksOf(itemId),
            ratings = details.ratings(type, key),
        )
    }

    override fun getCards(cardsRequest: CardsRequest): ResponseEntity<List<CardView>> {
        val store = sourceOf(cardsRequest.url) ?: return ResponseEntity.ok(emptyList())
        val base = runCatching { URI(cardsRequest.url) }.getOrNull()
        val ids = cardsRequest.links.distinct().mapNotNull { link ->
            val absolute = runCatching { base?.resolve(link)?.toString() ?: link }.getOrDefault(link)
            runCatching { store.source.itemIdOf(absolute) }.getOrNull()?.let { link to it }
        }
        val known = store.items.heads(ids.map { it.second })
        val keys = ids.map { it.second }.filter { it in known }.distinct().map { ItemKey(store.id, it) }
        val summaries = cards.cards(stores.typeOf(store.id), keys).associateBy { it.item }
        return ResponseEntity.ok(ids.mapNotNull { (link, id) ->
            summaries[id]?.let { CardView(link, store.id, id, it.title, it.grade, it.prediction?.score) }
        })
    }

    companion object {
        /** The lines of the explanation a page shows. */
        private const val EXPLAINED = 8

        private fun hostOf(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

        private fun PageDecor.toApi() = PageDecorInfo(
            facets = facets.map { FacetDecorInfo(it.facet, it.values) },
            panelAfter = panelAfter,
            reviews = reviews?.let { ReviewDecorInfo(it.selector, it.idAttribute, it.idPrefix) },
            pictures = pictures?.selector,
            cards = cards?.let { CardDecorInfo(it.selector, it.link) },
        )
    }
}
