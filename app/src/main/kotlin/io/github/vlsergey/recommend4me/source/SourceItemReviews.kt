package io.github.vlsergey.recommend4me.source

import kotlin.concurrent.withLock

/** The reviews of a source's items, as the source's plugin writes and reads them; the writes one at a time. */
internal class SourceItemReviews(private val store: SourceStore) : ItemReviewStore {
    override fun saveReviews(itemId: String, reviews: List<ReviewData>) =
        store.writing.withLock {
            store.reviews.save(itemId, reviews)
        }

    override fun reviewIds(itemId: String): Set<String> = store.reviews.ids(itemId)

    override fun reviewContents(itemId: String): Map<String, String> =
        store.reviews.ofItem(itemId).associate { it.reviewId to it.content }
}
