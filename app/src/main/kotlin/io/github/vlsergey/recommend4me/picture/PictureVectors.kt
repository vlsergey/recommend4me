package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.api.model.WorkKind
import io.github.vlsergey.recommend4me.encoder.ImageEncoder
import io.github.vlsergey.recommend4me.encoder.PreparedPicture
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.WorkKeys
import io.github.vlsergey.recommend4me.workload.Workload
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(PictureVectors::class.java)

/**
 * THE PICTURES DOWNLOADED ANALYSED BY THE PICTURE ENCODER, A WORK OF THE QUEUE (owner, 2026-11-19: "no loop in the
 * background"): of the works whose pictures were downloaded ([PicturesDownloaded]) — kept waiting in the queue
 * ([WorkKeys]) — every picture's original read from the disk, decoded, prepared and encoded, [BATCH] at a time, a
 * [PORTION] a turn, a tail planned again after it ([Workload.again]); its vector kept, its original deleted unless its
 * work keeps it (graded [PictureService.KEEP_FROM] or more), the works told ([PicturesAnalysed]). A picture no reader
 * decodes is a lasting failure.
 */
@Component
class PictureVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val workload: Workload,
    private val events: ApplicationEventPublisher,
) {
    // What the last run of the application left undone of it: planned again by the queue at its start
    init {
        workload.resumption.resumes(WorkKind.PICTURE_VECTORS) { _, reason -> plan(reason) }
    }

    /** Whether pictures are to be analysed. */
    fun running(): Boolean = workload.due(WORK)

    @EventListener
    fun downloaded(event: PicturesDownloaded) {
        workload.keys.want(WORK, event.ids.map { "${event.source}$SEP$it" })
        plan("pictures", event.ids.size)
    }

    /** Pictures downloaded and not analysed by the encoder in use — what the data say: their works waiting. */
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val encoder = plugins.imageEncoder() ?: return
        val waiting =
            stores.sources.flatMap { s -> s.pictures.queue.itemsDownloaded(encoder.id).map { "${s.id}$SEP$it" } }
        if (waiting.isEmpty()) return
        workload.keys.want(WORK, waiting)
        plan("pictures", waiting.size)
    }

    private fun plan(reason: String, count: Int = 1) =
        workload.plan(WORK, Instant.now().plus(QUIET), reason, count) { analysed() }

    /**
     * The pictures of the works waiting analysed, a [PORTION] at most; of a tail left, the work planned again after it.
     */
    private fun analysed() {
        val encoder = plugins.imageEncoder() ?: return
        val taken = workload.keys.wanted(WORK)
        if (taken.isEmpty()) return
        var room = PORTION
        val done = ArrayList<String>()
        taken.groupBy({ it.substringBefore(SEP) }, { it.substringAfter(SEP) }).forEach { (source, ids) ->
            val s = stores.source(source)
            val pictures = s?.pictures?.queue?.downloadedOf(ids, encoder.id).orEmpty()
            val now = pictures.take(room)
            room -= now.size
            // Of the works of the pictures analysed whole, or of none to analyse, nothing left waiting
            val later = pictures.drop(now.size).mapTo(HashSet()) { it.itemId }
            done += ids.filter { it !in later }.map { "$source$SEP$it" }
            if (s != null && now.isNotEmpty()) {
                now.chunked(BATCH).forEach { analysed(s, it, encoder) }
                events.publishEvent(PicturesAnalysed(source, now.map { it.itemId }.distinct()))
            }
        }
        workload.keys.done(WORK, done)
        if (done.size < taken.size) workload.again("portion")
    }

    /** The [pictures] of the source [s] decoded, prepared and encoded, their vectors kept. */
    private fun analysed(s: SourceStore, pictures: List<Picture>, encoder: ImageEncoder) {
        val started = System.nanoTime()
        val prepared = pictures.parallelStream().map { prepared(s, it, encoder) }.toList()
        val ready = pictures.indices.filter { prepared[it] != null }
        val vectors = if (ready.isEmpty()) emptyList() else encoder.encode(ready.map { prepared[it]!! })
        val now = Instant.now()
        ready.forEachIndexed { k, i -> kept(s, pictures[i], vectors[k], encoder, now) }
        pictures.indices.filter { prepared[it] == null }.forEach { i -> undecoded(s, pictures[i]) }
        log.info(
            "{}: {} pictures analysed, {} not decoded, in {} ms",
            s.id,
            ready.size,
            pictures.size - ready.size,
            (System.nanoTime() - started) / NANOS_PER_MILLI,
        )
    }

    /** The original of the picture read from the disk, decoded and prepared; null when no reader decodes it. */
    // A BOUNDARY: bytes of the web go through the readers of the formats and the encoder's preparing, and a malformed
    // picture may fail them in any way — it is a picture that cannot be decoded
    @Suppress("TooGenericExceptionCaught")
    private fun prepared(s: SourceStore, p: Picture, encoder: ImageEncoder): PreparedPicture? =
        try {
            PictureFiles.decode(Files.readAllBytes(s.files.path(p.fullFile!!)))?.let(encoder::prepare)
        } catch (e: Exception) {
            log.debug("{}: the picture {}/{} cannot be decoded: {}", s.id, p.itemId, p.position, e.message)
            null
        }

    /** The picture's [vector] kept; its original deleted unless its work keeps originals. */
    private fun kept(s: SourceStore, p: Picture, vector: FloatArray, encoder: ImageEncoder, now: Instant) {
        val keeps = (s.ratings.ofItem(p.itemId).maxOfOrNull { it.grade } ?: 0) >= PictureService.KEEP_FROM
        s.pictures.queue.saveAnalysis(
            p.itemId,
            p.position,
            vector,
            encoder.id,
            p.previewFile,
            p.fullFile.takeIf { keeps },
            now,
        )
        if (!keeps) s.files.delete(listOfNotNull(p.fullFile))
    }

    /** A picture no reader decodes: a lasting failure, its original deleted. */
    private fun undecoded(s: SourceStore, p: Picture) {
        s.pictures.saveFiles(p.itemId, p.position, p.previewFile, null)
        s.files.delete(listOfNotNull(p.fullFile))
        s.pictures.queue.saveError(p.itemId, p.position, "cannot decode", p.failures + 1, null)
    }

    private companion object {
        val WORK = Work(WorkKind.PICTURE_VECTORS)

        /** Pictures per encoder run. */
        const val BATCH = 64

        /** Pictures analysed by one turn of the work: minutes, a grade and a training between. */
        const val PORTION = 1_024

        /** The quiet after pictures downloaded before they are analysed: a round's of every source at once. */
        val QUIET: Duration = Duration.ofSeconds(5)

        /** The key of a work waiting in the queue: its source and its id. */
        const val SEP = '\t'

        /** The nanoseconds of a millisecond: a unit. */
        const val NANOS_PER_MILLI = 1_000_000
    }
}
