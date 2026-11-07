package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.encoder.ImageEncoder
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores

/**
 * The next pictures of every source to download and analyse, the sources that have work sharing it: of each, the graded
 * works' first, then by prediction, then the newest.
 */
internal class NextPictures(private val stores: Stores) {

    /** The next pictures, [limit] at most, but those of [skip] (source, item, position): those in flight. */
    fun of(encoder: ImageEncoder, limit: Int, skip: Set<Pair<String, Pair<String, Int>>>): List<PictureJob> {
        val perSource = stores.sources.map { s -> ofSource(s, encoder, limit, skip) }.filter { it.isNotEmpty() }
        if (perSource.isEmpty()) return emptyList()
        val share = (limit / perSource.size).coerceAtLeast(1)
        return perSource.flatMap { it.take(share) }.take(limit)
    }

    /**
     * The next pictures of the source, [limit] at most: those of the graded works first — their originals too, when
     * kept — then of the rest by prediction.
     */
    private fun ofSource(
        s: SourceStore,
        encoder: ImageEncoder,
        limit: Int,
        skip: Set<Pair<String, Pair<String, Int>>>,
    ): List<PictureJob> {
        val best = s.ratings.all().groupBy { it.itemId }.mapValues { (_, list) -> list.maxOf { it.grade } }
        val keep = best.filterValues { it >= PictureService.KEEP_FROM }.keys
        val graded = s.pictures.queue.pendingOf(best.keys.sorted(), encoder.id, keep, limit)
        val rest =
            if (graded.size >= limit) emptyList()
            else {
                val todo = s.pictures.queue.itemsWithPending(encoder.id) - best.keys
                val predictions = stores.typeOf(s.id).predictions.scores(s.id)
                val order = todo.sortedByDescending { predictions[it] ?: Double.NEGATIVE_INFINITY }.take(limit)
                s.pictures.queue.pendingOf(order, encoder.id, emptySet(), limit - graded.size)
            }
        return (graded + rest).map { PictureJob(s, it, it.itemId in keep) }.filter { it.key !in skip }
    }
}
