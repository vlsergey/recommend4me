package io.github.vlsergey.recommend4me.part

/** A source stored parts of the texts of its items [ids]: there may be windows to encode. */
data class SiteItemChapterChangeEvent(val source: String, val ids: List<Long>)
