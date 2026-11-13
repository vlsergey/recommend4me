package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemRemoval
import io.github.vlsergey.recommend4me.picture.PicturesListed
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.tag.KeptOutWorks
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
                override fun set(itemId: String, signal: String, value: String?) =
                    store.writing.withLock { store.signals.set(itemId, signal, value) }

                override fun withSignal(signal: String): Map<String, String> = store.signals.withSignal(signal)
            }
        override val folder: Path
            get() = store.folder

        override fun keepPage(url: String, html: String, itemId: String?) =
            store.writing.withLock { store.pages.keep(url, html, itemId, Instant.now(), store.source.parserVersion) }

        override fun gradedItems(): Set<String> = store.ratings.gradedItems()

        override fun jobState(key: String): String? = this@SourceContexts.settings.get("job.${store.id}.$key")

        override fun itemsInOrder(): List<String> {
            val graded = store.ratings.gradedItems()
            val predictions = stores.typeOf(store.id).predictions.scores(store.id)
            return store.items
                .keys()
                .sortedWith(
                    compareByDescending<io.github.vlsergey.recommend4me.item.ItemKeys> { it.id in graded }
                        .thenByDescending { predictions[it.id] ?: Double.NEGATIVE_INFINITY }
                        .thenByDescending { it.updatedAt }
                )
                .map { it.id }
        }
    }

    /**
     * The source's writes, one at a time ([SourceStore.writing]); its reads free. Its items' facets, reviews, parts and
     * series are kept by stores of their own.
     */
    private class Items(
        private val store: SourceStore,
        private val events: ApplicationEventPublisher,
        private val removal: (String) -> Unit,
    ) :
        ItemStore,
        ItemFacetStore by SourceItemFacets(store, events),
        ItemReviewStore by SourceItemReviews(store),
        ItemPartStore by SourceItemParts(store, events),
        ItemSeriesStore by SourceItemSeries(store) {
        private fun <T> write(block: () -> T): T = store.writing.withLock(block)

        override fun upsert(item: ItemHead, now: Instant): Boolean = write { store.items.upsert(item, now) }

        override fun find(itemId: String): StoredItem? = store.items.find(itemId)

        override fun remove(itemId: String) = removal(itemId)

        override fun count(): Int = store.items.count()

        override fun latestUpdate(): Instant? = store.items.latestUpdate()

        override fun setNumbers(itemId: String, numbers: Map<String, Double?>) = write {
            store.items.setNumbers(itemId, numbers)
        }

        override fun setTexts(itemId: String, texts: Map<String, String?>) = write {
            store.items.setTexts(itemId, texts)
        }

        override fun texts(itemId: String): Map<String, String> = store.items.texts(itemId)

        override fun setPictures(itemId: String, urls: List<String?>) {
            write { store.files.delete(store.pictures.sync(itemId, urls)) }
            events.publishEvent(PicturesListed(store.id))
        }
    }
}
