package io.github.vlsergey.recommend4me.source

import java.time.Instant

/** How a source gets its items. */
enum class SourceMode {
    /** The whole catalogue, resumable: hours or days of requests to the site. */
    SCRAPE_ALL,

    /** What is new since the last time. */
    UPDATES,

    /**
     * The pages the user opens in the browser, sent by the extension: the application itself
     * requests nothing from the site.
     */
    BROWSER,
}

/**
 * A site the works come from. Implemented by a plugin and declared as a Spring bean.
 *
 * Everything a source learns it writes through the [SourceContext] it is handed: the generic
 * data of its items into the application's tables, anything else into tables of its own
 * ([migrations]) in the same source database.
 */
interface Source {
    /** Stable: the name of the source's folder, of its databases and the first half of every item key. */
    val id: String

    /** The name shown to the user. */
    val title: String

    /** The content type the source's works belong to: `games`, `books`. */
    val contentType: String

    val homepage: String

    val schema: SourceSchema

    val modes: Set<SourceMode>

    /**
     * Flyway locations of the source's own tables in its source database, e.g.
     * `classpath:db/f95zone`. They are migrated with a history table of their own.
     */
    val migrations: List<String> get() = emptyList()

    val settings: List<SettingDef> get() = emptyList()

    /** The page of the item on the site. */
    fun itemUrl(itemId: String): String

    // --- Browser tracking ---

    /** The addresses of the pages the source wants from the browser, matched against the whole URL. */
    val capturePatterns: List<Regex> get() = emptyList()

    /**
     * Raised when the source reads its pages differently: every kept page ([SourceContext.keepPage])
     * is handed to [capture] again on the next start.
     */
    val parserVersion: Int get() = 1

    /**
     * Reads a page the browser sent — or one kept before, read again after the parser changed —
     * and writes what it says. Returns the items it touched.
     */
    fun capture(page: CapturedPage, context: SourceContext): List<String> = emptyList()

    // --- Scraping ---

    /** Runs a job of [mode] (not [SourceMode.BROWSER]) to its end, cancelled or failed. */
    fun scrape(mode: SourceMode, context: SourceContext, job: Job): Unit =
        throw UnsupportedOperationException("$id does not scrape")

    /**
     * Downloads the item's page again now — the button of a single item, or a work the user has
     * just graded whose details are missing. Returns false when the source cannot.
     */
    fun refresh(itemId: String, context: SourceContext): Boolean = false

    /** Whether [refresh] would bring something the item lacks: the details of its current version. */
    fun needsRefresh(itemId: String, context: SourceContext): Boolean = false

    // --- Pictures ---

    /**
     * Where a picture of an item is downloaded from, the best first: an original before the
     * preview it was listed by. The application tries them in order.
     */
    fun pictureAddresses(url: String): List<String> = listOf(url)

    /** Headers of a picture download: a referer, a user agent. */
    fun pictureHeaders(url: String, context: SourceContext): Map<String, String> = emptyMap()
}

/** A page the browser sent: its address, the DOM as the user saw it, and when. */
class CapturedPage(
    val url: String,
    val html: String,
    val capturedAt: Instant,
)
