package io.github.vlsergey.recommend4me.source

/**
 * HOW THE PICTURES OF A [Source]'S ITEMS ARE DOWNLOADED from its site: from where, with which headers. A part of every
 * source, implemented with it; a picture's own address and no headers of a source that tells nothing else.
 */
interface SitePictures {
    /**
     * Where a picture of an item is downloaded from, the best first: an original before the preview it was listed by.
     * The application tries them in order.
     */
    fun pictureAddresses(url: String): List<String> = listOf(url)

    /** Headers of a picture download: a referer, a user agent. */
    fun pictureHeaders(url: String, context: SourceContext): Map<String, String> = emptyMap()
}
