package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.author.AuthorPlaces
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemKeys
import io.github.vlsergey.recommend4me.item.ItemRemoval
import io.github.vlsergey.recommend4me.picture.SiteItemPictureListEvent
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.tag.KeptOutWorks
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import io.github.vlsergey.recommend4me.worklist.ListPredictions
import java.nio.file.Path
import java.time.Instant
import kotlin.concurrent.withLock
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/** The [SourceContext] the application hands each source: its stores behind the plugin API. */
@Component
class SourceContexts(
    private val stores: Stores,
    private val settings: Settings,
    private val events: ApplicationEventPublisher,
    /** A work a source keeps no more removed as the user removes one: got when asked for, it reads the sources too. */
    private val removal: ObjectProvider<ItemRemoval>,
    /**
     * The works their tags keep out of the library, of every source alike: got when asked for, it reads the sources.
     */
    private val keptOut: ObjectProvider<KeptOutWorks>,
    /** The places on the web the authors' pages list: got when asked for, it reads the sources too. */
    private val authorPlaces: ObjectProvider<AuthorPlaces>,
    /** The index of the list: the predictions the items are read in the order of. */
    private val indexes: ListIndexes,
) {

    fun of(sourceId: String): SourceContext {
        val store = stores.source(sourceId) ?: error("No such source: $sourceId")
        return Context(store)
    }

    private inner class Context(private val store: SourceStore) : SourceContext {
        override val sourceId: String
            get() = store.id

        override val items: ItemStore =
            keptOut.getObject().guard(store, Items(store, events) { removal.getObject().remove(ItemKey(store.id, it)) })
        override val database: DSLContext
            get() = store.sourceDsl

        override val settings: SourceSettings = this@SourceContexts.settings.of(store.id)
        override val signals: SiteSignals =
            object : SiteSignals {
                override fun setSignal(itemId: Long, signal: String, value: String?) =
                    store.writing.withLock { store.signals.set(itemId, signal, value) }

                override fun withSignal(signal: String): Map<Long, String> = store.signals.withSignal(signal)
            }
        override val folder: Path
            get() = store.folder

        override fun keepPage(url: String, html: String, itemId: Long?) =
            store.writing.withLock {
                Instant.now().let { now ->
                    store.pages.keep(url, html, itemId, now, PageParser.of(store.source.parsers, now).version)
                }
            }

        override fun authorPlaces(author: String, urls: List<String>) =
            authorPlaces.getObject().said(store.id, author, urls)

        override fun gradedItems(): Set<Long> = store.ratings.gradedItems()

        override fun jobState(key: String): String? = this@SourceContexts.settings.get("job.${store.id}.$key")

        override fun itemsInOrder(): LongArray {
            val graded = store.ratings.gradedItems()
            val predictions =
                indexes.of(store.type).searching { ListPredictions.ofEvery(it, store.id) }.mapValues { it.value.score }
            val keys =
                store.items
                    .keys()
                    .sortedWith(
                        compareByDescending<ItemKeys> { it.id in graded }
                            .thenByDescending { predictions[it.id] ?: Double.NEGATIVE_INFINITY }
                            .thenByDescending { it.updatedAt }
                    )
            return LongArray(keys.size) { keys[it].id }
        }
    }

    /**
     * The source's writes, one at a time ([SourceStore.writing]); its reads free. Its items' facets, reviews, parts and
     * series are kept by stores of their own. A work is named by the application's number of it, as the plugin's own
     * tables of the source's database bind it too.
     */
    private class Items(
        private val store: SourceStore,
        private val events: ApplicationEventPublisher,
        private val removal: (Long) -> Unit,
    ) :
        ItemStore,
        ItemNumbers by store.items,
        ItemFacetStore by SourceItemFacets(store, events),
        ItemReviewStore by SourceItemReviews(store),
        ItemPartStore by SourceItemParts(store, events),
        ItemSeriesStore by SourceItemSeries(store) {
        private fun <T> write(block: () -> T): T = store.writing.withLock(block)

        override fun upsert(item: ItemHead, now: Instant): Upserted = write { store.items.upsert(item, now) }

        override fun find(itemId: Long): StoredItem? = store.items.find(itemId)

        override fun remove(itemId: Long) = removal(itemId)

        override fun count(): Int = store.items.count()

        override fun latestUpdate(): Instant? = store.items.latestUpdate()

        override fun setNumbers(itemId: Long, numbers: Map<String, Double?>) = write {
            store.items.setNumbers(itemId, numbers)
        }

        override fun setTexts(itemId: Long, texts: Map<String, String?>) = write { store.items.setTexts(itemId, texts) }

        override fun texts(itemId: Long): Map<String, String> = store.items.texts(itemId)

        override fun setPictures(itemId: Long, urls: List<String?>) {
            write { store.files.delete(store.pictures.sync(itemId, urls)) }
            events.publishEvent(SiteItemPictureListEvent(store.id))
        }
    }
}
