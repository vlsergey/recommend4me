package io.github.vlsergey.recommend4me.source

/**
 * THE PAGES OF THE SITE THE BROWSER SENDS a [Source]: which it wants, and how it reads them. A part of every source,
 * implemented with it; nothing of a source that reads no page of the browser.
 */
interface BrowserTracking {
    /** The addresses of the pages the source wants from the browser, matched against the whole URL. */
    val capturePatterns: List<Regex>
        get() = emptyList()

    /**
     * Raised when the source reads its pages differently: every kept page ([SourceContext.keepPage]) is handed to
     * [capture] again on the next start.
     */
    val parserVersion: Int
        get() = 1

    /**
     * Reads a page the browser sent — or one kept before, read again after the parser changed — and writes what it
     * says. Returns the items it touched.
     */
    fun capture(page: CapturedPage, context: SourceContext): List<String> = emptyList()

    /**
     * The requests of the site that bring what it says [tags] — keys of its [facet] — mean, made for the application by
     * the browser from a page of the site the user has open (a site that checks its visitors lets the user's browser
     * in, not the application), and sent back as captured pages under their [WantedPage.url], read by [capture]. Empty:
     * the site does not describe them, or the source reads it otherwise.
     */
    fun describing(facet: String, tags: List<String>): List<WantedPage> = emptyList()
}
