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
     * The blocks of a wanted page — CSS selectors — the site fills only after the page has loaded (author.today's
     * comments): the browser sends the page again once one of them is filled, however little the page grew by it.
     */
    val lateBlocks: List<String>
        get() = emptyList()

    /**
     * Raised when the source reads its pages differently: every kept page ([SourceContext.keepPage]) is handed to
     * [capture] again — of a source whose site never changed the form of its pages, its one way of reading them
     * ([parsers]).
     */
    val parserVersion: Int
        get() = 1

    /**
     * The ways the source reads its pages, each of the pages downloaded since its date ([PageParser]): a kept page read
     * by another version than the way of its day is handed to [capture] again. One way, of every page, by default.
     */
    val parsers: List<PageParser>
        get() = listOf(PageParser(null, parserVersion))

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
