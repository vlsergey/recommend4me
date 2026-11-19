package io.github.vlsergey.recommend4me.source

import java.time.Instant

/**
 * A WAY A SOURCE READS ITS SITE'S PAGES, of the pages downloaded [since] alone (of every one, when null): a site
 * changes the form of its pages, and the pages kept of before are read by the way of their day (owner, 2026-11-19: "the
 * parser's implementations have the date they apply from — the pages downloaded since which date they can read — and a
 * version"). Its [version] is written with every page it read; a kept page read by another version than the way of its
 * day has now is read again. A version no other way of the source has.
 */
data class PageParser(val since: Instant?, val version: Int) {
    companion object {
        /**
         * Of the [parsers] of a source, the one reading a page downloaded at [downloaded]: the latest begun by then.
         */
        fun of(parsers: List<PageParser>, downloaded: Instant): PageParser =
            parsers.filter { it.since == null || !it.since.isAfter(downloaded) }.maxByOrNull { it.since ?: Instant.MIN }
                ?: error("no way of reading a page downloaded at $downloaded")
    }
}
