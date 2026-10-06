package io.github.vlsergey.recommend4me.item

/** The searchable data of these items of a source — title, facets, texts — was written. */
data class ItemsChanged(val source: String, val ids: List<String>)
