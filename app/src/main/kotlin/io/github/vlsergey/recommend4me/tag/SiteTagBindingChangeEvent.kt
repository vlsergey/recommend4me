package io.github.vlsergey.recommend4me.tag

/**
 * The sites' tags [keys] of the [source] bound to normalized tags anew, as works came to have them
 * ([TagNormalization]): what their site told of them before is done now ([SiteTagWords]).
 */
data class SiteTagBindingChangeEvent(val source: String, val keys: Set<String>)
