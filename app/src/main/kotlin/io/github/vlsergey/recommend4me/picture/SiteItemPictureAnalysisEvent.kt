package io.github.vlsergey.recommend4me.picture

/** The pictures of the works [ids] of the [source] analysed: their vectors made, their sets of screenshots grown. */
data class SiteItemPictureAnalysisEvent(val source: String, val ids: List<String>)
