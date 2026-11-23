package io.github.vlsergey.recommend4me.universe

/**
 * The [source] told synonyms of its fandoms anew ([told]: a synonym's key to the key of the fandom it is a name of):
 * the site's word that they are one fandom, bound to one universe ([SiteFandomWords]).
 */
data class SiteFandomSynonymChangeEvent(val source: String, val told: Map<String, String>)
