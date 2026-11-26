package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.worklist.WorkDatum
import java.time.Instant
import kotlin.concurrent.withLock

/** The reviews of a source's items, as the source's plugin writes and reads them; the writes one at a time. */
internal class SourceItemReviews(private val store: SourceStore) : ItemReviewStore {
    override fun saveReviews(itemId: Long, reviews: List<ReviewData>) {
        store.writing.withLock {
            // Dated as they change: what the sets of the reviews are made of
            if (store.reviews.save(itemId, reviews)) {
                store.changes.changed(listOf(itemId), WorkDatum.REVIEWS, Instant.now())
            }
        }
    }

    override fun reviewIds(itemId: Long): Set<String> = store.reviews.ids(itemId)

    override fun reviewContents(itemId: Long): Map<String, String> =
        store.reviews.ofItem(itemId).associate { it.reviewId to it.content }
}
