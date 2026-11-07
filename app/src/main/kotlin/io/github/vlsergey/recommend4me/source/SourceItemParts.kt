package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.part.PartsSaved
import kotlin.concurrent.withLock
import org.springframework.context.ApplicationEventPublisher

/**
 * The parts — chapters, volumes — of a source's items, as the source's plugin writes and reads them; the writes one at
 * a time.
 */
internal class SourceItemParts(private val store: SourceStore, private val events: ApplicationEventPublisher) :
    ItemPartStore {
    override fun saveParts(itemId: String, parts: List<PartData>) {
        store.writing.withLock { store.parts.save(itemId, parts) }
        if (parts.any { it.content != null }) events.publishEvent(PartsSaved(store.id))
    }

    override fun parts(itemId: String): Map<String, Boolean> =
        store.parts.ofItem(itemId).associate { it.partId to it.hasText }
}
