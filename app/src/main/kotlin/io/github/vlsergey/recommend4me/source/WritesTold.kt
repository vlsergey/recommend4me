package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.part.PartsSaved
import org.springframework.context.ApplicationEventPublisher

/**
 * What the writes of a source's items tell the rest of the application — the items written ([ItemsChanged]), the parts'
 * texts saved ([PartsSaved]): at once, or of a batch ([ItemStore.batch]) once, when it ends, for every item of it
 * together. The listeners take a list of items: a thousand works told together are bound, queued and planned once.
 */
class WritesTold(private val source: String, private val events: ApplicationEventPublisher) {
    private class Batch {
        val ids = LinkedHashSet<String>()
        var parts = false
    }

    /** The batch of the thread writing, if it writes one: each thread its own, the writes of others told at once. */
    private val open = ThreadLocal<Batch?>()

    fun itemsChanged(ids: Collection<String>) {
        val batch = open.get()
        if (batch != null) batch.ids += ids else events.publishEvent(ItemsChanged(source, ids.toList()))
    }

    fun partsSaved() {
        val batch = open.get()
        if (batch != null) batch.parts = true else events.publishEvent(PartsSaved(source))
    }

    /**
     * [writes] told once, when they end — what was written before a failure too, as it is kept; a batch inside another
     * is a part of it.
     */
    fun <T> batch(writes: () -> T): T {
        if (open.get() != null) return writes()
        val batch = Batch()
        open.set(batch)
        try {
            return writes()
        } finally {
            open.remove()
            if (batch.ids.isNotEmpty()) events.publishEvent(ItemsChanged(source, batch.ids.toList()))
            if (batch.parts) events.publishEvent(PartsSaved(source))
        }
    }
}
