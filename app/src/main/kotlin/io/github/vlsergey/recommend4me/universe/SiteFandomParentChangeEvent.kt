package io.github.vlsergey.recommend4me.universe

/**
 * The [source] told anew that fandoms of it are under others ([told]: a fandom's key to the key of one above it, the
 * metatags of AO3): the universe of the one put under the universe of the other ([SiteFandomWords]).
 */
data class SiteFandomParentChangeEvent(val source: String, val told: Set<Pair<String, String>>)
