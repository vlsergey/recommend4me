package io.github.vlsergey.recommend4me.item

/**
 * A source told the other names of values of its [facet]: the site's tags of them to bind; those [told] anew — a
 * synonym's key to the key of the value it is a name of — bound to the value's tag where bound elsewhere
 * ([io.github.vlsergey.recommend4me.tag.SiteTagWords]).
 */
data class SiteTagSynonymChangeEvent(val source: String, val facet: String, val told: Map<String, String> = emptyMap())
