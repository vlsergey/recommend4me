package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.part.SiteItemChapterChangeEvent
import kotlin.concurrent.withLock
import org.springframework.context.ApplicationEventPublisher

/**
 * The parts — chapters, volumes — of a source's items, as the source's plugin writes and reads them; the writes one at
 * a time.
 */
internal class SourceItemParts(private val store: SourceStore, private val events: ApplicationEventPublisher) :
    ItemPartStore {
    override fun saveParts(itemId: Long, parts: List<PartData>, whole: Boolean) {
        // A part taken off the site or come back changes what the work is made of, as a text written does
        val moved = store.writing.withLock { store.parts.save(itemId, parts, whole) }
        if (moved || parts.any { it.content != null }) {
            events.publishEvent(SiteItemChapterChangeEvent(store.id, listOf(itemId)))
        }
    }

    override fun parts(itemId: Long): Map<String, Boolean> =
        store.parts.ofItem(itemId).associate { it.partId to it.hasText }
}
