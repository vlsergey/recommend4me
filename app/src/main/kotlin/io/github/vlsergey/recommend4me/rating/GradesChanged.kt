package io.github.vlsergey.recommend4me.rating

/** The user graded or ungraded a work of a content type. */
data class GradesChanged(val type: String, val source: String, val itemId: String)
