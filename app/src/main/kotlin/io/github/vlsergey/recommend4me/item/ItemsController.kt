package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.ItemsApi
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.ItemPage
import io.github.vlsergey.recommend4me.api.model.ItemPageVersion
import io.github.vlsergey.recommend4me.api.model.ItemQuery
import io.github.vlsergey.recommend4me.api.model.PartText
import io.github.vlsergey.recommend4me.api.model.SearchRefinement
import io.github.vlsergey.recommend4me.picture.PictureService
import io.github.vlsergey.recommend4me.picture.Served
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.search.SearchStep
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.StoredItem
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.downloadsPages
import jakarta.servlet.http.HttpServletResponse
import java.net.URI
import java.time.Duration
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

private val log = LoggerFactory.getLogger(ItemsController::class.java)

/**
 * The answer [found] gives of the item [item] of the source [source] — its store and its head; 404 when the source or
 * the item is not known.
 */
internal inline fun <T : Any> Stores.answerOn(
    source: String,
    item: String,
    found: (SourceStore, StoredItem) -> ResponseEntity<T>,
): ResponseEntity<T> {
    val store = source(source)
    val head = store?.items?.find(item)
    return if (store == null || head == null) ResponseEntity.notFound().build() else found(store, head)
}

/** The list of the works and a work itself: what it is, its pages and pictures, read again from the site or removed. */
@RestController
class ItemsController(
    private val stores: Stores,
    private val list: ItemList,
    private val filters: ListFilters,
    private val details: ItemDetailsReader,
    private val pictures: PictureService,
    private val contexts: SourceContexts,
    private val removal: ItemRemoval,
    private val json: JsonMapper,
) : ItemsApi {

    override fun removeItem(source: String, item: String): ResponseEntity<Unit> =
        if (removal.remove(ItemKey(source, item))) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()

    override fun listItems(type: String, itemQuery: ItemQuery): ResponseEntity<ItemPage> =
        ResponseEntity.ok(pageOf(type, itemQuery))

    /**
     * The page of [listItems] of a search, a version of it as every step of the search ends: a line of JSON each,
     * written and sent on the thread of the request as it is made (api: listItemVersions, written here by hand — the
     * API's interface has no stream). The one who asked gone, the next line fails to be sent, and the search ends.
     */
    @PostMapping(
        "/api/types/{type}/items/versions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_NDJSON_VALUE],
    )
    fun listItemVersions(@PathVariable type: String, @RequestBody itemQuery: ItemQuery, response: HttpServletResponse) {
        response.contentType = MediaType.APPLICATION_NDJSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        val out = response.outputStream
        fun send(version: ItemPageVersion) {
            out.write(json.writeValueAsBytes(version))
            out.write('\n'.code)
            out.flush()
        }
        try {
            val last =
                pageOf(type, itemQuery) { page, next ->
                    send(ItemPageVersion(page.total, page.items, SearchRefinement.valueOf(next.name)))
                }
            send(ItemPageVersion(last.total, last.items))
        } catch (e: ClientAbortException) {
            log.info("{}: the search '{}' given up by the one who asked: {}", type, itemQuery.search, e.message)
        }
    }

    /** The page of the list of the [type] the query [q] asks; of a search, its [versions] told as they are made. */
    private fun pageOf(
        type: String,
        q: ItemQuery,
        versions: ((ItemPage, SearchStep) -> Unit)? = null,
    ): ItemPage {
        // A field sent as null is the field left out: the defaults of the API's own schema
        val d = ItemQuery()
        val ranges =
            q.ranges.orEmpty().map {
                NumberRange.parse(it)
                    ?: throw Refusal.BAD_RANGE.refuse(
                        HttpStatus.BAD_REQUEST,
                        "The range '$it' is not 'number=from..to'",
                        "range" to it,
                    )
            }
        val facets =
            q.facets.orEmpty().map { c ->
                // Of a scale, the values from one to the other alone
                val range = if (c.from == null && c.to == null) null else filters.rangeOf(type, c.facet, c.from, c.to)
                FacetChoice(c.facet, c.hidden.orEmpty().toSet(), c.shown?.toSet(), c.withoutHidden == true, range)
            }
        return list.page(
            type,
            ItemView.valueOf((q.view ?: d.view!!).name),
            ItemOrder.valueOf((q.sort ?: d.sort!!).name),
            q.sortNumber,
            q.search,
            facets,
            q.sources?.toSet()?.takeIf { it.isNotEmpty() },
            ranges,
            q.offset ?: d.offset!!,
            q.limit ?: d.limit!!,
            // The line searched read as the interface's language: the one it is typed in
            language = LocaleContextHolder.getLocale().language,
            versions = versions,
        )
    }

    override fun getItem(source: String, item: String): ResponseEntity<ItemDetails> =
        details.read(ItemKey(source, item))?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun refreshItem(source: String, item: String): ResponseEntity<ItemDetails> =
        stores.answerOn(source, item) { store, _ ->
            refresh(store, item)
            // Its texts encoded in the queue of the computations, as every written work's: not waited for here; its
            // expectation made as its screen is read
            getItem(source, item)
        }

    /**
     * The page of the [item] downloaded again by its source; refused when the source downloads no pages by itself or
     * could not download this one.
     */
    private fun refresh(store: SourceStore, item: String) {
        if (!store.source.downloadsPages)
            throw Refusal.NO_DOWNLOADS.refuse(
                HttpStatus.CONFLICT,
                "The source ${store.id} does not download pages by itself",
            )
        if (!download(store, item))
            throw Refusal.REFRESH_FAILED.refuse(
                HttpStatus.CONFLICT,
                "The source ${store.id} could not download the page of $item",
            )
    }

    /** Whether the source of the [store] downloaded the page of the [item]; refused when the site did not answer. */
    @Suppress("TooGenericExceptionCaught")
    private fun download(store: SourceStore, item: String): Boolean =
        try {
            store.source.refresh(item, contexts.of(store.id))
        } catch (e: Exception) {
            // A boundary: the plugin of a source reads a site, and whatever breaks there — the network, the site's
            // page, the plugin itself — is the site's silence to the user, never the request's failure
            log.warn("{}: {} was not loaded: {}", store.id, item, e.message)
            throw Refusal.SITE_SILENT.refuse(
                    HttpStatus.BAD_GATEWAY,
                    "The site did not answer: ${e.message}",
                    "error" to (e.message ?: e.javaClass.simpleName),
                )
                .apply { initCause(e) }
        }

    override fun getPicture(source: String, item: String, position: Int, full: Boolean): ResponseEntity<Resource> =
        when (val served = pictures.serve(ItemKey(source, item), position, full)) {
            null -> ResponseEntity.notFound().build()
            // A picture changes only together with its address, and then the file is replaced
            is Served.File ->
                ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(served.contentType))
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                    .body(FileSystemResource(served.path))
            is Served.Redirect -> ResponseEntity.status(HttpStatus.FOUND).location(URI.create(served.url)).build()
        }

    override fun getPart(source: String, item: String, partId: String): ResponseEntity<PartText> =
        stores.source(source)?.parts?.text(item, partId)?.let { (part, content) ->
            ResponseEntity.ok(PartText(part.partId, part.position, content, part.title))
        } ?: ResponseEntity.notFound().build()
}
