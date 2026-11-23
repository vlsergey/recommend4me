package io.github.vlsergey.recommend4me.item

/**
 * A source told anew that values of its [facet] are under others ([told]: a value's key to the key of one above it, the
 * metatags of AO3): of the site's tags, the tag of the one put under the tag of the other
 * ([io.github.vlsergey.recommend4me.tag.SiteTagWords]).
 */
data class SiteTagParentChangeEvent(val source: String, val facet: String, val told: Set<Pair<String, String>>)
