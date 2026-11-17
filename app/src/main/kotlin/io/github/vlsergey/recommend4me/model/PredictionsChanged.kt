package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey

/** The predictions of the items [keys] of a content type written anew: what reads them reads them again. */
data class PredictionsChanged(val type: String, val keys: List<ItemKey>)
