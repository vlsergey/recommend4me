package io.github.vlsergey.recommend4me.universe

/**
 * The sites' fandoms [keys] of the [source] bound to universes anew, as works came to have them
 * ([FandomNormalization]): what their site told of them before is done now ([SiteFandomWords]).
 */
data class SiteFandomBindingChangeEvent(val source: String, val keys: Set<String>)
