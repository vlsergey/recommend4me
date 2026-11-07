package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

private val log = LoggerFactory.getLogger(PictureService::class.java)

data class PictureStatus(
    val total: Int,
    val analyzed: Int,
    val failed: Int,
    val running: Boolean,
    val perMinute: Double?,
)

/** A picture to serve: a local file, or the address of the original to send the browser to. */
sealed interface Served {
    data class File(val path: Path, val contentType: String) : Served

    data class Redirect(val url: String) : Served
}

/**
 * The pictures of the items of every source: the cover (position 0) and every screenshot, analysed by the picture
 * encoder from the ORIGINAL in the background ([PictureAnalysis]): every picture of the graded works first, then the
 * other items from the top of the recommendations down, every picture of an item at once; the sources share the work.
 *
 * WHAT IS KEPT FOLLOWS THE GRADES: the vector and a preview always, the original only for works graded "can be played"
 * or more — graded so later, the originals are downloaded again; graded lower, they are deleted.
 */
@Service
class PictureService(
    private val stores: Stores,
    private val plugins: Plugins,
    private val contexts: SourceContexts,
    private val analysis: PictureAnalysis,
) {
    private val serving = ReentrantLock()

    /**
     * The pictures of the work changed hands, or its grades changed: keep or drop its originals, and look at the queue.
     */
    @EventListener
    fun gradesChanged(event: GradesChanged) {
        val store = stores.source(event.source) ?: return
        if (!keepsOriginals(store, event.itemId)) store.files.delete(store.pictures.forgetOriginals(event.itemId))
        analysis.signal()
    }

    private fun keepsOriginals(store: SourceStore, itemId: String): Boolean =
        (store.ratings.ofItem(itemId).maxOfOrNull { it.grade } ?: 0) >= KEEP_FROM

    fun status(): PictureStatus {
        val encoder = plugins.imageEncoder()
        return PictureStatus(
            total = stores.sources.sumOf { it.pictures.count() },
            analyzed = encoder?.let { e -> stores.sources.sumOf { it.pictures.countAnalyzed(e.id) } } ?: 0,
            failed = stores.sources.sumOf { it.pictures.countFailed() },
            running = analysis.running,
            perMinute = analysis.perMinute,
        )
    }

    /** A picture to show: the preview (downloaded now if missing), or the original when [full] is asked. */
    fun serve(key: ItemKey, position: Int, full: Boolean): Served? {
        val store = stores.source(key.source)
        val picture = store?.pictures?.find(key.id, position, null)
        return when {
            store == null || picture == null -> null
            full ->
                picture.fullFile?.let { file(store, it) }
                    ?: Served.Redirect(store.source.pictureAddresses(picture.url).firstOrNull() ?: picture.url)
            else ->
                picture.previewFile?.let { file(store, it) } ?: serving.withLock { previewNow(store, key, position) }
        }
    }

    /** The file [relative] of the source's pictures, served as its extension tells. */
    private fun file(store: SourceStore, relative: String) =
        Served.File(store.files.path(relative), PictureFiles.contentType(relative))

    /**
     * The preview of a picture that has none, one request at a time: another may have downloaded it meanwhile, else it
     * is downloaded now and kept; the browser sent to the picture's address when it fails.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun previewNow(store: SourceStore, key: ItemKey, position: Int): Served? {
        val again = store.pictures.find(key.id, position, null)
        return when {
            again == null -> null
            again.previewFile != null -> file(store, again.previewFile)
            else ->
                try {
                    val (bytes, type) =
                        PictureDownloader.fetch(
                            again.url,
                            store.source.pictureHeaders(again.url, contexts.of(store.id)),
                        )
                    val file = store.files.save(key.id, "$position-preview", bytes, type)
                    store.pictures.saveFiles(key.id, position, file, again.fullFile)
                    Served.File(store.files.path(file), type)
                } catch (e: Exception) {
                    // A boundary: the preview is the picture's own address at worst — whatever fails on the way, the
                    // source's plugin making its headers, the download, the files or the database keeping it, the
                    // browser is sent there
                    log.warn("preview {} was not downloaded: {}", again.url, e.message)
                    Served.Redirect(again.url)
                }
        }
    }

    companion object {
        /** Originals are kept for works graded this or more: "can be played". */
        const val KEEP_FROM = Grades.MIDDLE
    }
}
