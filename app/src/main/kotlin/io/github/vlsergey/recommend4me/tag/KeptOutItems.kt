package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.Upserted
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
    /** The works of this reading kept out, by their numbers. */
    private val out = ConcurrentHashMap.newKeySet<Long>()

    /** The works of this reading kept out, the number each had by the site's id: written again, not kept. */
    private val outOfSite = ConcurrentHashMap<String, Long>()

    override fun upsert(item: ItemHead, now: Instant): Upserted =
        outOfSite[item.siteId]?.let { Upserted(it, false) } ?: items.upsert(item, now)

    override fun setFacet(itemId: Long, facet: String, values: List<FacetValue>) {
        if (itemId in out) return
        items.setFacet(itemId, facet, values)
        if (keepsOut(facet, values)) {
            items.find(itemId)?.let { outOfSite[it.siteId] = itemId }
            out += itemId
            items.remove(itemId)
        }
    }

    override fun setNumbers(itemId: Long, numbers: Map<String, Double?>) {
        if (itemId !in out) items.setNumbers(itemId, numbers)
    }

    override fun setTexts(itemId: Long, texts: Map<String, String?>) {
        if (itemId !in out) items.setTexts(itemId, texts)
    }

    override fun setPictures(itemId: Long, urls: List<String?>) {
        if (itemId !in out) items.setPictures(itemId, urls)
    }

    override fun saveReviews(itemId: Long, reviews: List<ReviewData>) {
        if (itemId !in out) items.saveReviews(itemId, reviews)
    }

    override fun saveParts(itemId: Long, parts: List<PartData>, whole: Boolean) {
        if (itemId !in out) items.saveParts(itemId, parts, whole)
    }

    override fun placeInSeries(itemId: Long, key: String, position: Int) {
        if (itemId !in out) items.placeInSeries(itemId, key, position)
    }
}
