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
import io.github.vlsergey.recommend4me.item.SitesSay
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.PageDecor
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.suggestion.toApi
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.work.Works
import java.net.URI
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

/**
 * THE SITE AS THE APPLICATION'S INTERFACE: what the browser extension shows on a page the user opens — on a work's page
 * the work as the application knows it, on a list the prediction of every card. The extension changes things through
 * the same calls as the application's own interface.
 */
@RestController
class PagesController(
    private val stores: Stores,
    private val plugins: Plugins,
    private val cards: ItemCards,
    private val details: ItemDetailsReader,
    private val suggestions: FacetSuggestions,
    private val works: Works,
    private val sitesSay: SitesSay,
    private val texts: TextVectors,
) : PagesApi {

    /** The source whose site the address is on: one that wants the page, or one of the same host. */
    private fun sourceOf(url: String): SourceStore? =
        stores.sources.firstOrNull { s -> s.source.capturePatterns.any { it.matches(url) } }
            ?: hostOf(url)?.let { host -> stores.sources.firstOrNull { hostOf(it.source.homepage) == host } }

    override fun getPage(url: String): ResponseEntity<PageView> {
        val store = sourceOf(url) ?: return ResponseEntity.notFound().build()
        val decor = store.source.pageDecor
        val itemId = runCatching { store.source.itemIdOf(url) }.getOrNull()
        val item = itemId?.takeIf { store.items.find(it) != null }?.let { item(store, it, decor) }
        // The work's chances still to come: the extension asks again till they are there
        return ResponseEntity.status(if (item?.pending == true) HttpStatus.ACCEPTED else HttpStatus.OK)
            .body(
                PageView(
                    source = store.id,
                    type = store.type,
                    decor = decor?.toApi() ?: PageDecorInfo(emptyList()),
                    itemId = itemId,
                    item = item,
                )
            )
    }

    private fun item(store: SourceStore, itemId: String, decor: PageDecor?): PageItem? {
        val key = ItemKey(store.id, itemId)
        val type = stores.typeOf(store.id)
        val summary = cards.cards(type, listOf(key)).firstOrNull() ?: return null
        val schema = store.schema
        val wanted =
            decor?.facets.orEmpty().map { it.facet }.toSet() +
                schema.facets.filter { it.suggest || it.infer }.map { it.key }
        // Its chances made now in memory, once for the suggestions and the values both
        val chances = suggestions.chances(store, listOf(itemId))[itemId].orEmpty()
        val suggested = suggestions.ofItem(store, itemId, chances).map { it.toApi(store) }
        // The values of the work, of every item of it, as the web interface shows them
        val clusters = works.of(type)
        val work = cards.ofWork(type, clusters, key, store.items.facets(itemId), chances)
        val (site, workChances) = work
        val corrections = store.corrections.facetsOf(itemId)
        val names = wanted.associateWith { facet ->
            store.names(
                facet,
                site[facet].orEmpty() +
                    corrections.filter { it.facet == facet }.map { it.key } +
                    ModelValues.named(workChances?.get(facet)),
            )
        }
        val pictureMarks = store.marks.pictureMarksOf(itemId)
        return PageItem(
            summary = summary,
            facets =
                cards.facets(
                    store,
                    work,
                    corrections,
                    names,
                    sitesSay.of(type, store, clusters.fullestFirst(key)),
                    empty = { true },
                    complete = store.corrections.completeOf(itemId),
                ) {
                    it in wanted
                },
            suggestions = suggested,
            pictures =
                store.pictures.ofItem(itemId, plugins.imageEncoder()?.id).map { p ->
                    PagePicture(
                        p.position,
                        p.url,
                        pictureMarks.firstOrNull { it.position == p.position && it.url == p.url }?.mark,
                    )
                },
            reviewMarks = store.marks.reviewMarksOf(itemId),
            ratings = details.ratings(type, key),
            pending = texts.awaited(clusters.members(key)),
        )
    }

    override fun getCards(cardsRequest: CardsRequest): ResponseEntity<List<CardView>> {
        val store = sourceOf(cardsRequest.url) ?: return ResponseEntity.ok(emptyList())
        val base = runCatching { URI(cardsRequest.url) }.getOrNull()
        val ids =
            cardsRequest.links.distinct().mapNotNull { link ->
                val absolute = runCatching { base?.resolve(link)?.toString() ?: link }.getOrDefault(link)
                runCatching { store.source.itemIdOf(absolute) }.getOrNull()?.let { link to it }
            }
        val known = store.items.heads(ids.map { it.second })
        val keys = ids.map { it.second }.filter { it in known }.distinct().map { ItemKey(store.id, it) }
        val summaries = cards.cards(stores.typeOf(store.id), keys).associateBy { it.item }
        return ResponseEntity.ok(
            ids.mapNotNull { (link, id) ->
                summaries[id]?.let { s ->
                    CardView(
                        link,
                        store.id,
                        id,
                        s.title,
                        grade = s.grade,
                        previousGrade = s.previousGrade,
                        prediction = s.prediction?.score,
                        interest = s.interest,
                        interestChance = s.interestChance,
                    )
                }
            }
        )
    }

    companion object {
        private fun hostOf(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()

        private fun PageDecor.toApi() =
            PageDecorInfo(
                facets = facets.map { FacetDecorInfo(facet = it.facet, propertyValues = it.values, after = it.after) },
                panelAfter = panelAfter,
                reviews = reviews?.let { ReviewDecorInfo(it.selector, it.idAttribute, it.idPrefix) },
                pictures = pictures?.selector,
                cards = cards?.let { CardDecorInfo(it.selector, it.link) },
                nextPage = nextPage,
            )
    }
}
