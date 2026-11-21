package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.item.ItemKey

/**
 * The user put works of the content type [type] together or apart — two items one work or two, works in a series or out
 * of it — or merged two normalized authors or took an author of a site out of one: what is made of the groups of the
 * works (the manners of the authors and of the series) is to be made again. Of two items made one work or two, [items]:
 * every item of the works they were of before — what a work's items are shown with moved of them alone; none of works
 * put in a series or out of it, nor of authors: no item's work moved.
 */
data class WorkGroupChangeEvent(val type: String, val items: Collection<ItemKey>)
