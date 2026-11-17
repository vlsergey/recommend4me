package io.github.vlsergey.recommend4me.source

import kotlin.concurrent.withLock

/**
 * The parts — chapters, volumes — of a source's items, as the source's plugin writes and reads them; the writes one at
 * a time.
 */
internal class SourceItemParts(private val store: SourceStore) : ItemPartStore {
    override fun saveParts(itemId: String, parts: List<PartData>) {
        store.writing.withLock { store.parts.save(itemId, parts) }
        if (parts.any { it.content != null }) store.told.partsSaved()
    }

    override fun parts(itemId: String): Map<String, Boolean> =
        store.parts.ofItem(itemId).associate { it.partId to it.hasText }
}
