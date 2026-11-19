package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.encoder.ImageEncoder
import io.github.vlsergey.recommend4me.retry.Backoff
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(PictureBatch::class.java)

/** What a batch of pictures made: how many new vectors every content type got, and how many pictures were analysed. */
internal class BatchDone(val made: Map<String, Int>, val analysed: Int)

/**
 * A batch of downloaded pictures analysed and kept: the failed ones to be tried again later, the prepared ones through
 * the encoder at once, and of every one downloaded its preview, its original when its work keeps originals, and its
 * vector.
 */
internal object PictureBatch {

    /**
     * A picture whose download failed tried again a second after, then twice as late every time, without end (user,
     * 2026-10-21).
     */
    private val RETRIES = Backoff(first = Duration.ofSeconds(1), factor = 2.0)

    /** The nanoseconds of a second: a unit. */
    private const val NANOS_PER_SECOND = 1e9

    /** Analyses and stores the downloaded pictures. */
    fun analyse(fetched: List<Fetched>, encoder: ImageEncoder): BatchDone {
        val started = System.nanoTime()
        saveErrors(fetched)
        val toAnalyze = fetched.filter { it.prepared != null }
        val vectors = if (toAnalyze.isEmpty()) emptyList() else encoder.encode(toAnalyze.map { it.prepared!! })
        val byJob = toAnalyze.indices.associate { toAnalyze[it].job to vectors[it] }
        val now = Instant.now()
        val made = HashMap<String, Int>()
        fetched
            .filter { f -> f.original != null && (f.job.picture.analyzed || f.job in byJob) }
            .forEach { f ->
                keep(f, f.original!!, byJob[f.job], encoder.id, now)
                if (byJob[f.job] != null) made.merge(f.job.store.type, 1, Int::plus)
            }
        log.info(
            "{} pictures: {} analysed, {} failed; encoder {} s",
            fetched.size,
            byJob.size,
            fetched.count { it.error != null },
            "%.1f".format((System.nanoTime() - started) / NANOS_PER_SECOND),
        )
        return BatchDone(made, byJob.size)
    }

    /** The failed downloads counted, each to be tried again when its wait is over — a lasting failure never. */
    private fun saveErrors(fetched: List<Fetched>) {
        val failedAt = Instant.now()
        fetched
            .filter { it.error != null }
            .forEach {
                val p = it.job.picture
                val failures = p.failures + 1
                it.job.store.pictures.queue.saveError(
                    p.itemId,
                    p.position,
                    it.error!!,
                    failures,
                    if (it.lasting) null else RETRIES.nextAttempt(failedAt, failures),
                )
            }
    }

    /** The picture's files written — the preview always, the original when it is kept — and its [vector], if any. */
    private fun keep(
        f: Fetched,
        original: Pair<ByteArray, String>,
        vector: FloatArray?,
        encoder: String,
        now: Instant,
    ) {
        val job = f.job
        val p = job.picture
        val previewFile =
            p.previewFile
                ?: f.preview?.let { (bytes, type) ->
                    job.store.files.save(p.itemId, "${p.position}-preview", bytes, type)
                }
        val fullFile =
            if (job.keep) p.fullFile ?: job.store.files.save(p.itemId, "${p.position}", original.first, original.second)
            else null
        job.store.pictures.queue.saveAnalysis(p.itemId, p.position, vector, encoder, previewFile, fullFile, now)
    }
}
