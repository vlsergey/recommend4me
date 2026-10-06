package io.github.vlsergey.recommend4me.setvector

/** Set vectors of this many items of a content type were made again: the model may want to learn them. */
data class SetVectorsChanged(val type: String, val items: Int)
