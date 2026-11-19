package io.github.vlsergey.recommend4me.item

/** A source told the other names of values of its [facet]: the site's tags of them to bind. */
data class SiteTagSynonymChangeEvent(val source: String, val facet: String)
