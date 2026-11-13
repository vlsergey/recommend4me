package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * A SOURCE'S WRITES OF ITS WORKS, THE WORKS THEIR TAGS KEEP OUT NOT KEPT ([KeptOutWorks]): the write of a work's facet
 * that [keepsOut] removes the work, and every write of it after, of the same reading, is not kept — a site writes a
 * work's head before its tags, and its texts, chapters and reviews after them.
 */
internal class KeptOutItems(
    private val items: ItemStore,
    /** Whether the values of a facet just written keep their work out. */
    private val keepsOut: (facet: String, values: List<FacetValue>) -> Boolean,
) : ItemStore by items {
    /** The works of this reading kept out. */
    private val out = ConcurrentHashMap.newKeySet<String>()

    override fun upsert(item: ItemHead, now: Instant): Boolean = item.id !in out && items.upsert(item, now)

    override fun setFacet(itemId: String, facet: String, values: List<FacetValue>) {
        if (itemId in out) return
        items.setFacet(itemId, facet, values)
        if (keepsOut(facet, values)) {
            out += itemId
            items.remove(itemId)
        }
    }

    override fun setNumbers(itemId: String, numbers: Map<String, Double?>) {
        if (itemId !in out) items.setNumbers(itemId, numbers)
    }

    override fun setTexts(itemId: String, texts: Map<String, String?>) {
        if (itemId !in out) items.setTexts(itemId, texts)
    }

    override fun setPictures(itemId: String, urls: List<String?>) {
        if (itemId !in out) items.setPictures(itemId, urls)
    }

    override fun saveReviews(itemId: String, reviews: List<ReviewData>) {
        if (itemId !in out) items.saveReviews(itemId, reviews)
    }

    override fun saveParts(itemId: String, parts: List<PartData>) {
        if (itemId !in out) items.saveParts(itemId, parts)
    }

    override fun placeInSeries(itemId: String, key: String, position: Int) {
        if (itemId !in out) items.placeInSeries(itemId, key, position)
    }
}
