package io.github.vlsergey.recommend4me.item

/**
 * These items of a source are to be read again — their chances, their search, the models: what the site says of them
 * was written ([SiteItemWriteEvent], told first), or what is said of them — an answer of the user, a merge of tags or
 * of universes, a draft of the model.
 */
data class WorkChangeEvent(val source: String, val ids: List<String>)
