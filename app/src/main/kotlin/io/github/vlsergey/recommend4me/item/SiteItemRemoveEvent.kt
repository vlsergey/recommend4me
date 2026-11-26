package io.github.vlsergey.recommend4me.item

/**
 * A site's item [itemId] of the [source] removed, with everything of it: the [remaining] items of its work — of other
 * sites, or of none, the work gone with it — and whether its work was [graded]. The work's grade is the work's: an item
 * gone changes it not (owner, 2026-11-19: "a work removed from one site does not mean it is not left on another").
 */
data class SiteItemRemoveEvent(
    val type: String,
    val source: String,
    val itemId: Long,
    val remaining: List<ItemKey>,
    val graded: Boolean,
)
